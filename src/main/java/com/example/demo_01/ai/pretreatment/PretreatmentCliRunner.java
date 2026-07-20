package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Order(0)
@Component
public class PretreatmentCliRunner implements ApplicationRunner {

    @Resource
    private PretreatmentProperties properties;

    @Resource
    private PretreatmentService pretreatmentService;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getCli().isEnabled()) {
            return;
        }
        PretreatmentRunSummary summary = pretreatmentService.runCli();
        log.info("PreTreatment {} completed: runId={}, accepted={}, rejected={}, uncertain={}, skipped={}, removed={}, output={}",
                summary.mode(),
                summary.runId(),
                summary.acceptedDocuments(),
                summary.rejectedDocuments(),
                summary.uncertainDocuments(),
                summary.skippedDocuments(),
                summary.vectorsRemoved(),
                summary.outputDir());
        applicationContext.close();
    }
}
