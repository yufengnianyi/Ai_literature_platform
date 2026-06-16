package com.example.demo_01.ai.evidence.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.evidence")
public class EvidenceProperties {

    private boolean enabled = true;

    @Min(1)
    private int asyncThreads = 2;

    @Min(1)
    private int chunkBatchSize = 12;

    @Min(1)
    private int maxAttempts = 3;

    @Min(1)
    private int maxSinglePassChunks = 40;

    @Min(1)
    private int maxSinglePassChars = 120_000;

    private String outputRoot = "Evidence";
}
