package com.notetrace.graph;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 图谱查询（FR-15 结构化层）：
 * 主题树浏览 + 「某主题及其子主题关联哪些笔记」（递归 CTE）。
 */
@Service
public class GraphService {

    private final JdbcTemplate jdbcTemplate;

    public GraphService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 主题节点（name 为完整路径，UI 按 / 层级缩进渲染） */
    public record Topic(String name) {
    }

    /** 关联笔记 */
    public record Note(Long id, String sourcePath) {
    }

    /** 全部主题（按路径排序） */
    public List<Topic> allTopics() {
        return jdbcTemplate.query(
                "SELECT name FROM graph_nodes WHERE node_type = 'TOPIC' ORDER BY name",
                (rs, i) -> new Topic(rs.getString("name")));
    }

    /** 全部笔记节点 */
    public List<Note> allNotes() {
        return jdbcTemplate.query(
                "SELECT n.id, n.name FROM graph_nodes n WHERE n.node_type = 'NOTE' ORDER BY n.name",
                (rs, i) -> new Note(rs.getLong("id"), rs.getString("name")));
    }

    /** 全部概念节点（M3.1） */
    public List<Topic> allConcepts() {
        return jdbcTemplate.query(
                "SELECT name FROM graph_nodes WHERE node_type = 'CONCEPT' ORDER BY name",
                (rs, i) -> new Topic(rs.getString("name")));
    }

    /** 概念关联的笔记（APPEARS_IN） */
    public List<Note> conceptNotes(String conceptName) {
        if (conceptName == null || conceptName.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT n.id, n.name FROM graph_nodes n
                JOIN graph_edges e ON e.to_node_id = n.id
                WHERE e.from_node_id = (SELECT id FROM graph_nodes WHERE name = ? AND node_type = 'CONCEPT')
                  AND e.relation_type = 'APPEARS_IN' AND n.node_type = 'NOTE'
                ORDER BY n.name
                """,
                (rs, i) -> new Note(rs.getLong("id"), rs.getString("name")),
                conceptName);
    }

    /** 主题（含子主题）下的笔记涉及的所有概念 */
    public List<Topic> topicConcepts(String topicPath) {
        if (topicPath == null || topicPath.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                WITH RECURSIVE sub AS (
                    SELECT id FROM graph_nodes WHERE name = ? AND node_type = 'TOPIC'
                    UNION
                    SELECT e.to_node_id FROM graph_edges e
                    JOIN sub s ON e.from_node_id = s.id
                    WHERE e.relation_type = 'PARENT'
                )
                SELECT DISTINCT c.name FROM graph_nodes c
                JOIN graph_edges ce ON ce.from_node_id = c.id AND ce.relation_type = 'APPEARS_IN'
                JOIN graph_edges te ON te.to_node_id = ce.to_node_id
                WHERE te.from_node_id IN (SELECT id FROM sub) AND te.relation_type = 'CONTAINS'
                ORDER BY c.name
                """,
                (rs, i) -> new Topic(rs.getString("name")),
                topicPath);
    }

    /** 图谱问答（FR-15）：两个概念共同出现的笔记（"X 与 Y 的关系"） */
    public List<Note> relationQuery(String conceptX, String conceptY) {
        if (conceptX == null || conceptX.isBlank() || conceptY == null || conceptY.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT n.id, n.name FROM graph_nodes n
                WHERE n.id IN (SELECT e1.to_node_id FROM graph_edges e1
                               WHERE e1.from_node_id = (SELECT id FROM graph_nodes WHERE name = ? AND node_type = 'CONCEPT')
                                 AND e1.relation_type = 'APPEARS_IN')
                  AND n.id IN (SELECT e2.to_node_id FROM graph_edges e2
                               WHERE e2.from_node_id = (SELECT id FROM graph_nodes WHERE name = ? AND node_type = 'CONCEPT')
                                 AND e2.relation_type = 'APPEARS_IN')
                ORDER BY n.name
                """,
                (rs, i) -> new Note(rs.getLong("id"), rs.getString("name")),
                conceptX, conceptY);
    }

    /** 主题（含子主题）下关联的所有笔记 */
    public List<Note> topicNotes(String topicPath) {
        if (topicPath == null || topicPath.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                WITH RECURSIVE sub AS (
                    SELECT id FROM graph_nodes WHERE name = ? AND node_type = 'TOPIC'
                    UNION
                    SELECT e.to_node_id FROM graph_edges e
                    JOIN sub s ON e.from_node_id = s.id
                    WHERE e.relation_type = 'PARENT'
                )
                SELECT DISTINCT n.id, n.name
                FROM graph_nodes n
                JOIN graph_edges e ON e.to_node_id = n.id
                WHERE e.from_node_id IN (SELECT id FROM sub)
                  AND e.relation_type = 'CONTAINS'
                  AND n.node_type = 'NOTE'
                ORDER BY n.name
                """,
                (rs, i) -> new Note(rs.getLong("id"), rs.getString("name")),
                topicPath);
    }
}
