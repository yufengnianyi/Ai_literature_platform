package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PatentDemoModels {

    private PatentDemoModels() {
    }

    public enum PatentDemoRunStatus {
        QUEUED, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED
    }

    public enum PatentDemoDocumentStatus {
        PENDING, RUNNING, INGESTED, SKIPPED, FAILED
    }

    public enum PatentTextLayerStatus {
        TEXT_LAYER_USABLE,
        SCANNED_NEEDS_OCR,
        MIXED_OR_LOW_QUALITY,
        TEXT_LAYER_GARBLED,
        OVERSIZED_SKIPPED,
        PDF_ERROR
    }

    public enum PatentPageRole {
        ABSTRACT,
        Q1_ACTIVITY,
        TEST_DEFINITION,
        ACTIVITY_TABLE,
        COMPOUND_MAP,
        CLAIMS_CONTEXT,
        NEIGHBOR_CONTEXT,
        TABLE_CANDIDATE
    }

    public enum PatentPageReadStatus {
        READ_OK,
        EMPTY,
        READ_ERROR
    }

    public enum PatentDiscoveryStatus {
        COMPLETE,
        PARTIAL,
        REVIEW_REQUIRED,
        FAILED,
        NOT_APPLICABLE
    }

    public enum PatentDiscoveryOutcome {
        EVIDENCE_FOUND,
        NO_EVIDENCE_FOUND,
        UNRESOLVED
    }

    public enum PatentSubjectType {
        SINGLE_COMPOUND,
        COMPOUND_SERIES,
        COMBINATION,
        FORMULATION,
        USE_OR_PROCESS,
        UNKNOWN
    }

    public enum PatentTableCandidateSource {
        TEXT_CAPTION,
        TEXT_MATRIX,
        PDF_IMAGE,
        LAYOUT_TABLE,
        CONTINUATION
    }

    public enum PatentTableKind {
        ACTIVITY,
        STRUCTURE,
        FORMULATION,
        UNKNOWN
    }

    public record PatentDemoRunRequest(
            String manifestPath,
            String pdfRoot,
            String category,
            Integer limit,
            Long seed,
            Boolean runExtraction,
            Boolean force,
            List<String> publicationNumbers,
            String strategyVersion,
            String discoveryMode,
            String subjectMode
    ) {
        public PatentDemoRunRequest(String manifestPath, String pdfRoot, String category,
                                    Integer limit, Long seed, Boolean runExtraction, Boolean force,
                                    List<String> publicationNumbers) {
            this(manifestPath, pdfRoot, category, limit, seed, runExtraction, force,
                    publicationNumbers, null, null, null);
        }

        public PatentDemoRunRequest(String manifestPath, String pdfRoot, String category,
                                    Integer limit, Long seed, Boolean runExtraction, Boolean force) {
            this(manifestPath, pdfRoot, category, limit, seed, runExtraction, force,
                    null, null, null, null);
        }
    }

    public record PatentDemoRunAccepted(
            UUID runId,
            PatentDemoRunStatus status,
            int requestedLimit
    ) {
    }

    public record PatentDemoRunRecord(
            UUID runId,
            PatentDemoRunStatus status,
            String manifestPath,
            String pdfRoot,
            String category,
            int requestedLimit,
            Long seed,
            boolean runExtraction,
            boolean force,
            int totalCandidates,
            int textLayerPassed,
            int ingestedDocuments,
            int skippedDocuments,
            int failedDocuments,
            UUID cohortId,
            UUID extractionRunId,
            int evidenceRows,
            String errorMessage,
            Long elapsedMs,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt,
            List<String> publicationNumbers,
            String extractionStatus,
            int validEvidenceRows
    ) {
        public PatentDemoRunRecord(UUID runId, PatentDemoRunStatus status, String manifestPath,
                                   String pdfRoot, String category, int requestedLimit, Long seed,
                                   boolean runExtraction, boolean force, int totalCandidates,
                                   int textLayerPassed, int ingestedDocuments, int skippedDocuments,
                                   int failedDocuments, UUID cohortId, UUID extractionRunId,
                                   int evidenceRows, String errorMessage, Long elapsedMs,
                                   Instant startedAt, Instant finishedAt, Instant createdAt, Instant updatedAt) {
            this(runId, status, manifestPath, pdfRoot, category, requestedLimit, seed, runExtraction,
                    force, totalCandidates, textLayerPassed, ingestedDocuments, skippedDocuments,
                    failedDocuments, cohortId, extractionRunId, evidenceRows, errorMessage, elapsedMs,
                    startedAt, finishedAt, createdAt, updatedAt, List.of(), null, 0);
        }
    }

    public record PatentDemoDocumentRecord(
            UUID runId,
            String publicationNumber,
            String title,
            String pdfPath,
            PatentDemoDocumentStatus status,
            PatentTextLayerStatus textLayerStatus,
            Integer pageCount,
            Long fileSizeBytes,
            Integer sampledTextChars,
            Double usablePageRatio,
            Double replacementCharRatio,
            Integer selectedPageCount,
            Integer chunkCount,
            UUID documentId,
            String skipReason,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PatentDemoDocumentPage(
            List<PatentDemoDocumentRecord> items,
            int page,
            int size,
            long total
    ) {
    }

    public record PatentCandidate(
            String publicationNumber,
            String title,
            String category,
            Path pdfPath
    ) {
    }

    public record CandidateEnumeration(
            int totalCandidates,
            List<PatentCandidate> selectedCandidates
    ) {
    }

    public record PatentPageText(
            int pageNumber,
            String text,
            PatentPageReadStatus readStatus,
            String errorMessage
    ) {
        public PatentPageText(int pageNumber, String text) {
            this(pageNumber, text, text == null || text.isBlank()
                    ? PatentPageReadStatus.EMPTY : PatentPageReadStatus.READ_OK, null);
        }
    }

    public record PatentImageRegion(
            int pageNumber,
            float x,
            float y,
            float width,
            float height,
            String source
    ) {
    }

    public record PatentPageIndexRecord(
            int pageNumber,
            PatentPageReadStatus readStatus,
            String errorMessage,
            int textChars,
            int imageCount,
            List<PatentImageRegion> imageRegions
    ) {
    }

    public record PatentSubject(
            PatentSubjectType subjectType,
            List<String> coreComponents,
            List<String> aliases,
            List<String> claimedTargets,
            List<String> sourceSpans,
            String resolverVersion,
            String method
    ) {
    }

    public record PatentTableCandidate(
            String candidateId,
            int pageNumber,
            PatentTableCandidateSource source,
            PatentTableKind tableKind,
            boolean selectedForRead,
            String caption,
            String reason,
            PatentImageRegion region
    ) {
    }

    public record PatentEvidenceBundle(
            String bundleId,
            String assayId,
            PatentDiscoveryStatus discoveryStatus,
            PatentDiscoveryOutcome discoveryOutcome,
            List<String> tableRefs,
            List<Integer> pages,
            List<String> methodSpans,
            List<String> resultSpans,
            List<String> treatmentRows,
            List<String> conflicts,
            List<String> missingItems
    ) {
    }

    public record TextLayerReport(
            PatentTextLayerStatus status,
            int pageCount,
            long fileSizeBytes,
            int sampledTextChars,
            double usablePageRatio,
            double replacementCharRatio,
            String message
    ) {
    }

    public record PatentPageAssessment(
            int pageNumber,
            int score,
            Set<PatentPageRole> roles,
            boolean selected,
            String reason,
            int textChars,
            String text
    ) {
    }

    public record PatentTriageResult(
            List<PatentPageAssessment> pages,
            List<PatentPageAssessment> selectedPages
    ) {
    }

    public record StructuredArtifactResult(
            UUID documentId,
            Path storageDir,
            int chunkCount,
            String pdfSha256,
            String canonicalKey,
            RagDocumentMetadata metadata,
            PatentDiscoveryStatus discoveryStatus,
            PatentDiscoveryOutcome discoveryOutcome,
            int evidenceBundleCount
    ) {
        public StructuredArtifactResult(UUID documentId, Path storageDir, int chunkCount,
                                        String pdfSha256, String canonicalKey,
                                        RagDocumentMetadata metadata) {
            this(documentId, storageDir, chunkCount, pdfSha256, canonicalKey, metadata,
                    PatentDiscoveryStatus.NOT_APPLICABLE, PatentDiscoveryOutcome.UNRESOLVED, 0);
        }
    }

    public record ExistingRagDocument(
            UUID documentId,
            String storageRoot,
            RagDocumentStatus status
    ) {
        public ExistingRagDocument(UUID documentId, String storageRoot) {
            this(documentId, storageRoot, RagDocumentStatus.COMPLETED);
        }
    }
}
