package com.notetrace.qa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.notetrace.ai.ChatProvider;
import com.notetrace.search.Reranker;
import com.notetrace.search.SearchHit;
import com.notetrace.search.VectorSearchService;

/**
 * 防伪溯源问答（FR-10/FR-12）。
 *
 * <p>核心机制（防 LLM 编造来源）：
 * <ol>
 *   <li>检索 Top-K 后把候选 chunk 以编号 [1]~[K] 注入 prompt，强制 LLM 只能引用候选编号；</li>
 *   <li>程序侧解析回答中的 [n] 引用并校验 n ∈ 候选集，越界/非法的引用一律剔除；</li>
 *   <li>引用携带 (文档标题, 章节路径, 行号范围) 定位信息，供 UI 溯源跳转（FR-11）。</li>
 * </ol>
 */
@Service
public class QaService {

    private static final int RETRIEVE_K = 8;
    private static final int RERANK_K = 5;
    private static final Pattern REF_PATTERN = Pattern.compile("\\[(\\d+)]");

    private static final String SYSTEM_PROMPT = """
            你是 NoteTrace（知溯）知识库助手。
            回答必须基于用户提供的编号资料 [1]~[K]，每个结论后用对应编号标注来源，如"线程池默认拒绝策略是 AbortPolicy[2]"。
            只能引用资料中真实存在的内容，禁止编造来源。
            引用编号请写成单个 [n] 形式，不要使用 [1,2] 或 [1-2] 范围写法。
            如果资料不足以回答问题，直接回答"知识库中没有相关内容"，不要编造。
            """;

    private final VectorSearchService vectorSearchService;
    private final Reranker reranker;
    private final ChatProvider chatProvider;

    public QaService(VectorSearchService vectorSearchService, Reranker reranker, ChatProvider chatProvider) {
        this.vectorSearchService = vectorSearchService;
        this.reranker = reranker;
        this.chatProvider = chatProvider;
    }

    /** 溯源引用（UI 跳转定位用） */
    public record Reference(Long chunkId, String documentTitle, String sectionPath,
                            int startLine, int endLine, String excerpt, String fullContent) {
    }

    /** 问答结果：回答正文 + 校验后的引用列表 */
    public record QaResult(String answer, List<Reference> references) {
    }

    public QaResult ask(String question) {
        if (question == null || question.isBlank()) {
            return new QaResult("请输入问题。", List.of());
        }

        List<SearchHit> hits = vectorSearchService.search(question, RETRIEVE_K);
        if (hits.isEmpty()) {
            // FR-12：无结果降级，不硬编答案
            return new QaResult("知识库中没有相关内容，请先确认笔记已导入（data/notes）。", List.of());
        }

        List<SearchHit> top = reranker.rerank(question, hits, RERANK_K);
        String rawAnswer = chatProvider.chat(SYSTEM_PROMPT, buildUserPrompt(top, question));

        return new QaResult(rawAnswer, extractValidReferences(rawAnswer, top));
    }

    /** 构造编号候选 prompt（防伪核心） */
    static String buildUserPrompt(List<SearchHit> top, String question) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            SearchHit h = top.get(i);
            sb.append('[').append(i + 1).append("] (文档: ").append(h.documentTitle())
                    .append(" / 章节: ").append(nonEmpty(h.sectionPath())).append(")\n")
                    .append(h.content()).append('\n');
        }
        sb.append("问题: ").append(question);
        return sb.toString();
    }

    /** 解析回答中的 [n] 引用，校验 n 在候选集内，返回去重后的合法引用 */
    static List<Reference> extractValidReferences(String answer, List<SearchHit> top) {
        Map<Integer, Reference> refs = new LinkedHashMap<>();
        Matcher m = REF_PATTERN.matcher(answer == null ? "" : answer);
        while (m.find()) {
            String num = m.group(1);
            // 防御：超长数字串（LLM 异常输出）直接跳过，避免 Integer.parseInt 抛异常导致 500
            if (num.length() > 6) {
                continue;
            }
            int idx = Integer.parseInt(num);
            if (idx >= 1 && idx <= top.size()) {
                SearchHit h = top.get(idx - 1);
                refs.putIfAbsent(idx, new Reference(
                        h.chunkId(), h.documentTitle(), h.sectionPath(),
                        h.startLine(), h.endLine(), excerpt(h.content()), h.content()));
            }
        }
        return new ArrayList<>(refs.values());
    }

    private static String excerpt(String content) {
        String oneLine = content.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() ? "（无章节）" : s;
    }
}
