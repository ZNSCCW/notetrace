package com.notetrace.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 简单重排（FR-9）：在向量相似度基础上叠加查询关键词命中加分。
 * 不引入重模型——M1 用「关键词 + 向量」混合，够用且可解释。
 */
@Component
public class Reranker {

    /** 每个命中关键词的加分（向量分范围 0~1 内的常量） */
    private static final double KEYWORD_BONUS = 0.05;

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "怎么", "什么", "为什么", "如何", "为", "在", "与", "和",
            "或", "吗", "呢", "请", "一下", "介绍", "说说", "一个", "这个", "那个",
            "what", "how", "why", "the", "a", "an", "is", "are", "in", "on", "of", "to");

    /** 重排：向量分 + 关键词命中加分，返回 topK */
    public List<SearchHit> rerank(String query, List<SearchHit> hits, int topK) {
        Set<String> keywords = extractKeywords(query);
        List<Scored> scored = new ArrayList<>(hits.size());
        for (SearchHit hit : hits) {
            int hitCount = 0;
            String content = hit.content().toLowerCase();
            for (String kw : keywords) {
                if (content.contains(kw)) {
                    hitCount++;
                }
            }
            double score = Math.min(1.0, hit.score() + hitCount * KEYWORD_BONUS);
            scored.add(new Scored(hit, score));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble((Scored s) -> s.score()).reversed()
                        .thenComparing(s -> s.hit().chunkId()))
                .limit(topK)
                .map(s -> new SearchHit(s.hit().chunkId(), s.hit().documentId(), s.hit().documentTitle(),
                        s.hit().content(), s.hit().sectionPath(), s.hit().startLine(), s.hit().endLine(),
                        s.score()))
                .toList();
    }

    /** 提取查询关键词：连续中文字符串 + 英文单词（小写），去掉停用词 */
    Set<String> extractKeywords(String query) {
        Set<String> keywords = new HashSet<>();
        String lower = query.toLowerCase();
        // 英文单词
        for (String word : lower.split("[^a-z0-9]+")) {
            if (word.length() >= 2) {
                keywords.add(word);
            }
        }
        // 中文：按非汉字切分后取 2 字以上片段
        for (String seg : lower.split("[^\\u4e00-\\u9fa5]+")) {
            if (seg.length() >= 2) {
                keywords.add(seg);
            }
        }
        keywords.removeAll(STOP_WORDS);
        return keywords;
    }

    private record Scored(SearchHit hit, double score) {
    }
}
