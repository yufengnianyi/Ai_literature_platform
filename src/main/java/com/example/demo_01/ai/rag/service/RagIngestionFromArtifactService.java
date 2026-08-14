package com.example.demo_01.ai.rag.service;

import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactLoader;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.DuplicateReason;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentIngestionOutcome;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionStage;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagJobMetrics;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagJobStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagUploadAcceptedResponse;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagVectorIngestionResult;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.repository.RagIngestionJobRepository;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class RagIngestionFromArtifactService {

    @Resource(name = "ragTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource
    private RagDocumentRepository documentRepository;

    @Resource
    private RagIngestionJobRepository jobRepository;

    @Resource
    private RagVectorIngestionService ragVectorIngestionService;

    @Resource
    private PreprocessArtifactLoader artifactLoader;

    public RagUploadAcceptedResponse enqueueDocument(UUID documentId, UUID batchId) {
        RagDocumentRecord document = loadDocument(documentId);
        PreprocessStatus preprocessStatus = documentRepository.findPreprocessStatus(documentId);
        UUID jobId = UUID.randomUUID();
        if (preprocessStatus == PreprocessStatus.DUPLICATE_SKIPPED || document.status() == RagDocumentStatus.DUPLICATE_SKIPPED) {
            jobRepository.insert(jobId, documentId, batchId, RagJobStatus.QUEUED, RagIngestionStage.COMPLETED);
            RagJobMetrics metrics = new RagJobMetrics();
            metrics.totalMs = 0L;
            jobRepository.update(jobId, RagJobStatus.DUPLICATE_SKIPPED, RagIngestionStage.COMPLETED, null, null, null, metrics, Instant.now());
            return new RagUploadAcceptedResponse(jobId, documentId, RagJobStatus.DUPLICATE_SKIPPED, RagIngestionStage.COMPLETED);
        }
        if (preprocessStatus != PreprocessStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Preprocess is not completed for document: " + documentId);
        }
        jobRepository.insert(jobId, documentId, batchId, RagJobStatus.QUEUED, RagIngestionStage.EMBEDDING);
        taskExecutor.execute(() -> process(jobId, documentId));
        return new RagUploadAcceptedResponse(jobId, documentId, RagJobStatus.QUEUED, RagIngestionStage.EMBEDDING);
    }

    public RagDocumentIngestionOutcome ingestDocument(UUID documentId, UUID batchId) {
        RagDocumentRecord document = loadDocument(documentId);
        PreprocessStatus preprocessStatus = documentRepository.findPreprocessStatus(documentId);
        UUID jobId = UUID.randomUUID();
        if (preprocessStatus == PreprocessStatus.DUPLICATE_SKIPPED || document.status() == RagDocumentStatus.DUPLICATE_SKIPPED) {
            jobRepository.insert(jobId, documentId, batchId, RagJobStatus.QUEUED, RagIngestionStage.COMPLETED);
            RagJobMetrics metrics = new RagJobMetrics();
            metrics.totalMs = 0L;
            jobRepository.update(jobId, RagJobStatus.DUPLICATE_SKIPPED, RagIngestionStage.COMPLETED, null, null, null, metrics, Instant.now());
            return new RagDocumentIngestionOutcome(documentId, jobId, RagJobStatus.DUPLICATE_SKIPPED, null,
                    0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        if (preprocessStatus != PreprocessStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Preprocess is not completed for document: " + documentId);
        }
        jobRepository.insert(jobId, documentId, batchId, RagJobStatus.QUEUED, RagIngestionStage.EMBEDDING);
        return process(jobId, documentId);
    }

    private RagDocumentRecord loadDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Document not found: " + documentId));
    }

    private RagDocumentIngestionOutcome process(UUID jobId, UUID documentId) {
        RagJobMetrics metrics = new RagJobMetrics();
        try {
            RagDocumentRecord document = loadDocument(documentId);
            Path storageDir = Path.of(document.storageRoot());
            var chunks = artifactLoader.loadChunks(storageDir);
            artifactLoader.loadManifest(storageDir);
            metrics.chunkCount = chunks.size();
            jobRepository.update(jobId, RagJobStatus.RUNNING, RagIngestionStage.EMBEDDING, null, null, null, metrics, null);
            RagVectorIngestionResult result = ragVectorIngestionService.ingestChunks(chunks);
            metrics.chunkCount = result.chunkCount();
            metrics.embedMs = result.embedMs();
            metrics.persistMs = result.persistMs();
            metrics.estimatedTokensTotal = result.estimatedTokensTotal();
            metrics.providerTokensTotal = result.providerTokensTotal();
            metrics.totalMs = result.embedMs() + result.persistMs();
            jobRepository.update(jobId, RagJobStatus.RUNNING, RagIngestionStage.PERSISTING, null, null, null, metrics, null);
            jobRepository.update(jobId, RagJobStatus.COMPLETED, RagIngestionStage.COMPLETED, null, null, null, metrics, Instant.now());
            return new RagDocumentIngestionOutcome(documentId, jobId, RagJobStatus.COMPLETED, null,
                    metrics.chunkCount, metrics.estimatedTokensTotal, metrics.providerTokensTotal,
                    null, null, null, null, null, metrics.embedMs, metrics.persistMs, metrics.totalMs);
        } catch (Exception ex) {
            ragVectorIngestionService.removeDocument(documentId);
            jobRepository.update(jobId, RagJobStatus.FAILED, RagIngestionStage.FAILED, null, "RAG_INGESTION", ex.getMessage(), metrics, Instant.now());
            return new RagDocumentIngestionOutcome(documentId, jobId, RagJobStatus.FAILED, null,
                    metrics.chunkCount, metrics.estimatedTokensTotal, metrics.providerTokensTotal,
                    null, null, null, null, null, metrics.embedMs, metrics.persistMs, metrics.totalMs);
        }
    }
}

