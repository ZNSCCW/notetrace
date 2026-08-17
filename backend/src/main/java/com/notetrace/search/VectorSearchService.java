package com.notetrace.search;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.notetrace.ai.EmbeddingProvider;

/**
 * 向量检索（FR-8）：查询文本嵌入后，用 pgvector 余弦距离（<=>）取 Top-K。
 * 只命中带向量的 chunk（embedding IS NOT NULL）。
 */
@Service
public class VectorSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProvider embeddingProvider;

    public VectorSearchService(JdbcTemplate jdbcTemplate, EmbeddingProvider embeddingProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
    }

    public List<SearchHit> search(String query, int topK) {
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
