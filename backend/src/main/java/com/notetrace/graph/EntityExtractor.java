package com.notetrace.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notetrace.ai.ChatRouter;

/**
 * 概念抽取（M3.1，FR-13 LLM 层）：用 DeepSeek 按文档抽取核心技术概念（技术名词/框架/术语）。
 * 每文档一次 API 调用；失败或响应异常时降级为空列表（不阻塞图谱构建）。
 */
@Service
public class EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);
    private static final int MAX_INPUT_CHARS = 4000;
    private static final int MAX_CONCEPTS = 20;
    private static final Pattern JSON_ARRAY = Pattern.compile("\\[[^\\]]*]");

    private static final String SYSTEM = "你是技术概念提取器。只输出 JSON 数组，不要任何其他文字。";
    private static final String PROMPT = """
            从下面的技术笔记中提取核心技术概念（技术名词/框架/术语，如"线程池"、"ReentrantLock"、"HashMap"、"synchronized"）。
            规则：
            1. 只输出 JSON 字符串数组，如 ["线程池","ReentrantLock"]；最多 %d 个；按重要程度排序；
            2. 若下方"已存在的概念"中有与笔记内容匹配的，必须优先复用其原样叫法（保持一致性）；
            3. 不要包含笔记标题本身。

            已存在的概念：%s

            笔记内容：
            %s
            """;

    private final ChatRouter chatRouter;
    private final ObjectMapper objectMapper;

    public EntityExtractor(ChatRouter chatRouter, ObjectMapper objectMapper) {
        this.chatRouter = chatRouter;
        this.objectMapper = objectMapper;
    }

    /** 抽取文档概念；existingConcepts 供 few-shot 复用（跨文档一致性，M4 优化） */
    public List<String> extract(String documentContent, List<String> existingConcepts) {
        if (documentContent == null || documentContent.isBlank()) {
            return List.of();
        }
        try {
            String truncated = documentContent.length() > MAX_INPUT_CHARS
                    ? documentContent.substring(0, MAX_INPUT_CHARS) : documentContent;
            String existing = (existingConcepts == null || existingConcepts.isEmpty())
                    ? "（无）" : String.join("、", existingConcepts);
            String raw = chatRouter.resolve("api").chat(SYSTEM, PROMPT.formatted(MAX_CONCEPTS, existing, truncated));
            return parse(raw);
        } catch (Exception e) {
            log.warn("概念抽取失败（降级为空）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 LLM 返回的 JSON 数组（容忍 markdown 代码块/前后杂文） */
    List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            // 先尝试整体 JSON
            JsonNode arr = objectMapper.readTree(raw.trim());
            return readStrings(arr);
        } catch (Exception ignored) {
            // 容错：正则取第一个 [...] 再解析
            Matcher m = JSON_ARRAY.matcher(raw);
            if (m.find()) {
                try {
                    return readStrings(objectMapper.readTree(m.group()));
                } catch (Exception e) {
                    log.warn("概念 JSON 解析失败: {}", e.getMessage());
                }
            }
        }
        return new ArrayList<>();
    }

    private List<String> readStrings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText().strip());
                    if (result.size() >= MAX_CONCEPTS) {
                        break;
                    }
                }
            }
        }
        return result;
    }
}
