package com.notetrace.eval;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.notetrace.search.SearchHit;
import com.notetrace.search.VectorSearchService;

/**
 * 检索评估运行器（PRD 13.2）：java -jar ... --eval 触发。
 * 读取 eval-questions.csv（问题/期望文档/期望章节），对每条问题跑 Top-5 检索，
 * 检查期望文档是否命中，输出 Top-5 文档命中率——切分/重排策略调整后的回归指标。
 * @Order(2)：在 IngestRunner 之后执行，避免对空库产出假评估。
 */
@Component
@Order(2)
public class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final int TOP_K = 5;

    private final VectorSearchService vectorSearchService;

    public EvalRunner(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("eval")) {
            return;
        }
        List<EvalItem> items = loadItems();
        if (items.isEmpty()) {
            log.warn("评估集为空（eval-questions.csv），跳过评估");
            return;
        }
        int hit = 0;
        for (EvalItem item : items) {
            List<SearchHit> top = vectorSearchService.search(item.question(), TOP_K);
            boolean docHit = top.stream().anyMatch(h -> h.documentTitle().equals(item.expectedDoc()));
            boolean sectionHit = top.stream().anyMatch(h ->
                    h.documentTitle().equals(item.expectedDoc())
                            && h.sectionPath() != null
                            && h.sectionPath().contains(item.expectedSection()));
            if (docHit) {
                hit++;
            }
            log.info("[{}] {}  -> 文档命中={} 章节命中={} top1={}",
                    docHit ? "OK" : "MISS", item.question(), docHit, sectionHit,
                    top.isEmpty() ? "-" : top.get(0).documentTitle());
        }
        int percent = (int) Math.round(hit * 100.0 / items.size());
        log.info("===== 评估结果: Top-{} 文档命中率 {}/{} ({}%) =====",
                TOP_K, hit, items.size(), percent);
    }

    private List<EvalItem> loadItems() {
        List<EvalItem> items = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/eval-questions.csv"), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] parts = line.split(",", 3);
                if (parts.length == 3) {
                    items.add(new EvalItem(parts[0], parts[1], parts[2]));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 eval-questions.csv 失败", e);
        }
        return items;
    }

    private record EvalItem(String question, String expectedDoc, String expectedSection) {
    }
}
