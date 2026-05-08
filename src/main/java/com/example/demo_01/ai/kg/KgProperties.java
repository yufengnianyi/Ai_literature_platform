package com.example.demo_01.ai.kg;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.kg")
public class KgProperties {

    private boolean enabled = false;

    @Min(1)
    private int asyncThreads = 2;

    private String entityModel = "qwen3-max-2026-01-23";

    private String relationModel = "qwen3-max-2026-01-23";

    private String schemaVersion = "v1";

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double entityConfidenceThreshold = 0.6;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double relationConfidenceThreshold = 0.6;

    @Min(1)
    private int graphMaxResults = 4;

    @Valid
    private GraphBuilder graphBuilder = new GraphBuilder();

    @Data
    public static class GraphBuilder {

        private boolean enabled = false;

        private String endpoint;

        private String model = "qwen3-max-2026-01-23";

        @Min(1)
        private int batchSize = 1;

        @Min(0)
        private int maxRetries = 2;

        @Min(1)
        private int connectTimeoutMs = 5_000;

        @Min(1)
        private int readTimeoutMs = 30_000;
    }
}
