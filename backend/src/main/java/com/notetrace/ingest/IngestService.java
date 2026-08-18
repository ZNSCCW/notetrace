package com.notetrace.ingest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.notetrace.ai.EmbeddingProvider;
import com.notetrace.chunk.Chunk;
import com.notetrace.chunk.ChunkRepository;
import com.notetrace.document.Document;
import com.notetrace.document.DocumentRepository;
import com.notetrace.ingest.MarkdownChunker.ChunkPiece;

/**
 * 文档导入管线（FR-1/FR-3/FR-4/FR-6）：
 * 扫描入库目录 → 读文件 → 变更检测（hash，M2 增量埋点）→ 切分 → 存 document/chunks → 向量化写 embedding。
 * 每文件独立事务，失败不影响其他文件。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final Set<String> SUPPORTED_EXT = Set.of("md", "txt");

    private final Path ingestDir;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final MarkdownChunker chunker;
    private final EmbeddingProvider embeddingProvider;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public IngestService(@Value("${notetrace.ingest.dir}") String ingestDir,
                         DocumentRepository documentRepository,
                         ChunkRepository chunkRepository,
                         MarkdownChunker chunker,
                         EmbeddingProvider embeddingProvider,
                         JdbcTemplate jdbcTemplate,
                         TransactionTemplate transactionTemplate) {
        this.ingestDir = Paths.get(ingestDir);
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /** 全量入库：扫描目录处理所有 md/txt，并清理磁盘上已删除的文档。返回实际处理（新增或变更）的文件数 */
    public int ingestAll() throws IOException {
        if (!Files.isDirectory(ingestDir)) {
            Files.createDirectories(ingestDir);
            log.info("入库目录不存在，已创建: {}", ingestDir);
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(ingestDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> SUPPORTED_EXT.contains(ext(p)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        // 删除检测：磁盘上已不存在的文档，同步删除库记录（chunks 由外键 CASCADE 清理）
        Set<String> diskPaths = files.stream()
                .map(f -> ingestDir.relativize(f).toString().replace('\\', '/'))
                .collect(java.util.stream.Collectors.toSet());
        int deleted = 0;
        for (Document doc : documentRepository.findAll()) {
            if (!diskPaths.contains(doc.getSourcePath())) {
                documentRepository.delete(doc);
                log.info("删除已移除的文档: {}", doc.getSourcePath());
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("删除检测: 清理 {} 个已删除文档", deleted);
        }

        int processed = 0;
        for (Path file : files) {
            if (ingestFile(file)) {
                processed++;
            }
        }
        log.info("入库完成: {} 个文件变更/新增, 共扫描 {}, 清理删除 {}", processed, files.size(), deleted);
        return processed;
    }

    /** 处理单个文件（hash 相同则跳过）。返回 true 表示已处理。 */
    public boolean ingestFile(Path file) {
        String content;
        String hash;
        try {
            content = readFileSmart(file);
            hash = sha256(content);
        } catch (IOException e) {
            log.error("读取失败: {}: {}", file, e.getMessage());
            markFailed(file, null);
            return false;
        }
        final String rel = ingestDir.relativize(file).toString().replace('\\', '/');
        final String contentFinal = content;
        final String hashFinal = hash;

        Optional<Document> existing = documentRepository.findBySourcePath(rel);
        // 仅当已成功处理且内容未变时跳过；FAILED 文件即使 hash 相同也要重试
        if (existing.isPresent() && "PROCESSED".equals(existing.get().getStatus())
                && existing.get().getFileHash().equals(hashFinal)) {
            return false; // 未变更，跳过（M2 增量更新基础）
        }

        try {
            List<ChunkPiece> pieces = chunker.chunk(contentFinal);
            transactionTemplate.executeWithoutResult(status -> processDocument(existing, rel, file, hashFinal, pieces));
            log.info("入库: {} ({} 个 chunk)", rel, pieces.size());
            return true;
        } catch (Exception e) {
            log.error("入库失败: {}: {}", file, e.getMessage());
            markFailed(file, hashFinal);
            return false;
        }
    }

    /**
     * 入库失败时写入 FAILED 状态：入库状态页可见失败文件，且清除旧 chunks
     * （FAILED 文档不应再检索到过期内容）；记录实际内容 hash，内容回滚后可恢复处理。
     */
    private void markFailed(Path file, String fileHash) {
        try {
            String rel = ingestDir.relativize(file).toString().replace('\\', '/');
            Document doc = documentRepository.findBySourcePath(rel).orElseGet(Document::new);
            if (doc.getId() == null) {
                doc.setTitle(file.getFileName().toString());
                doc.setSourcePath(rel);
                doc.setSourceType(ext(file));
            }
            doc.setFileHash(fileHash != null ? fileHash : "unreadable");
            doc.setStatus("FAILED");
            documentRepository.save(doc);
            chunkRepository.deleteByDocumentId(doc.getId());
        } catch (Exception ex) {
            log.warn("标记 FAILED 失败: {}", ex.getMessage());
        }
    }

    private void processDocument(Optional<Document> existing, String rel, Path file, String hash,
                                 List<ChunkPiece> pieces) {
        Document doc = existing.orElseGet(Document::new);
        doc.setTitle(file.getFileName().toString());
        doc.setSourcePath(rel);
        doc.setSourceType(ext(file));
        doc.setFileHash(hash);
        doc.setStatus("PROCESSED");
        doc = documentRepository.save(doc);

        // 重处理：清掉旧 chunks
        chunkRepository.deleteByDocumentId(doc.getId());

        for (ChunkPiece piece : pieces) {
            Chunk chunk = new Chunk();
            chunk.setDocumentId(doc.getId());
            chunk.setContent(piece.content());
            chunk.setSectionPath(piece.sectionPath());
            chunk.setStartLine(piece.startLine());
            chunk.setEndLine(piece.endLine());
            chunk.setTokenCount(estimateTokens(piece.content()));
            chunk = chunkRepository.save(chunk);

            float[] vector = embeddingProvider.embed(piece.content());
            jdbcTemplate.update(
                    "UPDATE chunks SET embedding = CAST(? AS vector), embedding_model = ? WHERE id = ?",
                    toVectorString(vector), embeddingProvider.modelName(), chunk.getId());
        }
    }

    /** 粗略 token 估计：非空白字符数 / 2（中英文混合场景近似） */
    static int estimateTokens(String content) {
        return content.replaceAll("\\s+", "").length() / 2;
    }

    /** float[] → pgvector 字面量 "[0.1,0.2,...]" */
    static String toVectorString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 智能读取：优先 UTF-8 严格解码；失败（如 Windows 记事本 ANSI/GBK 保存的文件）
     * 回退 GBK 解码——兼容常见中文编码，杜绝乱码或 FAILED。
     */
    static String readFileSmart(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ext(Path p) {
        String name = p.getFileName().toString();
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }
}
