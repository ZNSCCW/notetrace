package com.notetrace.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时自动扫描入库（FR-1 启动扫描）。入库失败不影响应用启动。
 */
@Component
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final IngestService ingestService;

    public IngestRunner(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ingestService.ingestAll();
        } catch (Exception e) {
            log.warn("启动入库失败（不影响启动）: {}", e.getMessage());
        }
    }
}
