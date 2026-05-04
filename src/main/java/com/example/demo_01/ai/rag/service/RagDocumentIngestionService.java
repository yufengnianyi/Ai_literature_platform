package com.example.demo_01.ai.rag.service;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessOutcome;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.preprocessing.service.DocumentPreprocessService;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentIngestionOutcome;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionJobRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagJobStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagUploadAcceptedResponse;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.repository.RagIngestionJobRepository;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.UUID;

@Service
public class RagDocumentIngestionService {

    @Resource
    private DocumentPreprocessService documentPreprocessService;

    @Resource
    private RagIngestionFromArtifactService ragIngestionFromArtifactService;

    @Resource
    private RagDocumentRepository documentRepository;

    @Resource
    private RagIngestionJobRepository jobRepository;

    public RagUploadAcceptedResponse upload(MultipartFile file) {
        PreprocessOutcome outcome = documentPreprocessService.preprocessMultipartBlocking(file, null);
        return ragIngestionFromArtifactService.enqueueDocument(outcome.documentId(), null);
    }

    public RagDocumentIngestionOutcome ingestStoredPdf(Path pdfPath, String originalFilename, UUID batchId) {
        PreprocessOutcome preprocess = documentPreprocessService.preprocessStoredPdf(pdfPath, originalFilename, batchId);
        if (preprocess.status() == PreprocessStatus.FAILED) {
            return new RagDocumentIngestionOutcome(
                    preprocess.documentId(),
                    preprocess.jobId(),
                    RagJobStatus.FAILED,
                    preprocess.duplicateReason(),
                    preprocess.chunkCount(),
                    0L,
                    0L,
                    preprocess.uploadMs(),
                    preprocess.headerMs(),
                    preprocess.fulltextMs(),
                    preprocess.teiParseMs(),
                    preprocess.jsonlMs(),
                    0L,
                    0L,
                    preprocess.totalMs()
            );
        }
        RagDocumentIngestionOutcome rag = ragIngestionFromArtifactService.ingestDocument(preprocess.documentId(), batchId);
        return new RagDocumentIngestionOutcome(
                rag.documentId(),
                rag.jobId(),
                rag.status(),
                rag.duplicateReason() != null ? rag.duplicateReason() : preprocess.duplicateReason(),
                rag.chunkCount() != null && rag.chunkCount() > 0 ? rag.chunkCount() : preprocess.chunkCount(),
                rag.estimatedTokensTotal(),
                rag.providerTokensTotal(),
                preprocess.uploadMs(),
                preprocess.headerMs(),
                preprocess.fulltextMs(),
                preprocess.teiParseMs(),
                preprocess.jsonlMs(),
                rag.embedMs(),
                rag.persistMs(),
                sum(preprocess.totalMs(), rag.embedMs(), rag.persistMs())
        );
    }

    public RagDocumentRecord getDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Document not found: " + documentId));
    }

    public RagIngestionJobRecord getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Job not found: " + jobId));
    }

    private Long sum(Long... values) {
        long total = 0L;
        for (Long value : values) {
            if (value != null) {
                total += value;
            }
        }
        return total;
    }
}
