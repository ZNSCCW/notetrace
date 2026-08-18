package com.notetrace.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.notetrace.chunk.ChunkRepository;
import com.notetrace.document.Document;
import com.notetrace.document.DocumentRepository;
import com.notetrace.graph.GraphBuilderService;
import com.notetrace.graph.GraphService;
import com.notetrace.ingest.IngestService;
import com.notetrace.qa.QaService;

/**
 * Web 页面控制器（FR-16）：问答页 + 入库状态页（含网页端上传/删除笔记）。
 */
@Controller
public class WebController {

    private static final Logger log = LoggerFactory.getLogger(WebController.class);
    private static final Set<String> SUPPORTED_EXT = Set.of("md", "txt");

    /** 文档状态行：文档 + 其 chunk 数 */
    public record DocumentStatus(Document doc, long chunkCount) {
    }

    private final QaService qaService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final IngestService ingestService;
    private final GraphService graphService;
    private final GraphBuilderService graphBuilderService;
    private final Path ingestDir;

    public WebController(QaService qaService,
                         DocumentRepository documentRepository,
                         ChunkRepository chunkRepository,
                         IngestService ingestService,
                         GraphService graphService,
                         GraphBuilderService graphBuilderService,
                         @Value("${notetrace.ingest.dir}") String ingestDir) {
        this.qaService = qaService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.ingestService = ingestService;
        this.graphService = graphService;
        this.graphBuilderService = graphBuilderService;
        this.ingestDir = Paths.get(ingestDir);
    }

    @GetMapping("/")
    public String index() {
        return "qa";
    }

    /** 问答（FR-10/FR-11）：提交问题与 AI 选择（api=DeepSeek / local=Ollama），渲染带引用回答 */
    @PostMapping("/qa")
    public String ask(@RequestParam String question,
                      @RequestParam(name = "ai", defaultValue = "api") String aiChoice,
                      Model model) {
        QaService.QaResult result = qaService.ask(question, aiChoice);
        model.addAttribute("question", question);
        model.addAttribute("aiChoice", aiChoice);
        model.addAttribute("result", result);
        model.addAttribute("answerHtml", QaService.renderAnswerHtml(result.answer()));
        return "qa";
    }

    /** 入库状态页 */
    @GetMapping("/documents")
    public String documents(Model model) {
        List<DocumentStatus> rows = documentRepository.findAll().stream()
                .sorted((a, b) -> a.getUpdatedAt().compareTo(b.getUpdatedAt()) * -1)
                .map(d -> new DocumentStatus(d, chunkRepository.countByDocumentId(d.getId())))
                .toList();
        model.addAttribute("documents", rows);
        return "documents";
    }

    /** 网页端上传笔记：保存到 data/notes 并立即入库 */
    @PostMapping("/documents/upload")
    public String upload(@RequestParam("file") MultipartFile file, Model model) {
        if (file == null || file.isEmpty()) {
            return "redirect:/documents?msg=empty";
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        // 防路径遍历：只取文件名
        String name = Paths.get(original).getFileName().toString();
        String ext = ext(name);
        if (!SUPPORTED_EXT.contains(ext)) {
            return "redirect:/documents?msg=ext";
        }
        try {
            Path target = ingestDir.resolve(name);
            Files.createDirectories(ingestDir);
            file.transferTo(target);
            boolean processed = ingestService.ingestFile(target);
            graphBuilderService.build(); // 图谱同步更新（含新文档主题）
            log.info("网页上传: {} (processed={})", name, processed);
        } catch (IOException e) {
            log.error("上传失败: {}", e.getMessage());
            return "redirect:/documents?msg=error";
        }
        return "redirect:/documents";
    }

    /** 网页端删除笔记：删除磁盘文件 + 库记录（chunks 级联清理） */
    @PostMapping("/documents/delete")
    public String delete(@RequestParam String sourcePath) {
        // 防路径遍历：规范化后必须仍在入库目录内
        Path target = ingestDir.resolve(sourcePath).normalize();
        if (!target.startsWith(ingestDir.normalize())) {
            log.warn("拒绝越界删除: {}", sourcePath);
            return "redirect:/documents";
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.error("删除磁盘文件失败: {}", e.getMessage());
        }
        documentRepository.findBySourcePath(sourcePath).ifPresent(documentRepository::delete);
        graphBuilderService.build(); // 图谱同步重建（清除孤儿主题与笔记节点）
        log.info("网页删除: {}", sourcePath);
        return "redirect:/documents";
    }

    /** 图谱浏览页（FR-14/FR-15）：主题树 + 主题关联笔记 */
    @GetMapping("/graph")
    public String graph(@RequestParam(name = "topic", required = false) String topic, Model model) {
        model.addAttribute("topics", graphService.allTopics());
        model.addAttribute("notes", graphService.allNotes());
        model.addAttribute("selectedTopic", topic);
        if (topic != null && !topic.isBlank()) {
            model.addAttribute("topicNotes", graphService.topicNotes(topic));
        }
        return "graph";
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
