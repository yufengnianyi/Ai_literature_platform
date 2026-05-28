package com.example.demo_01.ai.rag.service;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.repository.RagIngestionBatchRepository;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.exception.ThrowUtils;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class RagBatchIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagBatchIngestionService.class);

    @Resource(name = "ragTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource(name = "ragBatchWorkerExecutor")
    private TaskExecutor batchWorkerExecutor;

    @Resource
    private RagIngestionBatchRepository batchRepository;

    @Resource
    private RagDocumentIngestionService ragDocumentIngestionService;

    @Resource
    private AiPersistenceProperties properties;

    public RagBatchAcceptedResponse ingestFolder(String folderPath) {
        Path folder = resolveFolder(folderPath);
        List<BatchPdf> pdfFiles = listPdfFiles(folder).stream()
                .map(path -> new BatchPdf(path, path.getFileName().toString()))
                .toList();
        ThrowUtils.throwIf(pdfFiles.isEmpty(), ErrorCode.PARAMS_ERROR, "No PDF files found under folder: " + folder);

        UUID batchId = UUID.randomUUID();
        batchRepository.insert(batchId, folder.toAbsolutePath().toString(), RagBatchStatus.QUEUED, pdfFiles.size());
        taskExecutor.execute(() -> processBatch(batchId, folder.toAbsolutePath().toString(), pdfFiles));
        return new RagBatchAcceptedResponse(batchId, RagBatchStatus.QUEUED, pdfFiles.size());
    }

    public RagBatchAcceptedResponse uploadFiles(MultipartFile[] files) {
        ThrowUtils.throwIf(files == null || files.length == 0, ErrorCode.PARAMS_ERROR, "At least one PDF file is required");

        UUID batchId = UUID.randomUUID();
        Path uploadRoot = Path.of(properties.getRag().getStorageRoot())
                .resolve("upload-batches")
                .resolve(batchId.toString())
                .toAbsolutePath()
                .normalize();
        List<BatchPdf> pdfFiles = persistUploads(uploadRoot, files);
        ThrowUtils.throwIf(pdfFiles.isEmpty(), ErrorCode.PARAMS_ERROR, "At least one PDF file is required");

        batchRepository.insert(batchId, uploadRoot.toString(), RagBatchStatus.QUEUED, pdfFiles.size());
        taskExecutor.execute(() -> processBatch(batchId, uploadRoot.toString(), pdfFiles));
        return new RagBatchAcceptedResponse(batchId, RagBatchStatus.QUEUED, pdfFiles.size());
    }

    public RagIngestionBatchRecord getBatch(UUID batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Batch not found: " + batchId));
    }

    private void processBatch(UUID batchId, String source, List<BatchPdf> pdfFiles) {
        Instant startedAt = Instant.now();
        log.info("Starting RAG batch {} from {} with {} PDF files", batchId, source, pdfFiles.size());
        RagBatchMetrics metrics = new RagBatchMetrics();
        metrics.totalFiles = pdfFiles.size();
        metrics.processedFiles = 0;
        metrics.completedFiles = 0;
        metrics.duplicateFiles = 0;
        metrics.failedFiles = 0;
        metrics.chunkCount = 0;
        metrics.estimatedTokensTotal = 0L;
        metrics.providerTokensTotal = 0L;
        metrics.uploadMs = 0L;
        metrics.headerMs = 0L;
        metrics.fulltextMs = 0L;
        metrics.teiParseMs = 0L;
        metrics.jsonlMs = 0L;
        metrics.embedMs = 0L;
        metrics.persistMs = 0L;
        batchRepository.update(batchId, RagBatchStatus.RUNNING, metrics, null);

        ExecutorCompletionService<BatchFileResult> completionService = new ExecutorCompletionService<>(batchWorkerExecutor);
        int nextIndex = 0;
        int submitted = 0;
        int completed = 0;
        int concurrency = Math.max(1, properties.getRag().getBatchConcurrency());

        while (nextIndex < pdfFiles.size() && submitted - completed < concurrency) {
            submitFile(completionService, batchId, pdfFiles, nextIndex++);
            submitted++;
        }

        while (completed < pdfFiles.size()) {
            try {
                BatchFileResult result = takeCompleted(completionService);
                if (result.outcome() != null) {
                    accumulate(metrics, result.outcome());
                    log.info(
                            "Batch {} file {}/{} finished: status={}, chunks={}, estimatedTokens={}, providerTokens={}, totalMs={}",
                            batchId,
                            result.fileNumber(),
                            pdfFiles.size(),
                            result.outcome().status(),
                            defaultInt(result.outcome().chunkCount()),
                            defaultLong(result.outcome().estimatedTokensTotal()),
                            defaultLong(result.outcome().providerTokensTotal()),
                            defaultLong(result.outcome().totalMs())
                    );
                } else {
                    metrics.processedFiles++;
                    metrics.failedFiles++;
                    log.error("Batch {} file {}/{} failed: {}", batchId, result.fileNumber(), pdfFiles.size(), result.pdf().path().getFileName(), result.failure());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                metrics.failedFiles += pdfFiles.size() - completed;
                metrics.processedFiles += pdfFiles.size() - completed;
                log.error("Batch {} interrupted after {}/{} files", batchId, completed, pdfFiles.size(), ex);
                break;
            } catch (Exception ex) {
                metrics.processedFiles++;
                metrics.failedFiles++;
                log.error("Batch {} failed while collecting worker result", batchId, ex);
            }
            completed++;
            batchRepository.update(batchId, RagBatchStatus.RUNNING, metrics, null);
            if (nextIndex < pdfFiles.size()) {
                submitFile(completionService, batchId, pdfFiles, nextIndex++);
                submitted++;
            }
        }

        metrics.totalElapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
        RagBatchStatus finalStatus = resolveBatchStatus(metrics);
        batchRepository.update(batchId, finalStatus, metrics, Instant.now());
        log.info(
                "Batch {} completed with status {}. totalFiles={}, processedFiles={}, completedFiles={}, duplicateFiles={}, failedFiles={}, chunks={}, estimatedTokens={}, providerTokens={}, totalElapsedMs={}",
                batchId,
                finalStatus,
                metrics.totalFiles,
                metrics.processedFiles,
                metrics.completedFiles,
                metrics.duplicateFiles,
                metrics.failedFiles,
                metrics.chunkCount,
                metrics.estimatedTokensTotal,
                metrics.providerTokensTotal,
                metrics.totalElapsedMs
        );
    }

    private void submitFile(ExecutorCompletionService<BatchFileResult> completionService,
                            UUID batchId,
                            List<BatchPdf> pdfFiles,
                            int index) {
        BatchPdf pdf = pdfFiles.get(index);
        int fileNumber = index + 1;
        log.info("Batch {} processing file {}/{}: {}", batchId, fileNumber, pdfFiles.size(), pdf.path().getFileName());
        completionService.submit(() -> {
            try {
                RagDocumentIngestionOutcome outcome = ragDocumentIngestionService.ingestStoredPdf(pdf.path(), pdf.originalFilename(), batchId);
                return new BatchFileResult(fileNumber, pdf, outcome, null);
            } catch (Exception ex) {
                return new BatchFileResult(fileNumber, pdf, null, ex);
            }
        });
    }

    private BatchFileResult takeCompleted(ExecutorCompletionService<BatchFileResult> completionService)
            throws InterruptedException, ExecutionException {
        Future<BatchFileResult> future = completionService.take();
        return future.get();
    }

    private void accumulate(RagBatchMetrics metrics, RagDocumentIngestionOutcome outcome) {
        metrics.processedFiles++;
        if (outcome.status() == RagJobStatus.COMPLETED) {
            metrics.completedFiles++;
        } else if (outcome.status() == RagJobStatus.DUPLICATE_SKIPPED) {
            metrics.duplicateFiles++;
        } else {
            metrics.failedFiles++;
        }
        metrics.chunkCount += defaultInt(outcome.chunkCount());
        metrics.estimatedTokensTotal += defaultLong(outcome.estimatedTokensTotal());
        metrics.providerTokensTotal += defaultLong(outcome.providerTokensTotal());
        metrics.uploadMs += defaultLong(outcome.uploadMs());
        metrics.headerMs += defaultLong(outcome.headerMs());
        metrics.fulltextMs += defaultLong(outcome.fulltextMs());
        metrics.teiParseMs += defaultLong(outcome.teiParseMs());
        metrics.jsonlMs += defaultLong(outcome.jsonlMs());
        metrics.embedMs += defaultLong(outcome.embedMs());
        metrics.persistMs += defaultLong(outcome.persistMs());
    }

    private RagBatchStatus resolveBatchStatus(RagBatchMetrics metrics) {
        if (metrics.failedFiles == 0) {
            return RagBatchStatus.COMPLETED;
        }
        if (metrics.completedFiles == 0 && metrics.duplicateFiles == 0) {
            return RagBatchStatus.FAILED;
        }
        return RagBatchStatus.PARTIAL_FAILED;
    }

    private Path resolveFolder(String folderPath) {
        ThrowUtils.throwIf(folderPath == null || folderPath.isBlank(), ErrorCode.PARAMS_ERROR, "folderPath is required");
        Path path = Path.of(folderPath);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(path).normalize();
        }
        ThrowUtils.throwIf(!Files.isDirectory(path), ErrorCode.PARAMS_ERROR, "Folder does not exist: " + path);
        return path;
    }

    private List<Path> listPdfFiles(Path folder) {
        try (Stream<Path> stream = Files.walk(folder)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Failed to scan folder: " + folder);
        }
    }

    private List<BatchPdf> persistUploads(Path uploadRoot, MultipartFile[] files) {
        try {
            Files.createDirectories(uploadRoot);
            List<BatchPdf> pdfFiles = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                validateUpload(file);
                String originalFilename = originalFilename(file);
                String storedName = "%05d-%s".formatted(i + 1, sanitizeFilename(originalFilename));
                Path storedPath = uploadRoot.resolve(storedName).normalize();
                if (!storedPath.startsWith(uploadRoot)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid PDF filename");
                }
                try (var inputStream = file.getInputStream()) {
                    Files.copy(inputStream, storedPath, StandardCopyOption.REPLACE_EXISTING);
                }
                pdfFiles.add(new BatchPdf(storedPath, originalFilename));
            }
            return pdfFiles;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Failed to persist uploaded PDFs");
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

    private String originalFilename(MultipartFile file) {
        return file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "document.pdf"
                : file.getOriginalFilename();
    }

    private String sanitizeFilename(String filename) {
        String safe = Path.of(filename).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.toLowerCase(Locale.ROOT).endsWith(".pdf") ? safe : safe + ".pdf";
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private record BatchPdf(Path path, String originalFilename) {
    }

    private record BatchFileResult(int fileNumber, BatchPdf pdf, RagDocumentIngestionOutcome outcome, Throwable failure) {
    }
}
