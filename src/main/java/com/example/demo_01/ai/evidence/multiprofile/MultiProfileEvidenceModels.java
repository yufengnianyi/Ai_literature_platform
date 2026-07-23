package com.example.demo_01.ai.evidence.multiprofile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MultiProfileEvidenceModels {

    public static final String PROFILE_VERSION = "oomycete_questions_v2";
    public static final UUID DEFAULT_SOURCE_EXPERIMENT_ID =
            UUID.fromString("9038d6bc-6213-4009-ae5e-d1bd45e0c4b8");

    private MultiProfileEvidenceModels() {
    }

    public enum BatchStatus {
        QUEUED, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED
    }

    public enum DocumentStatus {
        PENDING, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, NO_CHUNKS
    }

    public enum ClassificationStatus {
        SUPPORTED, UNCERTAIN, NOT_SUPPORTED, FAILED
    }

    public enum ProfileExtractionStatus {
        NOT_REQUESTED, QUEUED, RUNNING, COMPLETED, NO_EVIDENCE, FAILED
    }

    public enum ValidationStatus {
        VALID, INVALID
    }

    public enum ReviewStatus {
        PENDING, APPROVED, REJECTED
    }

    public record BatchRequest(UUID sourceExperimentId, boolean force) {
    }

    public record BatchAcceptedResponse(UUID batchId, BatchStatus status, int totalDocuments,
                                        boolean reused) {
    }

    public record BatchRecord(
            UUID batchId,
            UUID sourceExperimentId,
            String sourceHash,
            String profileVersion,
            String promptHash,
            String modelName,
            boolean force,
            BatchStatus status,
            int totalDocuments,
            int processedDocuments,
            int supportedMatches,
            int uncertainMatches,
            int extractedProfiles,
            int noEvidenceProfiles,
            int failedProfiles,
            String outputPath,
            String errorMessage,
            Long elapsedMs,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record DocumentRecord(
            UUID batchId,
            UUID documentId,
            String documentTitle,
            DocumentStatus status,
            Integer chunkCount,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record QuestionMatchRecord(
            UUID batchId,
            UUID documentId,
            String documentTitle,
            String questionId,
            ClassificationStatus classificationStatus,
            double confidence,
            String reason,
            List<String> evidenceChunkIds,
            ProfileExtractionStatus extractionStatus,
            int evidenceCount,
            String outputPath,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record DocumentResult(DocumentRecord document, List<QuestionMatchRecord> matches) {
    }

    public record DocumentPage(List<DocumentResult> items, int page, int size, long total) {
    }

    public record AnchorInput(String chunkId, String exactQuote) {
    }

    public record ExtractedRowInput(List<String> cells, List<AnchorInput> anchors) {
    }

    public record ExtractionOutput(List<ExtractedRowInput> rows) {
    }

    public record ValidatedAnchor(
            String chunkId,
            String sectionPath,
            Integer paragraphIndex,
            Integer sentenceStart,
            Integer sentenceEnd,
            String exactQuote,
            String quoteHash
    ) {
    }

    public record ValidatedEvidenceRow(
            UUID recordId,
            List<String> cells,
            String fingerprint,
            List<ValidatedAnchor> anchors,
            ValidationStatus validationStatus,
            String verificationNote
    ) {
        public ValidatedEvidenceRow(UUID recordId,
                                    List<String> cells,
                                    String fingerprint,
                                    List<ValidatedAnchor> anchors) {
            this(recordId, cells, fingerprint, anchors, ValidationStatus.VALID, null);
        }

        public ValidatedEvidenceRow withValidation(ValidationStatus status, String note) {
            return new ValidatedEvidenceRow(
                    recordId, cells, fingerprint, anchors, status, note);
        }
    }

    public record GenericEvidenceRecord(
            UUID recordId,
            UUID batchId,
            UUID documentId,
            String documentTitle,
            String questionId,
            String profileVersion,
            int rowIndex,
            List<String> cells,
            String rowFingerprint,
            ClassificationStatus classificationStatus,
            ValidationStatus validationStatus,
            String verificationNote,
            ReviewStatus reviewStatus,
            String reviewNote,
            boolean current,
            List<ValidatedAnchor> anchors,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record EvidencePage(List<GenericEvidenceRecord> items, int page, int size, long total) {
    }

    public record RawQuestionClassification(
            String questionId,
            String status,
            Double confidence,
            String reason,
            List<String> chunkIds
    ) {
    }

    public record ClassificationOutput(List<RawQuestionClassification> questions) {
    }

    public record ClassifiedQuestion(
            String questionId,
            ClassificationStatus status,
            double confidence,
            String reason,
            List<String> chunkIds
    ) {
    }
}
