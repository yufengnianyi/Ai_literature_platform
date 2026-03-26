package com.example.demo_01.ai.rag.service;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.artifact.JsonlArtifactWriter;
import com.example.demo_01.ai.rag.chunk.TeiChunker;
import com.example.demo_01.ai.rag.client.GrobidClient;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.repository.RagIngestionJobRepository;
import com.example.demo_01.ai.rag.support.FailedLiteratureCsvRecorder;
import com.example.demo_01.ai.rag.support.Sha256Support;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class RagDocumentIngestionService {

    @Resource
    private AiPersistenceProperties properties;

    @Resource(name = "ragTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource
    private RagDocumentRepository documentRepository;

    @Resource
    private RagIngestionJobRepository jobRepository;

    @Resource
    private GrobidClient grobidClient;

    @Resource
    private TeiDocumentParser teiDocumentParser;

    @Resource
    private TeiChunker teiChunker;

    @Resource
    private JsonlArtifactWriter jsonlArtifactWriter;

    @Resource
    private RagVectorIngestionService ragVectorIngestionService;

    @Resource
    private FailedLiteratureCsvRecorder failedLiteratureCsvRecorder;

    public RagUploadAcceptedResponse upload(MultipartFile file) {
        validateUpload(file);
        InitializedIngestion ingestion = initializeMultipartUpload(file, null);

        taskExecutor.execute(() -> process(ingestion.jobId(), ingestion.documentId(), ingestion.storedUpload(), ingestion.metrics()));
        return new RagUploadAcceptedResponse(ingestion.jobId(), ingestion.documentId(), RagJobStatus.QUEUED, RagIngestionStage.UPLOAD);
    }

    public RagDocumentIngestionOutcome ingestStoredPdf(Path pdfPath, String originalFilename, UUID batchId) {
        validateStoredPdf(pdfPath);
        InitializedIngestion ingestion = initializeStoredUpload(pdfPath, originalFilename, batchId);
        return process(ingestion.jobId(), ingestion.documentId(), ingestion.storedUpload(), ingestion.metrics());
    }

    public RagDocumentRecord getDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Document not found: " + documentId));
    }

    public RagIngestionJobRecord getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Job not found: " + jobId));
    }

    private RagDocumentIngestionOutcome process(UUID jobId, UUID documentId, StoredUpload upload, RagJobMetrics metrics) {
        Instant totalStart = Instant.now();
        RagIngestionStage currentStage = RagIngestionStage.UPLOAD;
        boolean failureRecorded = false;
        try {
            currentStage = RagIngestionStage.HEADER_EXTRACTION;
            updateJob(jobId, RagJobStatus.RUNNING, currentStage, null, null, null, metrics, null);
            Instant headerStart = Instant.now();
            String headerTei = grobidClient.processHeaderDocument(upload.pdfPath());
            metrics.headerMs = Duration.between(headerStart, Instant.now()).toMillis();
            writeArtifact(upload.storageDir().resolve("header.tei.xml"), headerTei);

            RagDocumentMetadata headerMetadata;
            try {
                headerMetadata = teiDocumentParser.parseMetadata(headerTei);
            } catch (Exception parseError) {
                failedLiteratureCsvRecorder.append(documentId, "HEADER_PARSE", parseError);
                failureRecorded = true;
                throw parseError;
            }

            currentStage = RagIngestionStage.DEDUPLICATION;
            updateJob(jobId, RagJobStatus.RUNNING, currentStage, null, null, null, metrics, null);
            DuplicateMatch duplicateMatch = findDuplicate(documentId, upload.pdfSha256(), headerMetadata.doiNormalized());
            if (duplicateMatch != null) {
                String canonicalKey = duplicateMatch.document().canonicalKey();
                documentRepository.markDuplicate(documentId, duplicateMatch.document().documentId(), headerMetadata, upload.pdfSha256(), canonicalKey);
                metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
                updateJob(jobId,
                        RagJobStatus.DUPLICATE_SKIPPED,
                        RagIngestionStage.COMPLETED,
                        duplicateMatch.reason(),
                        null,
                        null,
                        metrics,
                        Instant.now());
                return toOutcome(documentId, jobId, RagJobStatus.DUPLICATE_SKIPPED, duplicateMatch.reason(), metrics);
            }

            String canonicalKey = headerMetadata.doiNormalized() != null && !headerMetadata.doiNormalized().isBlank()
                    ? "doi:" + headerMetadata.doiNormalized()
                    : "pdf_sha256:" + upload.pdfSha256();
            documentRepository.markProcessing(documentId, enrichTitle(headerMetadata, upload.originalFilename()), upload.pdfSha256(), canonicalKey);

            currentStage = RagIngestionStage.FULLTEXT_EXTRACTION;
            updateJob(jobId, RagJobStatus.RUNNING, currentStage, null, null, null, metrics, null);
            Instant fulltextStart = Instant.now();
            String fulltextTei = grobidClient.processFulltextDocument(upload.pdfPath());
            metrics.fulltextMs = Duration.between(fulltextStart, Instant.now()).toMillis();
            Path fulltextTeiPath = upload.storageDir().resolve("document.tei.xml");
            writeArtifact(fulltextTeiPath, fulltextTei);

            currentStage = RagIngestionStage.TEI_PARSING;
            updateJob(jobId, RagJobStatus.RUNNING, currentStage, null, null, null, metrics, null);
            Instant parseStart = Instant.now();
            ParsedTeiDocument parsed;
            try {
                parsed = teiDocumentParser.parse(fulltextTei);
            } catch (Exception parseError) {
                failedLiteratureCsvRecorder.append(documentId, "FULLTEXT_PARSE", parseError);
                failureRecorded = true;
                throw parseError;
            }
            metrics.teiParseMs = Duration.between(parseStart, Instant.now()).toMillis();
            RagDocumentMetadata finalMetadata = enrichTitle(headerMetadata.merge(parsed.metadata()), upload.originalFilename());

            Instant jsonlStart = Instant.now();
            List<RagChunk> chunks = teiChunker.chunk(documentId, canonicalKey, new ParsedTeiDocument(finalMetadata, parsed.chunkUnits()), upload.pdfPath(), fulltextTeiPath);
            metrics.chunkCount = chunks.size();
            Path jsonlPath = upload.storageDir().resolve("document.jsonl");
            jsonlArtifactWriter.write(jsonlPath, chunks);
            metrics.jsonlMs = Duration.between(jsonlStart, Instant.now()).toMillis();

            currentStage = RagIngestionStage.EMBEDDING;
            updateJob(jobId, RagJobStatus.RUNNING, currentStage, null, null, null, metrics, null);
            RagVectorIngestionResult ingestionResult = ragVectorIngestionService.ingestChunks(chunks);
            metrics.embedMs = ingestionResult.embedMs();
            metrics.persistMs = ingestionResult.persistMs();
            metrics.estimatedTokensTotal = ingestionResult.estimatedTokensTotal();
            metrics.providerTokensTotal = ingestionResult.providerTokensTotal();

            documentRepository.markCompleted(documentId, finalMetadata, upload.pdfSha256(), canonicalKey);
            metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
            updateJob(jobId, RagJobStatus.COMPLETED, RagIngestionStage.COMPLETED, null, null, null, metrics, Instant.now());
            return toOutcome(documentId, jobId, RagJobStatus.COMPLETED, null, metrics);
        } catch (DataIntegrityViolationException ex) {
            if (!failureRecorded) {
                failedLiteratureCsvRecorder.append(documentId, currentStage.name(), ex);
            }
            ragVectorIngestionService.removeDocument(documentId);
            documentRepository.markFailed(documentId);
            metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
            updateJob(jobId,
                    RagJobStatus.FAILED,
                    RagIngestionStage.FAILED,
                    null,
                    "DATA_INTEGRITY",
                    formatErrorMessage(currentStage, ex),
                    metrics,
                    Instant.now());
            return toOutcome(documentId, jobId, RagJobStatus.FAILED, null, metrics);
        } catch (Exception ex) {
            if (!failureRecorded) {
                failedLiteratureCsvRecorder.append(documentId, currentStage.name(), ex);
            }
            ragVectorIngestionService.removeDocument(documentId);
            documentRepository.markFailed(documentId);
            metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
            updateJob(jobId,
                    RagJobStatus.FAILED,
                    RagIngestionStage.FAILED,
                    null,
                    "PIPELINE_ERROR",
                    formatErrorMessage(currentStage, ex),
                    metrics,
                    Instant.now());
            return toOutcome(documentId, jobId, RagJobStatus.FAILED, null, metrics);
        }
    }

    private String formatErrorMessage(RagIngestionStage stage, Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return "stage=" + stage.name() + "; " + message;
    }

    private RagDocumentMetadata enrichTitle(RagDocumentMetadata metadata, String originalFilename) {
        if (metadata.title() != null && !metadata.title().isBlank()) {
            return metadata;
        }
        String fallbackTitle = originalFilename;
        int dotIndex = fallbackTitle.lastIndexOf('.');
        if (dotIndex > 0) {
            fallbackTitle = fallbackTitle.substring(0, dotIndex);
        }
        return new RagDocumentMetadata(
                metadata.doiRaw(),
                metadata.doiNormalized(),
                fallbackTitle,
                metadata.authors(),
                metadata.affiliations(),
                metadata.abstractText(),
                metadata.journal(),
                metadata.publicationDate(),
                metadata.publicationYear()
        );
    }

    private DuplicateMatch findDuplicate(UUID currentDocumentId, String pdfSha256, String doiNormalized) {
        if (doiNormalized != null && !doiNormalized.isBlank()) {
            Optional<RagDocumentRecord> byDoi = documentRepository.findCanonicalByDoi(doiNormalized)
                    .filter(record -> !record.documentId().equals(currentDocumentId));
            if (byDoi.isPresent()) {
                return new DuplicateMatch(byDoi.get(), DuplicateReason.DOI);
            }
        }
        return documentRepository.findCanonicalByPdfSha(pdfSha256)
                .filter(record -> !record.documentId().equals(currentDocumentId))
                .map(record -> new DuplicateMatch(record, DuplicateReason.PDF_SHA256))
                .orElse(null);
    }

    private InitializedIngestion initializeMultipartUpload(MultipartFile file, UUID batchId) {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant uploadStart = Instant.now();
        StoredUpload storedUpload = storeUpload(documentId, file);
        long uploadMs = Duration.between(uploadStart, Instant.now()).toMillis();
        return createQueuedIngestion(documentId, jobId, storedUpload, uploadMs, batchId);
    }

    private InitializedIngestion initializeStoredUpload(Path pdfPath, String originalFilename, UUID batchId) {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant uploadStart = Instant.now();
        StoredUpload storedUpload = storeUpload(documentId, pdfPath, originalFilename);
        long uploadMs = Duration.between(uploadStart, Instant.now()).toMillis();
        return createQueuedIngestion(documentId, jobId, storedUpload, uploadMs, batchId);
    }

    private InitializedIngestion createQueuedIngestion(UUID documentId,
                                                       UUID jobId,
                                                       StoredUpload storedUpload,
                                                       long uploadMs,
                                                       UUID batchId) {
        documentRepository.insertInitial(documentId, storedUpload.originalFilename(), storedUpload.storageDir().toAbsolutePath().toString(), RagDocumentStatus.QUEUED);
        RagJobMetrics metrics = new RagJobMetrics();
        metrics.uploadMs = uploadMs;
        jobRepository.insert(jobId, documentId, batchId, RagJobStatus.QUEUED, RagIngestionStage.UPLOAD, uploadMs);
        return new InitializedIngestion(documentId, jobId, storedUpload, metrics);
    }

    private StoredUpload storeUpload(UUID documentId, MultipartFile file) {
        try {
            Path storageDir = Path.of(properties.getRag().getStorageRoot()).resolve(documentId.toString()).toAbsolutePath();
            Files.createDirectories(storageDir);
            Path pdfPath = storageDir.resolve("source.pdf");
            file.transferTo(pdfPath);
            return new StoredUpload(storageDir, pdfPath, Sha256Support.hash(pdfPath), originalFilename(file));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Failed to persist uploaded PDF");
        }
    }

    private StoredUpload storeUpload(UUID documentId, Path sourcePdf, String originalFilename) {
        try {
            Path storageDir = Path.of(properties.getRag().getStorageRoot()).resolve(documentId.toString()).toAbsolutePath();
            Files.createDirectories(storageDir);
            Path pdfPath = storageDir.resolve("source.pdf");
            Files.copy(sourcePdf, pdfPath, StandardCopyOption.REPLACE_EXISTING);
            return new StoredUpload(storageDir, pdfPath, Sha256Support.hash(pdfPath), originalFilename == null || originalFilename.isBlank()
                    ? sourcePdf.getFileName().toString()
                    : originalFilename);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Failed to persist uploaded PDF");
        }
    }

    private void writeArtifact(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write artifact: " + path, e);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "PDF file is required");
        }
        String filename = originalFilename(file).toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Only PDF uploads are supported");
        }
    }

    private void validateStoredPdf(Path pdfPath) {
        if (pdfPath == null || !Files.isRegularFile(pdfPath)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "PDF file does not exist: " + pdfPath);
        }
        String filename = pdfPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Only PDF files are supported: " + pdfPath.getFileName());
        }
    }

    private String originalFilename(MultipartFile file) {
        return file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "document.pdf"
                : file.getOriginalFilename();
    }

    private void updateJob(UUID jobId,
                           RagJobStatus status,
                           RagIngestionStage stage,
                           DuplicateReason duplicateReason,
                           String errorCode,
                           String errorMessage,
                           RagJobMetrics metrics,
                           Instant finishedAt) {
        jobRepository.update(jobId, status, stage, duplicateReason, errorCode, errorMessage, metrics, finishedAt);
    }

    private RagDocumentIngestionOutcome toOutcome(UUID documentId,
                                                  UUID jobId,
                                                  RagJobStatus status,
                                                  DuplicateReason duplicateReason,
                                                  RagJobMetrics metrics) {
        return new RagDocumentIngestionOutcome(
                documentId,
                jobId,
                status,
                duplicateReason,
                metrics.chunkCount,
                metrics.estimatedTokensTotal,
                metrics.providerTokensTotal,
                metrics.uploadMs,
                metrics.headerMs,
                metrics.fulltextMs,
                metrics.teiParseMs,
                metrics.jsonlMs,
                metrics.embedMs,
                metrics.persistMs,
                metrics.totalMs
        );
    }

    private record StoredUpload(Path storageDir, Path pdfPath, String pdfSha256, String originalFilename) {
    }

    private record InitializedIngestion(UUID documentId, UUID jobId, StoredUpload storedUpload, RagJobMetrics metrics) {
    }

    private record DuplicateMatch(RagDocumentRecord document, DuplicateReason reason) {
    }
}
