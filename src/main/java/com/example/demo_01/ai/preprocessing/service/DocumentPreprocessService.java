package com.example.demo_01.ai.preprocessing.service;

import com.example.demo_01.ai.preprocessing.PreprocessingProperties;
import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactManifestWriter;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessAcceptedResponse;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessJobMetrics;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessJobRecord;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessOutcome;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStage;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.preprocessing.repository.DocumentPreprocessJobRepository;
import com.example.demo_01.ai.rag.artifact.JsonlArtifactWriter;
import com.example.demo_01.ai.rag.chunk.TeiChunker;
import com.example.demo_01.ai.rag.client.GrobidClient;
import com.example.demo_01.ai.rag.model.RagPipelineModels.DuplicateReason;
import com.example.demo_01.ai.rag.model.RagPipelineModels.ParsedTeiDocument;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.support.FailedLiteratureCsvRecorder;
import com.example.demo_01.ai.rag.support.Sha256Support;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DocumentPreprocessService {

    @Resource
    private PreprocessingProperties properties;

    @Resource(name = "preprocessTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource
    private RagDocumentRepository documentRepository;

    @Resource
    private DocumentPreprocessJobRepository jobRepository;

    @Resource
    private GrobidClient grobidClient;

    @Resource
    private TeiDocumentParser teiDocumentParser;

    @Resource
    private TeiChunker teiChunker;

    @Resource
    private JsonlArtifactWriter jsonlArtifactWriter;

    @Resource
    private PreprocessArtifactManifestWriter manifestWriter;

    @Resource
    private FailedLiteratureCsvRecorder failedLiteratureCsvRecorder;

    private final Map<String, Object> dedupeLocks = new ConcurrentHashMap<>();

    public PreprocessAcceptedResponse upload(MultipartFile file) {
        validateUpload(file);
        InitializedPreprocess preprocess = initializeMultipartUpload(file, null);
        taskExecutor.execute(() -> process(preprocess.jobId(), preprocess.documentId(), preprocess.storedUpload(), preprocess.metrics()));
        return new PreprocessAcceptedResponse(preprocess.jobId(), preprocess.documentId(), PreprocessStatus.QUEUED, PreprocessStage.UPLOAD);
    }

    public PreprocessOutcome preprocessMultipartBlocking(MultipartFile file, UUID batchId) {
        validateUpload(file);
        InitializedPreprocess preprocess = initializeMultipartUpload(file, batchId);
        return process(preprocess.jobId(), preprocess.documentId(), preprocess.storedUpload(), preprocess.metrics());
    }

    public PreprocessOutcome preprocessStoredPdf(Path pdfPath, String originalFilename, UUID batchId) {
        validateStoredPdf(pdfPath);
        InitializedPreprocess preprocess = initializeStoredUpload(pdfPath, originalFilename, batchId);
        return process(preprocess.jobId(), preprocess.documentId(), preprocess.storedUpload(), preprocess.metrics());
    }

    public PreprocessJobRecord getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Preprocess job not found: " + jobId));
    }

    private PreprocessOutcome process(UUID jobId, UUID documentId, StoredUpload upload, PreprocessJobMetrics metrics) {
        Instant totalStart = Instant.now();
        PreprocessStage currentStage = PreprocessStage.UPLOAD;
        boolean failureRecorded = false;
        documentRepository.updatePreprocessState(documentId, jobId, PreprocessStatus.RUNNING);
        try {
            currentStage = PreprocessStage.HEADER_EXTRACTION;
            updateJob(jobId, PreprocessStatus.RUNNING, currentStage, null, null, null, metrics, null);
            Instant headerStart = Instant.now();
            String headerTei = grobidClient.processHeaderDocument(upload.pdfPath());
            metrics.headerMs = Duration.between(headerStart, Instant.now()).toMillis();
            Path headerTeiPath = upload.storageDir().resolve("header.tei.xml");
            writeArtifact(headerTeiPath, headerTei);

            RagDocumentMetadata headerMetadata;
            try {
                headerMetadata = teiDocumentParser.parseMetadata(headerTei);
            } catch (Exception parseError) {
                failedLiteratureCsvRecorder.append(documentId, "HEADER_PARSE", parseError);
                failureRecorded = true;
                throw parseError;
            }

            currentStage = PreprocessStage.DEDUPLICATION;
            updateJob(jobId, PreprocessStatus.RUNNING, currentStage, null, null, null, metrics, null);
            String canonicalKey = headerMetadata.doiNormalized() != null && !headerMetadata.doiNormalized().isBlank()
                    ? "doi:" + headerMetadata.doiNormalized()
                    : "pdf_sha256:" + upload.pdfSha256();
            Object dedupeLock = dedupeLocks.computeIfAbsent(canonicalKey, key -> new Object());
            synchronized (dedupeLock) {
                try {
                    DuplicateMatch duplicateMatch = findDuplicate(documentId, upload.pdfSha256(), headerMetadata.doiNormalized());
                    if (duplicateMatch != null) {
                        String duplicateCanonicalKey = duplicateMatch.document().canonicalKey();
                        documentRepository.markDuplicate(documentId, duplicateMatch.document().documentId(), headerMetadata, upload.pdfSha256(), duplicateCanonicalKey);
                        documentRepository.updatePreprocessState(documentId, jobId, PreprocessStatus.DUPLICATE_SKIPPED);
                        metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
                        updateJob(jobId, PreprocessStatus.DUPLICATE_SKIPPED, PreprocessStage.COMPLETED, duplicateMatch.reason(), null, null, metrics, Instant.now());
                        return toOutcome(documentId, jobId, PreprocessStatus.DUPLICATE_SKIPPED, duplicateMatch.reason(), duplicateCanonicalKey, headerMetadata, metrics);
                    }

                    documentRepository.markProcessing(documentId, enrichTitle(headerMetadata, upload.originalFilename()), upload.pdfSha256(), canonicalKey);

                    currentStage = PreprocessStage.FULLTEXT_EXTRACTION;
                    updateJob(jobId, PreprocessStatus.RUNNING, currentStage, null, null, null, metrics, null);
                    Instant fulltextStart = Instant.now();
                    String fulltextTei = grobidClient.processFulltextDocument(upload.pdfPath());
                    metrics.fulltextMs = Duration.between(fulltextStart, Instant.now()).toMillis();
                    Path fulltextTeiPath = upload.storageDir().resolve("document.tei.xml");
                    writeArtifact(fulltextTeiPath, fulltextTei);

                    currentStage = PreprocessStage.TEI_PARSING;
                    updateJob(jobId, PreprocessStatus.RUNNING, currentStage, null, null, null, metrics, null);
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

                    currentStage = PreprocessStage.CHUNKING;
                    updateJob(jobId, PreprocessStatus.RUNNING, currentStage, null, null, null, metrics, null);
                    List<RagChunk> chunks = teiChunker.chunk(documentId, canonicalKey, new ParsedTeiDocument(finalMetadata, parsed.chunkUnits()), upload.pdfPath(), fulltextTeiPath);
                    metrics.chunkCount = chunks.size();

                    currentStage = PreprocessStage.ARTIFACT_WRITING;
                    updateJob(jobId, PreprocessStatus.RUNNING, currentStage, null, null, null, metrics, null);
                    Instant artifactStart = Instant.now();
                    Path jsonlPath = upload.storageDir().resolve("document.jsonl");
                    jsonlArtifactWriter.write(jsonlPath, chunks);
                    PreprocessArtifact artifact = new PreprocessArtifact(
                            documentId,
                            upload.storageDir().toAbsolutePath().toString(),
                            upload.pdfPath().toAbsolutePath().toString(),
                            headerTeiPath.toAbsolutePath().toString(),
                            fulltextTeiPath.toAbsolutePath().toString(),
                            jsonlPath.toAbsolutePath().toString(),
                            upload.pdfSha256(),
                            canonicalKey,
                            finalMetadata,
                            chunks.size(),
                            properties.getChunking().getStrategyVersion(),
                            properties.getVersion()
                    );
                    manifestWriter.write(upload.storageDir().resolve("artifact-manifest.json"), artifact);
                    metrics.jsonlMs = Duration.between(artifactStart, Instant.now()).toMillis();

                    documentRepository.markCompleted(documentId, finalMetadata, upload.pdfSha256(), canonicalKey);
                    documentRepository.updatePreprocessState(documentId, jobId, PreprocessStatus.COMPLETED);
                    metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
                    updateJob(jobId, PreprocessStatus.COMPLETED, PreprocessStage.COMPLETED, null, null, null, metrics, Instant.now());
                    return toOutcome(documentId, jobId, PreprocessStatus.COMPLETED, null, canonicalKey, finalMetadata, metrics);
                } finally {
                    dedupeLocks.remove(canonicalKey, dedupeLock);
                }
            }
        } catch (DataIntegrityViolationException ex) {
            if (!failureRecorded) {
                failedLiteratureCsvRecorder.append(documentId, currentStage.name(), ex);
            }
            documentRepository.markFailed(documentId);
            documentRepository.updatePreprocessState(documentId, jobId, PreprocessStatus.FAILED);
            metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
            updateJob(jobId, PreprocessStatus.FAILED, PreprocessStage.FAILED, null, "DATA_INTEGRITY", formatErrorMessage(currentStage, ex), metrics, Instant.now());
            return toOutcome(documentId, jobId, PreprocessStatus.FAILED, null, null, null, metrics);
        } catch (Exception ex) {
            if (!failureRecorded) {
                failedLiteratureCsvRecorder.append(documentId, currentStage.name(), ex);
            }
            documentRepository.markFailed(documentId);
            documentRepository.updatePreprocessState(documentId, jobId, PreprocessStatus.FAILED);
            metrics.totalMs = Duration.between(totalStart, Instant.now()).toMillis();
            updateJob(jobId, PreprocessStatus.FAILED, PreprocessStage.FAILED, null, "PIPELINE_ERROR", formatErrorMessage(currentStage, ex), metrics, Instant.now());
            return toOutcome(documentId, jobId, PreprocessStatus.FAILED, null, null, null, metrics);
        }
    }

    private String formatErrorMessage(PreprocessStage stage, Exception ex) {
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

    private InitializedPreprocess initializeMultipartUpload(MultipartFile file, UUID batchId) {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant uploadStart = Instant.now();
        StoredUpload storedUpload = storeUpload(documentId, file);
        long uploadMs = Duration.between(uploadStart, Instant.now()).toMillis();
        return createQueuedPreprocess(documentId, jobId, storedUpload, uploadMs, batchId);
    }

    private InitializedPreprocess initializeStoredUpload(Path pdfPath, String originalFilename, UUID batchId) {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant uploadStart = Instant.now();
        StoredUpload storedUpload = storeUpload(documentId, pdfPath, originalFilename);
        long uploadMs = Duration.between(uploadStart, Instant.now()).toMillis();
        return createQueuedPreprocess(documentId, jobId, storedUpload, uploadMs, batchId);
    }

    private InitializedPreprocess createQueuedPreprocess(UUID documentId,
                                                         UUID jobId,
                                                         StoredUpload storedUpload,
                                                         long uploadMs,
                                                         UUID batchId) {
        documentRepository.insertInitial(documentId, storedUpload.originalFilename(), storedUpload.storageDir().toAbsolutePath().toString(), RagDocumentStatus.QUEUED);
        documentRepository.updatePreprocessState(documentId, jobId, PreprocessStatus.QUEUED);
        PreprocessJobMetrics metrics = new PreprocessJobMetrics();
        metrics.uploadMs = uploadMs;
        jobRepository.insert(jobId, documentId, batchId, PreprocessStatus.QUEUED, PreprocessStage.UPLOAD, uploadMs);
        return new InitializedPreprocess(documentId, jobId, storedUpload, metrics);
    }

    private StoredUpload storeUpload(UUID documentId, MultipartFile file) {
        try {
            Path storageDir = Path.of(properties.getStorageRoot()).resolve(documentId.toString()).toAbsolutePath();
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
            Path storageDir = Path.of(properties.getStorageRoot()).resolve(documentId.toString()).toAbsolutePath();
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
                           PreprocessStatus status,
                           PreprocessStage stage,
                           DuplicateReason duplicateReason,
                           String errorCode,
                           String errorMessage,
                           PreprocessJobMetrics metrics,
                           Instant finishedAt) {
        jobRepository.update(jobId, status, stage, duplicateReason, errorCode, errorMessage, metrics, finishedAt);
    }

    private PreprocessOutcome toOutcome(UUID documentId,
                                        UUID jobId,
                                        PreprocessStatus status,
                                        DuplicateReason duplicateReason,
                                        String canonicalKey,
                                        RagDocumentMetadata metadata,
                                        PreprocessJobMetrics metrics) {
        return new PreprocessOutcome(
                documentId,
                jobId,
                status,
                duplicateReason,
                canonicalKey,
                metadata,
                metrics.chunkCount,
                metrics.uploadMs,
                metrics.headerMs,
                metrics.fulltextMs,
                metrics.teiParseMs,
                metrics.jsonlMs,
                metrics.totalMs
        );
    }

    private record StoredUpload(Path storageDir, Path pdfPath, String pdfSha256, String originalFilename) {
    }

    private record InitializedPreprocess(UUID documentId, UUID jobId, StoredUpload storedUpload, PreprocessJobMetrics metrics) {
    }

    private record DuplicateMatch(RagDocumentRecord document, DuplicateReason reason) {
    }
}
