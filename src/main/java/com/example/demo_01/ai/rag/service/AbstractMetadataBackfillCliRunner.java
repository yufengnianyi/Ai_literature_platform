package com.example.demo_01.ai.rag.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Order(0)
@Component
@ConditionalOnProperty(prefix = "app.ai.rag.abstract-backfill", name = "enabled", havingValue = "true")
public class AbstractMetadataBackfillCliRunner implements ApplicationRunner {

    @Resource
    private AbstractMetadataBackfillService abstractMetadataBackfillService;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    @Value("${app.ai.rag.abstract-backfill.max-documents:0}")
    private int maxDocuments;

    @Override
    public void run(ApplicationArguments args) {
        AbstractMetadataBackfillService.AbstractBackfillSummary summary =
                abstractMetadataBackfillService.backfill(maxDocuments);
        log.info("Abstract metadata backfill completed: scanned={}, alreadyPresent={}, updated={}, missingArtifact={}, noAbstract={}, failed={}",
                summary.scanned(), summary.alreadyPresent(), summary.updated(), summary.missingArtifact(),
                summary.noAbstract(), summary.failed());
        applicationContext.close();
    }
}
