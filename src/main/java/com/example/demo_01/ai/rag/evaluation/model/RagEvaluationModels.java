package com.example.demo_01.ai.rag.evaluation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RagEvaluationModels {

    public static final String DEFAULT_QUESTION = "请帮我总结抑菌化合物？";

    private RagEvaluationModels() {
    }

    public enum ExperimentStatus {
        QUEUED, RUNNING, COMPLETED, FAILED
    }

    public enum JudgmentLabel {
        RELEVANT, DISTRACTOR, IRRELEVANT
    }

    public enum RetrievalRoute {
        FTS, DENSE, BM25, OVERALL,
        BASELINE_FTS, BASELINE_DENSE, BASELINE_BM25, BASELINE_OVERALL,
        REVIEW_ENTITY_FTS, REVIEW_ENTITY_DENSE, REVIEW_ENTITY_BM25, REVIEW_ENTITY_OVERALL,
        GOLD_ENTITY_FTS, GOLD_ENTITY_DENSE, GOLD_ENTITY_BM25, GOLD_ENTITY_OVERALL,
        RERANK_OVERALL, RERANK_DOCUMENT_OVERALL
    }

    public enum RetrievalScope {
        FULL_CORPUS, JUDGED_DOCUMENTS
    }

    public enum ExperimentPhase {
        BUBBLE, QUESTION_REWRITE_ENTITY, BALANCED_500, RERANK_BEST_RECALL,
        ANTIMICROBIAL_PAPER_SUMMARY
    }

    public enum AntimicrobialResultStatus {
        PENDING, IRRELEVANT, SUMMARIZED, NO_CHUNKS, FAILED
    }

    public record RagEvaluationExperimentRequest(
            String question,
            RetrievalScope retrievalScope,
            ExperimentPhase phase,
            Integer corpusSize,
            Integer targetRelevantDocuments,
            Integer targetDistractorDocuments,
            Integer targetIrrelevantDocuments,
            Boolean questionRewriteEnabled,
            Boolean stronglyRelatedEntitiesEnabled,
            Boolean rerankEnabled,
            String rerankModel,
            UUID sourceJudgmentExperimentId
    ) {
        public RagEvaluationExperimentRequest(String question, RetrievalScope retrievalScope) {
            this(question, retrievalScope, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record RagEvaluationAcceptedResponse(
            UUID experimentId,
            ExperimentStatus status
    ) {
    }

    public record RagEvaluationSuiteAcceptedResponse(
            UUID suiteId,
            List<RagEvaluationAcceptedResponse> experiments
    ) {
    }

    public record RagEvaluationExperimentRecord(
            UUID experimentId,
            String userId,
            String question,
            ExperimentStatus status,
            Map<String, Object> config,
            RagEvaluationMetrics metrics,
            String reportRoot,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) {
    }

    public record RagEvaluationDocumentJudgment(
            Long id,
            UUID experimentId,
            UUID documentId,
            String documentTitle,
            JudgmentLabel llmLabel,
            JudgmentLabel overrideLabel,
            JudgmentLabel effectiveLabel,
            List<String> keyEntities,
            List<String> keyChunkIds,
            String llmReason,
            String reportPath,
            double confidence,
            String overrideNote,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RagEvaluationRetrievalHit(
            Long id,
            UUID experimentId,
            RetrievalRoute route,
            String query,
            int rank,
            UUID documentId,
            String chunkId,
            double score
    ) {
    }

    public record RagEvaluationOverrideRequest(
            JudgmentLabel label,
            List<String> keyChunkIds,
            String note
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RagEvaluationMetrics(
            List<RouteMetrics> routes,
            Instant calculatedAt,
            List<ModelUsageMetric> modelUsage,
            Long totalElapsedMs,
            AntimicrobialSummaryMetrics antimicrobialSummary
    ) {
        public RagEvaluationMetrics(List<RouteMetrics> routes, Instant calculatedAt) {
            this(routes, calculatedAt, List.of(), null, null);
        }

        public RagEvaluationMetrics(List<RouteMetrics> routes,
                                    Instant calculatedAt,
                                    List<ModelUsageMetric> modelUsage,
                                    Long totalElapsedMs) {
            this(routes, calculatedAt, modelUsage, totalElapsedMs, null);
        }

        public static RagEvaluationMetrics empty() {
            return new RagEvaluationMetrics(List.of(), null, List.of(), null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AntimicrobialSummaryMetrics(
            int totalDocuments,
            int relevantDocuments,
            int irrelevantDocuments,
            int summarizedDocuments,
            int noChunksDocuments,
            int failedDocuments
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelUsageMetric(
            String phase,
            String model,
            Long estimatedInputTokens,
            Long providerInputTokens,
            Long providerOutputTokens,
            Long providerTotalTokens,
            Long elapsedMs,
            Integer calls
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteMetrics(
            RetrievalRoute route,
            List<MetricSlice> slices
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetricSlice(
            String at,
            double relevantDocRecall,
            double precision,
            double distractorRate,
            double irrelevantRate,
            int retrievedDocCount,
            List<UUID> missedRelevantDocumentIds,
            double keyChunkRecall,
            int retrievedKeyChunkCount,
            int totalKeyChunkCount,
            List<String> missedKeyChunkIds,
            double recallAtK,
            double precisionAtK,
            double mrr,
            double ndcgAtK,
            double map
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LlmDocumentJudgmentOutput(
            JudgmentLabel label,
            List<String> keyEntities,
            List<String> keyChunkIds,
            String reason,
            String summary,
            double confidence
    ) {
    }

    public record AntimicrobialPaperResult(
            UUID experimentId,
            UUID documentId,
            String documentTitle,
            AntimicrobialResultStatus status,
            Boolean relevant,
            Integer chunkCount,
            String judgmentReason,
            String outputPath,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
