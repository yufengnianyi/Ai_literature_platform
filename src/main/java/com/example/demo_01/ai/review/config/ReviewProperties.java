package com.example.demo_01.ai.review.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.review")
public class ReviewProperties {

    private boolean enabled = true;

    @Min(1)
    private int asyncThreads = 2;

    @Valid
    private Retrieval retrieval = new Retrieval();

    @Valid
    private Report report = new Report();

    @Valid
    private Reasoning reasoning = new Reasoning();

    @Valid
    private Xlsx xlsx = new Xlsx();

    @Data
    public static class Retrieval {

        @Min(1)
        private int seedFtsMaxResults = 60;

        @Min(1)
        private int seedDenseMaxResults = 30;

        @Min(0)
        private double seedDenseMinScore = 0.3;

        @Min(0)
        private double autoSelectMinSeedScore = 0.60;

        @Min(1)
        private int seedBm25MaxResults = 40;

        @Min(1)
        private int fusedMaxResults = 50;

        @Min(1)
        private int maxCandidates = 300;

    }

    @Data
    public static class Report {

        @Min(1)
        private int maxTokens = 8000;

        @Min(1)
        private int maxPaperTableIterations = 3;
    }

    @Data
    public static class Reasoning {

        private boolean coreDeepThinking = true;

        @Min(1)
        private int coreThinkingBudget = 8192;

        private boolean standardDeepThinking = false;

        @Min(0)
        private int standardThinkingBudget = 0;
    }

    @Data
    public static class Xlsx {

        private boolean enableThreeSheet = true;
    }

}
