package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.BatchStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ClassificationStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 4 runs one question against one document set. Keeping it separate from the
 * classification batch is what allows a single classification snapshot to back many extraction
 * experiments (table on/off, verifier on/off) without re-paying for classification.
 */
public final class QuestionExtractionModels {

    private QuestionExtractionModels() {
    }

    public enum ExtractionSourceType {
        /** Documents whose classification verdict for this question matches the filter. */
        CLASSIFICATION_RUN,
        /** Every completed canonical document of a RAG evaluation experiment, unclassified. */
        EXPERIMENT,
        /** An explicit document list, unclassified. */
        DOCUMENT_IDS,
        /** Every document in a frozen document cohort, unclassified. */
        COHORT
    }

    public enum ExtractionDocumentStatus {
        PENDING, RUNNING, COMPLETED, NO_EVIDENCE, FAILED, NO_CHUNKS
    }

    /**
     * @param overrides partial {@code app.ai.evidence} tree merged over the global configuration
     *                  for this run only, e.g. {@code {"table":{"enabled":true}}}. Included in
     *                  the run's config hash so experiments never reuse each other's results.
     */
    public record ExtractionRunRequest(
            String questionId,
            String label,
            ExtractionSourceType sourceType,
            UUID classificationBatchId,
            UUID sourceExperimentId,
            UUID cohortId,
            List<UUID> documentIds,
            List<ClassificationStatus> includeStatuses,
            JsonNode overrides,
            boolean force
    ) {
        public ExtractionRunRequest(String questionId,
                                    String label,
                                    ExtractionSourceType sourceType,
                                    UUID classificationBatchId,
                                    UUID sourceExperimentId,
                                    List<UUID> documentIds,
                                    List<ClassificationStatus> includeStatuses,
                                    JsonNode overrides,
                                    boolean force) {
            this(questionId, label, sourceType, classificationBatchId, sourceExperimentId,
                    null, documentIds, includeStatuses, overrides, force);
        }
    }

    public record ExtractionRunAccepted(
            UUID runId,
            String questionId,
            BatchStatus status,
            int totalDocuments,
            boolean reused
    ) {
    }

    public record ExtractionRunRecord(
            UUID runId,
            String questionId,
            String label,
            ExtractionSourceType sourceType,
            UUID classificationBatchId,
            UUID sourceExperimentId,
            UUID cohortId,
            List<ClassificationStatus> includeStatuses,
            String profileVersion,
            String inputHash,
            String configHash,
            String configSnapshotJson,
            String modelName,
            boolean force,
            BatchStatus status,
            int totalDocuments,
            int processedDocuments,
            int completedDocuments,
            int noEvidenceDocuments,
            int failedDocuments,
            int evidenceRows,
            String outputPath,
            String errorMessage,
            Long elapsedMs,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ExtractionRunDocument(
            UUID runId,
            UUID documentId,
            String documentTitle,
            ClassificationStatus classificationStatus,
            ExtractionDocumentStatus status,
            Integer chunkCount,
            int rowCount,
            Long elapsedMs,
            String outputPath,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    public record ExtractionRunDocumentPage(
            List<ExtractionRunDocument> items, int page, int size, long total) {
    }

    /** A document plus its classification verdict, as selected by the run's source resolver. */
    public record ExtractionCandidate(
            MultiProfileEvidenceRepository.SourceDocument document,
            ClassificationStatus classificationStatus
    ) {
    }

    /** One agent invocation captured during a dry run, for prompt iteration. */
    public record AgentTrace(
            String agentName,
            long elapsedMs,
            Map<String, Object> detail
    ) {
    }

    public record DryRunRequest(
            String questionId,
            JsonNode overrides
    ) {
    }

    public record DryRunResult(
            UUID documentId,
            String documentTitle,
            String questionId,
            int chunkCount,
            int augmentedChunkCount,
            int rowCount,
            long elapsedMs,
            List<String> headers,
            List<List<String>> rows,
            List<List<String>> anchors,
            List<AgentTrace> traces,
            String markdown,
            String configSnapshotJson,
            String errorMessage
    ) {
    }

    /** Row-level difference between two extraction runs of the same question. */
    public record RunComparison(
            UUID leftRunId,
            UUID rightRunId,
            String questionId,
            int leftRowCount,
            int rightRowCount,
            int sharedRowCount,
            int onlyInLeftCount,
            int onlyInRightCount,
            Long leftElapsedMs,
            Long rightElapsedMs,
            List<ComparisonRow> onlyInLeft,
            List<ComparisonRow> onlyInRight
    ) {
    }

    public record ComparisonRow(UUID documentId, String documentTitle, List<String> cells) {
    }
}
