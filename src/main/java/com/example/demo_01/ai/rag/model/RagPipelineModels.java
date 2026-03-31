package com.example.demo_01.ai.rag.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RagPipelineModels {

    private RagPipelineModels() {
    }

    public enum RagDocumentStatus {
        QUEUED,
        PROCESSING,
        DUPLICATE_SKIPPED,
        COMPLETED,
        FAILED
    }

    public enum RagJobStatus {
        QUEUED,
        RUNNING,
        DUPLICATE_SKIPPED,
        COMPLETED,
        FAILED
    }

    public enum RagIngestionStage {
        UPLOAD,
        HEADER_EXTRACTION,
        DEDUPLICATION,
        FULLTEXT_EXTRACTION,
        TEI_PARSING,
        JSONL_WRITING,
        EMBEDDING,
        PERSISTING,
        COMPLETED,
        FAILED
    }

    public enum DuplicateReason {
        DOI,
        PDF_SHA256
    }

    public enum RagBatchStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        PARTIAL_FAILED,
        FAILED
    }

    public record RagDocumentMetadata(
            String doiRaw,
            String doiNormalized,
            String title,
            List<String> authors,
            List<String> affiliations,
            String abstractText,
            String journal,
            String publicationDate,
            Integer publicationYear
    ) {

        public RagDocumentMetadata merge(RagDocumentMetadata other) {
            if (other == null) {
                return this;
            }
            return new RagDocumentMetadata(
                    firstNonBlank(other.doiRaw(), doiRaw),
                    firstNonBlank(other.doiNormalized(), doiNormalized),
                    firstNonBlank(other.title(), title),
                    other.authors() == null || other.authors().isEmpty() ? authors : other.authors(),
                    other.affiliations() == null || other.affiliations().isEmpty() ? affiliations : other.affiliations(),
                    firstNonBlank(other.abstractText(), abstractText),
                    firstNonBlank(other.journal(), journal),
                    firstNonBlank(other.publicationDate(), publicationDate),
                    other.publicationYear() != null ? other.publicationYear() : publicationYear
            );
        }

        private static String firstNonBlank(String preferred, String fallback) {
            return preferred != null && !preferred.isBlank() ? preferred : fallback;
        }
    }

    public record ChunkUnit(
            String contentType,
            String sectionPath,
            int paragraphIndex,
            int sentenceIndex,
            String text
    ) {
    }

    public record ParsedTeiDocument(
            RagDocumentMetadata metadata,
            List<ChunkUnit> chunkUnits
    ) {
    }

    public record RagChunk(
            UUID documentId,
            String canonicalKey,
            String doi,
            String chunkId,
            int chunkIndex,
            String contentType,
            String sectionPath,
            int paragraphIndex,
            int sentenceStart,
            int sentenceEnd,
            String title,
            String text,
            String sourcePdf,
            String sourceTei,
            String chunkStrategyVersion
    ) {
    }

    public record RagVectorIngestionResult(
            int chunkCount,
            long estimatedTokensTotal,
            long providerTokensTotal,
            long embedMs,
            long persistMs
    ) {
    }

    public record RagDocumentRecord(
            UUID documentId,
            UUID duplicateOfDocumentId,
            UUID latestJobId,
            String canonicalKey,
            String doiRaw,
            String doiNormalized,
            String pdfSha256,
            String title,
            List<String> authors,
            List<String> affiliations,
            String abstractText,
            String journal,
            String publicationDate,
            Integer publicationYear,
            String sourceFilename,
            String storageRoot,
            RagDocumentStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RagIngestionJobRecord(
            UUID jobId,
            UUID documentId,
            RagJobStatus status,
            RagIngestionStage stage,
            DuplicateReason duplicateReason,
            String errorCode,
            String errorMessage,
            Long uploadMs,
            Long headerMs,
            Long fulltextMs,
            Long teiParseMs,
            Long jsonlMs,
            Long embedMs,
            Long persistMs,
            Long totalMs,
            Integer chunkCount,
            Long estimatedTokensTotal,
            Long providerTokensTotal,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RagIngestionBatchRecord(
            UUID batchId,
            String sourceFolder,
            RagBatchStatus status,
            Integer totalFiles,
            Integer processedFiles,
            Integer completedFiles,
            Integer duplicateFiles,
            Integer failedFiles,
            Integer chunkCount,
            Long estimatedTokensTotal,
            Long providerTokensTotal,
            Long uploadMs,
            Long headerMs,
            Long fulltextMs,
            Long teiParseMs,
            Long jsonlMs,
            Long embedMs,
            Long persistMs,
            Long totalElapsedMs,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RagUploadAcceptedResponse(
            UUID jobId,
            UUID documentId,
            RagJobStatus status,
            RagIngestionStage stage
    ) {
    }

    public record RagBatchAcceptedResponse(
            UUID batchId,
            RagBatchStatus status,
            int totalFiles
    ) {
    }

    public record RagFolderBatchRequest(
            String folderPath
    ) {
    }

    public record RagDocumentIngestionOutcome(
            UUID documentId,
            UUID jobId,
            RagJobStatus status,
            DuplicateReason duplicateReason,
            Integer chunkCount,
            Long estimatedTokensTotal,
            Long providerTokensTotal,
            Long uploadMs,
            Long headerMs,
            Long fulltextMs,
            Long teiParseMs,
            Long jsonlMs,
            Long embedMs,
            Long persistMs,
            Long totalMs
    ) {
    }

    public static final class RagJobMetrics {
        public Long uploadMs;
        public Long headerMs;
        public Long fulltextMs;
        public Long teiParseMs;
        public Long jsonlMs;
        public Long embedMs;
        public Long persistMs;
        public Long totalMs;
        public Integer chunkCount;
        public Long estimatedTokensTotal;
        public Long providerTokensTotal;
    }

    public static final class RagBatchMetrics {
        public Integer totalFiles;
        public Integer processedFiles;
        public Integer completedFiles;
        public Integer duplicateFiles;
        public Integer failedFiles;
        public Integer chunkCount;
        public Long estimatedTokensTotal;
        public Long providerTokensTotal;
        public Long uploadMs;
        public Long headerMs;
        public Long fulltextMs;
        public Long teiParseMs;
        public Long jsonlMs;
        public Long embedMs;
        public Long persistMs;
        public Long totalElapsedMs;
    }
}
