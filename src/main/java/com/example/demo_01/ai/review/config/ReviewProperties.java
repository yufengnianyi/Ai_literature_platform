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

    @Valid
    private Reasoning reasoning = new Reasoning();

    @Valid
    private Synthesis synthesis = new Synthesis();

    @Valid
    private Audit audit = new Audit();

    @Valid
    private Xlsx xlsx = new Xlsx();

    @Valid
    private Agent agent = new Agent();

    @Data
    public static class Retrieval {

        @Min(1)
        private int seedFtsMaxResults = 60;

        @Min(1)
        private int documentShortlistTop = 24;

        @Min(1)
        private int documentExpandTop = 8;

        @Min(0)
        private int documentExpandChunkLimit = 12;

        @Min(1)
        private int seedDenseMaxResults = 30;

        @Min(0)
        private double seedDenseMinScore = 0.3;

        @Min(1)
        private int seedBm25MaxResults = 40;

        @Min(1)
        private int fusedMaxResults = 50;

        @Min(1)
        private int maxCandidates = 300;

        private boolean enableQuantitativeAnchor = true;

        @Min(1)
        private int maxAnchorsPerDocument = 10;

        private boolean enableCompoundDefinitionAnchor = true;
    }

    @Data
    public static class Rerank {

        @Min(1)
        private int topK = 80;

        @Min(1)
        private int batchSize = 5;

        private String minRelevance = "MEDIUM";
    }

    @Data
    public static class Extraction {

        @Min(1)
        private int batchSize = 3;

        private boolean enableAliasInlineAnnotation = true;

        private String activitySchemaVersion = "v2-paradigm";
    }

    @Data
    public static class Report {

        @Min(1)
        private int maxTokens = 8000;
    }

    @Data
    public static class Reasoning {

        /**
         * Deep thinking is reserved for synthesis-quality review decisions.
         * Bulk screening/extraction/enrichment calls should stay in standard mode to reduce timeout risk.
         */
        private boolean coreDeepThinking = true;

        @Min(1)
        private int coreThinkingBudget = 8192;

        private boolean standardDeepThinking = false;

        @Min(0)
        private int standardThinkingBudget = 0;
    }

    @Data
    public static class Synthesis {

        private boolean enableCompoundSynthesizer = true;

        private boolean parallel = true;

        private boolean cacheEnabled = true;
    }

    @Data
    public static class Audit {

        private boolean enableCoverageAudit = true;

        @Min(0)
        private int maxResynthesisAttempts = 1;
    }

    @Data
    public static class Xlsx {

        private boolean legacyCompoundSheet = true;

        private boolean enableThreeSheet = true;
    }

    @Data
    public static class Agent {

        private boolean enabled = false;

        @Min(1)
        private int maxIterationsPerCompound = 2;

        @Min(1)
        private int maxLlmCallsPerPaper = 12;

        @Min(1)
        private int maxConcurrentPapers = 2;

        @Min(1)
        private int retrieverKPerDirective = 6;

        @Min(1)
        private int maxPaperTableIterations = 3;
    }
}
