package com.example.demo_01.ai.preprocessing.service;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchAcceptedResponse;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchMetrics;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchRecord;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchStatus;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessOutcome;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.exception.ThrowUtils;
import com.example.demo_01.ai.preprocessing.repository.DocumentPreprocessBatchRepository;
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
public class DocumentPreprocessBatchService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPreprocessBatchService.class);

    @Resource(name = "preprocessTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource
    private DocumentPreprocessBatchRepository batchRepository;

    @Resource
    private DocumentPreprocessService documentPreprocessService;

    public PreprocessBatchAcceptedResponse preprocessFolder(String folderPath) {
        Path folder = resolveFolder(folderPath);
        List<Path> pdfFiles = listPdfFiles(folder);
        ThrowUtils.throwIf(pdfFiles.isEmpty(), ErrorCode.PARAMS_ERROR, "No PDF files found under folder: " + folder);

        UUID batchId = UUID.randomUUID();
        batchRepository.insert(batchId, folder.toAbsolutePath().toString(), PreprocessBatchStatus.QUEUED, pdfFiles.size());
        taskExecutor.execute(() -> processBatch(batchId, folder, pdfFiles));
        return new PreprocessBatchAcceptedResponse(batchId, PreprocessBatchStatus.QUEUED, pdfFiles.size());
    }

    public PreprocessBatchRecord getBatch(UUID batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Preprocess batch not found: " + batchId));
    }

    private void processBatch(UUID batchId, Path folder, List<Path> pdfFiles) {
        Instant startedAt = Instant.now();
        log.info("Starting preprocess batch {} from {} with {} PDF files", batchId, folder.toAbsolutePath(), pdfFiles.size());
        PreprocessBatchMetrics metrics = new PreprocessBatchMetrics();
        metrics.totalFiles = pdfFiles.size();
        metrics.processedFiles = 0;
        metrics.completedFiles = 0;
        metrics.duplicateFiles = 0;
        metrics.failedFiles = 0;
        metrics.chunkCount = 0;
        batchRepository.update(batchId, PreprocessBatchStatus.RUNNING, metrics, null);

        for (int i = 0; i < pdfFiles.size(); i++) {
            Path pdfFile = pdfFiles.get(i);
            try {
                PreprocessOutcome outcome = documentPreprocessService.preprocessStoredPdf(pdfFile, pdfFile.getFileName().toString(), batchId);
                accumulate(metrics, outcome);
            } catch (Exception ex) {
                metrics.processedFiles++;
                metrics.failedFiles++;
                log.error("Preprocess batch {} file {}/{} failed: {}", batchId, i + 1, pdfFiles.size(), pdfFile.getFileName(), ex);
            }
            batchRepository.update(batchId, PreprocessBatchStatus.RUNNING, metrics, null);
        }

        metrics.totalElapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
        PreprocessBatchStatus finalStatus = resolveBatchStatus(metrics);
        batchRepository.update(batchId, finalStatus, metrics, Instant.now());
    }

    private void accumulate(PreprocessBatchMetrics metrics, PreprocessOutcome outcome) {
        metrics.processedFiles++;
        if (outcome.status() == PreprocessStatus.COMPLETED) {
            metrics.completedFiles++;
        } else if (outcome.status() == PreprocessStatus.DUPLICATE_SKIPPED) {
            metrics.duplicateFiles++;
        } else {
            metrics.failedFiles++;
        }
        metrics.chunkCount += defaultInt(outcome.chunkCount());
        metrics.uploadMs = defaultLong(metrics.uploadMs) + defaultLong(outcome.uploadMs());
        metrics.headerMs = defaultLong(metrics.headerMs) + defaultLong(outcome.headerMs());
        metrics.fulltextMs = defaultLong(metrics.fulltextMs) + defaultLong(outcome.fulltextMs());
        metrics.teiParseMs = defaultLong(metrics.teiParseMs) + defaultLong(outcome.teiParseMs());
        metrics.jsonlMs = defaultLong(metrics.jsonlMs) + defaultLong(outcome.jsonlMs());
    }

    private PreprocessBatchStatus resolveBatchStatus(PreprocessBatchMetrics metrics) {
        if (metrics.failedFiles == 0) {
            return PreprocessBatchStatus.COMPLETED;
        }
        if (metrics.completedFiles == 0 && metrics.duplicateFiles == 0) {
            return PreprocessBatchStatus.FAILED;
        }
        return PreprocessBatchStatus.PARTIAL_FAILED;
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
