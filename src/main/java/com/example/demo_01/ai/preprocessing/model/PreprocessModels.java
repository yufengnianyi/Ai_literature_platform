package com.example.demo_01.ai.preprocessing.model;

import com.example.demo_01.ai.rag.model.RagPipelineModels.DuplicateReason;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;

import java.time.Instant;
import java.util.UUID;

public final class PreprocessModels {

    private PreprocessModels() {
    }

    public enum PreprocessStatus {
        QUEUED,
        RUNNING,
        DUPLICATE_SKIPPED,
        COMPLETED,
        FAILED
    }

    public enum PreprocessStage {
        UPLOAD,
        HEADER_EXTRACTION,
        DEDUPLICATION,
        FULLTEXT_EXTRACTION,
        TEI_PARSING,
        CHUNKING,
        ARTIFACT_WRITING,
        COMPLETED,
        FAILED
    }

    public enum PreprocessBatchStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        PARTIAL_FAILED,
        FAILED
    }

    public record PreprocessArtifact(
            UUID documentId,
            String storageRoot,
            String sourcePdfPath,
            String headerTeiPath,
            String fulltextTeiPath,
            String jsonlPath,
            String pdfSha256,
            String canonicalKey,
            RagDocumentMetadata metadata,
            int chunkCount,
            String chunkStrategyVersion,
            String preprocessVersion
    ) {
    }

    public record PreprocessJobRecord(
            UUID jobId,
            UUID documentId,
            UUID batchId,
            PreprocessStatus status,
            PreprocessStage stage,
            DuplicateReason duplicateReason,
            String errorCode,
            String errorMessage,
            Long uploadMs,
            Long headerMs,
            Long fulltextMs,
            Long teiParseMs,
            Long jsonlMs,
            Long totalMs,
            Integer chunkCount,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PreprocessBatchRecord(
            UUID batchId,
            String sourceFolder,
            PreprocessBatchStatus status,
            Integer totalFiles,
            Integer processedFiles,
            Integer completedFiles,
            Integer duplicateFiles,
            Integer failedFiles,
            Integer chunkCount,
            Long uploadMs,
            Long headerMs,
            Long fulltextMs,
            Long teiParseMs,
            Long jsonlMs,
            Long totalElapsedMs,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PreprocessAcceptedResponse(
            UUID jobId,
            UUID documentId,
            PreprocessStatus status,
            PreprocessStage stage
    ) {
    }

    public record PreprocessBatchAcceptedResponse(
            UUID batchId,
            PreprocessBatchStatus status,
            int totalFiles
    ) {
    }

    public record FolderPreprocessRequest(
            String folderPath
    ) {
    }

    public record PreprocessOutcome(
            UUID documentId,
            UUID jobId,
            PreprocessStatus status,
            DuplicateReason duplicateReason,
            String canonicalKey,
            RagDocumentMetadata metadata,
            Integer chunkCount,
            Long uploadMs,
            Long headerMs,
            Long fulltextMs,
            Long teiParseMs,
            Long jsonlMs,
            Long totalMs
    ) {
    }

    public static final class PreprocessJobMetrics {
        public Long uploadMs;
        public Long headerMs;
        public Long fulltextMs;
        public Long teiParseMs;
        public Long jsonlMs;
        public Long totalMs;
        public Integer chunkCount;
    }

    public static final class PreprocessBatchMetrics {
        public Integer totalFiles;
        public Integer processedFiles;
        public Integer completedFiles;
        public Integer duplicateFiles;
        public Integer failedFiles;
        public Integer chunkCount;
        public Long uploadMs;
        public Long headerMs;
        public Long fulltextMs;
        public Long teiParseMs;
        public Long jsonlMs;
        public Long totalElapsedMs;
    }
}
