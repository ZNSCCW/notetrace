package com.notetrace.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 图谱构建（FR-13）：结构层（section_path 主题-笔记图）+ M3.1 概念层（LLM 抽取技术概念）。
 * 主题 = section_path 的每一级（如 Java 并发编程/线程池 → 两个 TOPIC 节点 + PARENT 边），
 * 每篇文档一个 NOTE 节点，最深层主题 CONTAINS 笔记；概念节点 CONCEPT + APPEARS_IN 边（概念→笔记）。
 * 全量重建（幂等）。结构构建在事务内，概念抽取（LLM 网络调用）在事务外。
 */
@Service
public class GraphBuilderService {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilderService.class);

    private final JdbcTemplate jdbcTemplate;
    private final EntityExtractor entityExtractor;

    public GraphBuilderService(JdbcTemplate jdbcTemplate, EntityExtractor entityExtractor) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityExtractor = entityExtractor;
    }

    /** 全量重建图谱：结构层（事务内）+ 概念层（事务外 LLM 抽取） */
    public int build() {
        int structureEdges = buildStructure();
        int conceptEdges = buildConcepts();
        log.info("图谱构建完成: 结构边 {}, 概念边 {}", structureEdges, conceptEdges);
        return structureEdges + conceptEdges;
    }

    @Transactional
    int buildStructure() {
        jdbcTemplate.update("DELETE FROM graph_edges");
        jdbcTemplate.update("DELETE FROM graph_nodes");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.section_path, d.id AS doc_id, d.source_path " +
                "FROM chunks c JOIN documents d ON d.id = c.document_id " +
                "WHERE c.section_path IS NOT NULL AND c.section_path <> ''");

        Map<String, Long> topicIds = new HashMap<>();
        Map<Long, Long> noteIds = new HashMap<>();
        int edgeCount = 0;

        for (Map<String, Object> row : rows) {
            long docId = ((Number) row.get("doc_id")).longValue();
            String sectionPath = (String) row.get("section_path");
            String sourcePath = (String) row.get("source_path");

            Long noteId = noteIds.computeIfAbsent(docId, k -> createNode(
                    sourcePath, "NOTE", docId));
            if (noteId == null) {
                continue;
            }

            String[] segments = sectionPath.split("/");
            StringBuilder fullPath = new StringBuilder();
            Long parentId = null;
            for (String seg : segments) {
                if (seg.isBlank()) {
                    continue;
                }
                if (fullPath.length() > 0) {
                    fullPath.append('/');
                }
                fullPath.append(seg.strip());
                String path = fullPath.toString();
                Long topicId = topicIds.computeIfAbsent(path, p -> createNode(p, "TOPIC", null));
                if (topicId == null) {
                    continue;
                }
                if (parentId != null && createEdge(parentId, topicId, "PARENT")) {
                    edgeCount++;
                }
                parentId = topicId;
            }
            if (parentId != null && createEdge(parentId, noteId, "CONTAINS")) {
                edgeCount++;
            }
        }

        int topicCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM graph_nodes WHERE node_type = 'TOPIC'", Integer.class);
        int noteCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM graph_nodes WHERE node_type = 'NOTE'", Integer.class);
        log.info("图谱结构构建: {} 主题, {} 笔记, {} 边", topicCount, noteCount, edgeCount);
        return edgeCount;
    }

    /** 概念层（M3.1）：LLM 按文档抽取技术概念 → CONCEPT 节点 + APPEARS_IN 边（事务外网络调用） */
    int buildConcepts() {
        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT d.id, COALESCE(string_agg(c.content, E'\\n'), '') AS content " +
                "FROM documents d LEFT JOIN chunks c ON c.document_id = d.id " +
                "GROUP BY d.id");
        int edgeCount = 0;
        for (Map<String, Object> doc : docs) {
            long docId = ((Number) doc.get("id")).longValue();
            String content = (String) doc.get("content");
            List<String> concepts = entityExtractor.extract(content);
            if (concepts.isEmpty()) {
                continue;
            }
            Long noteId = findNoteId(docId);
            if (noteId == null) {
                continue;
            }
            for (String concept : concepts) {
                Long conceptId = createNode(concept, "CONCEPT", null);
                if (conceptId != null && createEdge(conceptId, noteId, "APPEARS_IN")) {
                    edgeCount++;
                }
            }
        }
        if (edgeCount > 0) {
            log.info("概念抽取: {} 条概念关联", edgeCount);
        }
        return edgeCount;
    }

    private Long findNoteId(long docId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM graph_nodes WHERE node_type = 'NOTE' AND doc_id = ?", Long.class, docId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long createNode(String name, String type, Long docId) {
        jdbcTemplate.update(
                "INSERT INTO graph_nodes (name, node_type, doc_id) VALUES (?, ?, ?) " +
                "ON CONFLICT (name, node_type, COALESCE(doc_id, 0)) DO NOTHING",
                name, type, docId);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM graph_nodes WHERE name = ? AND node_type = ? AND COALESCE(doc_id, 0) = COALESCE(?, 0)",
                Long.class, name, type, docId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private boolean createEdge(Long fromId, Long toId, String relationType) {
        return jdbcTemplate.update(
                "INSERT INTO graph_edges (from_node_id, to_node_id, relation_type) VALUES (?, ?, ?) " +
                "ON CONFLICT (from_node_id, to_node_id, relation_type) DO NOTHING",
                fromId, toId, relationType) > 0;
    }
}
