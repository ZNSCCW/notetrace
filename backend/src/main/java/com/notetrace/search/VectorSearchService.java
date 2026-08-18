package com.notetrace.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.notetrace.ai.EmbeddingProvider;

/**
 * 混合检索（FR-8 + M2 增强）：向量余弦 Top-K 召回 + 关键词（bigram）补充召回，
 * 按 chunkId 去重合并——解决纯向量对长查询精确短语（如"大括号"）召回不足的问题。
 */
@Service
public class VectorSearchService {

    private static final int KEYWORD_K = 8;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProvider embeddingProvider;
    private final Reranker reranker;

    public VectorSearchService(JdbcTemplate jdbcTemplate, EmbeddingProvider embeddingProvider, Reranker reranker) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
        this.reranker = reranker;
    }

    /** 混合检索：向量 Top-K ∪ 关键词 Top-K（按 chunkId 去重，向量序优先） */
    public List<SearchHit> search(String query, int topK) {
        Map<Long, SearchHit> merged = new LinkedHashMap<>();
        for (SearchHit hit : vectorSearch(query, topK)) {
            merged.put(hit.chunkId(), hit);
        }
        for (SearchHit hit : keywordSearch(query, KEYWORD_K)) {
            merged.putIfAbsent(hit.chunkId(), hit);
        }
        return new ArrayList<>(merged.values());
    }

    /** 纯向量检索：pgvector 余弦距离（<=>）Top-K */
    private List<SearchHit> vectorSearch(String query, int topK) {
        float[] vector = embeddingProvider.embed(query);
        String vectorLiteral = toVectorString(vector);

        return jdbcTemplate.query(
                """
                SELECT c.id, c.document_id, d.title, c.content, c.section_path, c.start_line, c.end_line,
                       (c.embedding <=> CAST(? AS vector)) AS distance
                FROM chunks c
                JOIN documents d ON d.id = c.document_id
                WHERE c.embedding IS NOT NULL
                ORDER BY distance
                LIMIT ?
                """,
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("section_path"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        1.0 - rs.getDouble("distance")),
                vectorLiteral, topK);
    }

    /** 关键词补充召回：bigram 关键词 LIKE 匹配，按命中关键词数排序（最相关优先） */
    private List<SearchHit> keywordSearch(String query, int limit) {
        Set<String> keywords = reranker.extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        StringBuilder select = new StringBuilder();
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (String kw : keywords) {
            String pattern = "%" + escapeLike(kw) + "%";
            if (select.length() > 0) {
                select.append(" + ");
                where.append(" OR ");
            }
            select.append("(c.content ILIKE ?)::int");
            args.add(pattern);
            where.append("c.content ILIKE ?");
            args.add(pattern);
        }
        String sql = """
                SELECT c.id, c.document_id, d.title, c.content, c.section_path, c.start_line, c.end_line,
                       (%s) AS match_count, 0.0 AS distance
                FROM chunks c
                JOIN documents d ON d.id = c.document_id
                WHERE %s
                ORDER BY match_count DESC, c.id
                LIMIT %d
                """.formatted(select, where, limit);

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("section_path"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        0.5), // 关键词命中基础分，由 Reranker 再加权
                args.toArray());
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

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
}
