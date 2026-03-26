package com.example.demo_01.ai.rag.service;

import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.exception.ThrowUtils;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.repository.RagIngestionBatchRepository;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class RagBatchIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagBatchIngestionService.class);

    @Resource(name = "ragTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource
    private RagIngestionBatchRepository batchRepository;

    @Resource
    private RagDocumentIngestionService ragDocumentIngestionService;

    public RagBatchAcceptedResponse ingestFolder(String folderPath) {
        Path folder = resolveFolder(folderPath);
        List<Path> pdfFiles = listPdfFiles(folder);
        ThrowUtils.throwIf(pdfFiles.isEmpty(), ErrorCode.PARAMS_ERROR, "No PDF files found under folder: " + folder);

        UUID batchId = UUID.randomUUID();
        batchRepository.insert(batchId, folder.toAbsolutePath().toString(), RagBatchStatus.QUEUED, pdfFiles.size());
        taskExecutor.execute(() -> processBatch(batchId, folder, pdfFiles));
        return new RagBatchAcceptedResponse(batchId, RagBatchStatus.QUEUED, pdfFiles.size());
    }

    public RagIngestionBatchRecord getBatch(UUID batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Batch not found: " + batchId));
    }

    private void processBatch(UUID batchId, Path folder, List<Path> pdfFiles) {
        Instant startedAt = Instant.now();
        log.info("Starting RAG batch {} from {} with {} PDF files", batchId, folder.toAbsolutePath(), pdfFiles.size());
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

        for (int i = 0; i < pdfFiles.size(); i++) {
            Path pdfFile = pdfFiles.get(i);
            int fileNumber = i + 1;
            log.info("Batch {} processing file {}/{}: {}", batchId, fileNumber, pdfFiles.size(), pdfFile.getFileName());
            try {
                RagDocumentIngestionOutcome outcome = ragDocumentIngestionService.ingestStoredPdf(pdfFile, pdfFile.getFileName().toString(), batchId);
                accumulate(metrics, outcome);
                log.info(
                        "Batch {} file {}/{} finished: status={}, chunks={}, estimatedTokens={}, providerTokens={}, totalMs={}",
                        batchId,
                        fileNumber,
                        pdfFiles.size(),
                        outcome.status(),
                        defaultInt(outcome.chunkCount()),
                        defaultLong(outcome.estimatedTokensTotal()),
                        defaultLong(outcome.providerTokensTotal()),
                        defaultLong(outcome.totalMs())
                );
            } catch (Exception ex) {
                metrics.processedFiles++;
                metrics.failedFiles++;
                log.error("Batch {} file {}/{} failed: {}", batchId, fileNumber, pdfFiles.size(), pdfFile.getFileName(), ex);
            }
            batchRepository.update(batchId, RagBatchStatus.RUNNING, metrics, null);
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

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
