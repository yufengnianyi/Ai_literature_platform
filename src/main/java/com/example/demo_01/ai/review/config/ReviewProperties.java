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
    private Rerank rerank = new Rerank();

    @Valid
    private Extraction extraction = new Extraction();

    @Valid
    private Report report = new Report();

    @Data
    public static class Retrieval {

        @Min(1)
        private int docFtsMaxResults = 50;

        @Min(1)
        private int docExpandTop = 20;

        @Min(1)
        private int denseMaxResults = 20;

        @Min(0)
        private double denseMinScore = 0.3;

        @Min(1)
        private int bm25MaxResults = 30;

        @Min(1)
        private int fusedMaxResults = 50;

        @Min(1)
        private int maxCandidates = 800;
    }

    @Data
    public static class Rerank {

        @Min(1)
        private int topK = 60;

        @Min(1)
        private int batchSize = 5;

        private String minRelevance = "MEDIUM";
    }

    @Data
    public static class Extraction {

        @Min(1)
        private int batchSize = 3;
    }

    @Data
    public static class Report {

        @Min(1)
        private int maxTokens = 8000;
    }
}
