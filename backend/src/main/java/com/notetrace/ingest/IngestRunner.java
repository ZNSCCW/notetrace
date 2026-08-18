package com.notetrace.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.notetrace.graph.GraphBuilderService;

/**
 * 应用启动时自动扫描入库（FR-1 启动扫描）并重建知识图谱。失败不影响应用启动。
 * @Order(1)：先于评估运行器执行，保证 --eval 时库中已有数据。
 */
@Component
@Order(1)
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final IngestService ingestService;
    private final GraphBuilderService graphBuilderService;

    public IngestRunner(IngestService ingestService, GraphBuilderService graphBuilderService) {
        this.ingestService = ingestService;
        this.graphBuilderService = graphBuilderService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ingestService.ingestAll();
        } catch (Exception e) {
            log.warn("启动入库失败（不影响启动）: {}", e.getMessage());
        }
        // 图谱构建独立 try：入库部分成功后图仍重建（避免异常互相拖累）
        try {
            graphBuilderService.build();
        } catch (Exception e) {
            log.warn("图谱构建失败（不影响启动）: {}", e.getMessage());
        }
    }
}
