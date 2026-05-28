package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.repository.RagIngestionBatchRepository;
import com.example.demo_01.ai.rag.service.RagBatchIngestionService;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagBatchIngestionServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private TaskExecutor batchWorkerExecutor;

    @Mock
    private RagIngestionBatchRepository batchRepository;

    @Mock
    private RagDocumentIngestionService ragDocumentIngestionService;

    private RagBatchIngestionService service;

    @BeforeEach
    void setUp() {
        service = new RagBatchIngestionService();
        ReflectionTestUtils.setField(service, "taskExecutor", taskExecutor);
        ReflectionTestUtils.setField(service, "batchWorkerExecutor", batchWorkerExecutor);
        ReflectionTestUtils.setField(service, "batchRepository", batchRepository);
        ReflectionTestUtils.setField(service, "ragDocumentIngestionService", ragDocumentIngestionService);
        AiPersistenceProperties properties = new AiPersistenceProperties();
        properties.getRag().setBatchConcurrency(2);
        properties.getRag().setStorageRoot(tempDir.toString());
        ReflectionTestUtils.setField(service, "properties", properties);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(batchWorkerExecutor).execute(any(Runnable.class));
    }

    @Test
    void ingestFolderShouldAggregateBatchMetrics() throws Exception {
        Path first = Files.writeString(tempDir.resolve("a.pdf"), "pdf-a");
        Path second = Files.writeString(tempDir.resolve("b.pdf"), "pdf-b");
        when(ragDocumentIngestionService.ingestStoredPdf(eq(first), eq("a.pdf"), any(UUID.class)))
                .thenReturn(new RagDocumentIngestionOutcome(UUID.randomUUID(), UUID.randomUUID(), RagJobStatus.COMPLETED, null,
                        3, 100L, 90L, 10L, 20L, 30L, 5L, 4L, 50L, 6L, 125L));
        when(ragDocumentIngestionService.ingestStoredPdf(eq(second), eq("b.pdf"), any(UUID.class)))
                .thenReturn(new RagDocumentIngestionOutcome(UUID.randomUUID(), UUID.randomUUID(), RagJobStatus.DUPLICATE_SKIPPED, DuplicateReason.DOI,
                        0, 0L, 0L, 8L, 12L, 0L, 0L, 0L, 0L, 0L, 20L));

        RagBatchAcceptedResponse response = service.ingestFolder(tempDir.toString());

        assertEquals(RagBatchStatus.QUEUED, response.status());
        assertEquals(2, response.totalFiles());

        ArgumentCaptor<RagBatchMetrics> metricsCaptor = ArgumentCaptor.forClass(RagBatchMetrics.class);
        verify(batchRepository).insert(eq(response.batchId()), eq(tempDir.toAbsolutePath().toString()), eq(RagBatchStatus.QUEUED), eq(2));
        verify(batchRepository).update(eq(response.batchId()), eq(RagBatchStatus.COMPLETED), metricsCaptor.capture(), any());

        RagBatchMetrics metrics = metricsCaptor.getValue();
        assertEquals(2, metrics.totalFiles);
        assertEquals(2, metrics.processedFiles);
        assertEquals(1, metrics.completedFiles);
        assertEquals(1, metrics.duplicateFiles);
        assertEquals(0, metrics.failedFiles);
        assertEquals(3, metrics.chunkCount);
        assertEquals(100L, metrics.estimatedTokensTotal);
        assertEquals(90L, metrics.providerTokensTotal);
    }

    @Test
    void ingestFolderShouldKeepProcessingWhenOneFileFails() throws Exception {
        Path first = Files.writeString(tempDir.resolve("a.pdf"), "pdf-a");
        Path second = Files.writeString(tempDir.resolve("b.pdf"), "pdf-b");
        when(ragDocumentIngestionService.ingestStoredPdf(eq(first), eq("a.pdf"), any(UUID.class)))
                .thenThrow(new IllegalStateException("boom"));
        when(ragDocumentIngestionService.ingestStoredPdf(eq(second), eq("b.pdf"), any(UUID.class)))
                .thenReturn(new RagDocumentIngestionOutcome(UUID.randomUUID(), UUID.randomUUID(), RagJobStatus.COMPLETED, null,
                        2, 80L, 70L, 10L, 20L, 30L, 5L, 4L, 50L, 6L, 125L));

        RagBatchAcceptedResponse response = service.ingestFolder(tempDir.toString());

        ArgumentCaptor<RagBatchMetrics> metricsCaptor = ArgumentCaptor.forClass(RagBatchMetrics.class);
        verify(batchRepository).update(eq(response.batchId()), eq(RagBatchStatus.PARTIAL_FAILED), metricsCaptor.capture(), any());

        RagBatchMetrics metrics = metricsCaptor.getValue();
        assertEquals(2, metrics.processedFiles);
        assertEquals(1, metrics.completedFiles);
        assertEquals(1, metrics.failedFiles);
        assertEquals(2, metrics.chunkCount);
    }
}
