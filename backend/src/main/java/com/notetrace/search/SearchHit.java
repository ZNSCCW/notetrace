package com.notetrace.search;

/**
 * 检索命中的 chunk（含溯源定位信息与分数）。
 * 分数 = 1 - 余弦距离，越接近 1 越相关。
 */
public record SearchHit(
        Long chunkId,
        Long documentId,
        String documentTitle,
        String content,
        String sectionPath,
        int startLine,
        int endLine,
        double score) {
}
