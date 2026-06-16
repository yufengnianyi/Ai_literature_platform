package com.example.demo_01.ai.rag.evaluation.config;

import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RetrievalScope;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.rag.evaluation")
public class RagEvaluationProperties {

    private String reportRoot = "data/rag-evaluation";

    @Min(1)
    private int chunkBatchSize = 12;

    @Min(1)
    private int judgmentMaxAttempts = 3;

    @Min(1)
    private int ftsMaxResults = 100;

    @Min(1)
    private int denseMaxResults = 300;

    @Min(0)
    private double denseMinScore = 0.0;

    @Min(1)
    private int bm25MaxResults = 100;

    @Min(1)
    private int priorityChunksPerFtsDocument = 2;

    @Min(1)
    private int rrfK = 60;

    @Min(0)
    private int maxDocuments = 0;

    private boolean entityEnhancedEnabled = true;

    private boolean judgmentOnly = false;

    @Min(1)
    private int antimicrobialSummaryMaxAttempts = 3;

    private UUID antimicrobialSummarySourceExperimentId =
            UUID.fromString("b6aec474-a84c-48f6-8717-531042f09143");

    @Min(1)
    private int maxEntityTerms = 20;

    private RetrievalScope retrievalScope = RetrievalScope.FULL_CORPUS;

    private UUID sourceJudgments100ExperimentId;

    private UUID sourceJudgments1000ExperimentId;

    private boolean reviewEntityBestRecallEnabled = true;

    private boolean reviewEntityHighPrecisionEnabled = false;

    private String reviewEntityHighPrecisionQueryMarker = "antibacterial";

    private List<String> reviewEntityBestRecallTerms = List.of(
            "antibacterial compounds",
            "Gram-positive bacteria",
            "Gram-negative bacteria",
            "resistant strains",
            "mechanism of action",
            "antibacterial activity",
            "chemical classification",
            "resistance development",
            "therapeutic application"
    );

    @Min(0)
    private int documentRerankMaxDocuments = 0;

    @Min(1)
    private int documentRerankMaxChunksPerDocument = 5;

    @Min(1000)
    private int documentRerankMaxDocumentChars = 7800;

    @Min(0)
    private double documentRerankMinScore = 0.5;

    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {

        private boolean enabled = false;

        private String endpoint = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

        private String apiKey;

        private String model = "qwen3-vl-rerank";

        @Min(1)
        private int maxCandidates = 100;
    }
}
