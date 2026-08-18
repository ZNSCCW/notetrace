package com.notetrace.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RerankerTest {

    private final Reranker reranker = new Reranker();

    private SearchHit hit(long id, String content, double score) {
        return new SearchHit(id, 1L, "测试文档", content, "测试/章节", 1, 2, score);
    }

    @Test
    void extractsChineseAndEnglishKeywords() {
        // 英文单词保留；中文按 2 字窗口提取，含停用字的窗口丢弃
        assertThat(reranker.extractKeywords("Spring Security 为什么 401")).contains(
                "spring", "security", "401");
        assertThat(reranker.extractKeywords("Spring Security 为什么 401")).doesNotContain("为什么");
        assertThat(reranker.extractKeywords("介绍一下 HashMap 的实现")).contains("hashmap", "实现");
        assertThat(reranker.extractKeywords("介绍一下 HashMap 的实现")).doesNotContain("介绍", "一下", "的实", "为什么");
    }

    @Test
    void longChineseQueryProducesUsefulBigrams() {
        // 长查询不再整句作关键词，而是可命中的 2 字窗口（修复大括号 MISS 的关键）
        assertThat(reranker.extractKeywords("新手阶段为什么建议永远写大括号"))
                .contains("大括", "括号", "新手", "阶段", "建议", "永远");
    }

    @Test
    void keywordHitBoostsScore() {
        SearchHit noKeyword = hit(1, "完全是无关内容", 0.9);
        SearchHit withKeyword = hit(2, "hashmap 的扩容机制说明", 0.85);
        List<SearchHit> result = reranker.rerank("HashMap 扩容", List.of(noKeyword, withKeyword), 2);
        // 命中关键词的 chunk 应排到前面
        assertThat(result.get(0).chunkId()).isEqualTo(2L);
        assertThat(result.get(0).score()).isGreaterThan(noKeyword.score());
    }

    @Test
    void topKIsRespected() {
        List<SearchHit> hits = List.of(
                hit(1, "内容一", 0.9),
                hit(2, "内容二", 0.8),
                hit(3, "内容三", 0.7));
        List<SearchHit> result = reranker.rerank("内容", hits, 2);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunkId()).isEqualTo(1L);
        assertThat(result.get(1).chunkId()).isEqualTo(2L);
    }

    @Test
    void emptyQueryKeepsOriginalOrder() {
        List<SearchHit> hits = List.of(hit(1, "a", 0.9), hit(2, "b", 0.8));
        List<SearchHit> result = reranker.rerank("", hits, 2);
        assertThat(result.get(0).chunkId()).isEqualTo(1L);
        assertThat(result.get(1).chunkId()).isEqualTo(2L);
    }
}
