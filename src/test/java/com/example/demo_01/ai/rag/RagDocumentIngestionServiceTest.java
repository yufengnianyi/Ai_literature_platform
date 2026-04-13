package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessOutcome;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.preprocessing.service.DocumentPreprocessService;
import com.example.demo_01.ai.rag.model.RagPipelineModels.DuplicateReason;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentIngestionOutcome;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionJobRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionStage;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagJobStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagUploadAcceptedResponse;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import com.example.demo_01.ai.rag.service.RagIngestionFromArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagDocumentIngestionServiceTest {

    @Mock
    private DocumentPreprocessService documentPreprocessService;

    @Mock
    private RagIngestionFromArtifactService ragIngestionFromArtifactService;

    @Mock
    private com.example.demo_01.ai.rag.repository.RagDocumentRepository documentRepository;

    @Mock
    private com.example.demo_01.ai.rag.repository.RagIngestionJobRepository jobRepository;

    private RagDocumentIngestionService service;

    @BeforeEach
    void setUp() {
        service = new RagDocumentIngestionService();
        ReflectionTestUtils.setField(service, "documentPreprocessService", documentPreprocessService);
        ReflectionTestUtils.setField(service, "ragIngestionFromArtifactService", ragIngestionFromArtifactService);
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository);
        ReflectionTestUtils.setField(service, "jobRepository", jobRepository);
    }

    @Test
    void uploadShouldPreprocessThenEnqueueArtifactIngestion() {
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "pdf-data".getBytes());
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(documentPreprocessService.preprocessMultipartBlocking(any(), eq(null)))
                .thenReturn(new PreprocessOutcome(documentId, UUID.randomUUID(), PreprocessStatus.COMPLETED, null, "doi:test", null,
                        3, 10L, 20L, 30L, 40L, 50L, 150L));
        when(ragIngestionFromArtifactService.enqueueDocument(documentId, null))
                .thenReturn(new RagUploadAcceptedResponse(jobId, documentId, RagJobStatus.QUEUED, RagIngestionStage.EMBEDDING));

        RagUploadAcceptedResponse response = service.upload(file);

        assertEquals(jobId, response.jobId());
        assertEquals(documentId, response.documentId());
        verify(documentPreprocessService).preprocessMultipartBlocking(any(), eq(null));
        verify(ragIngestionFromArtifactService).enqueueDocument(documentId, null);
    }

    @Test
    void ingestStoredPdfShouldMergePreprocessAndRagMetrics() {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Path pdf = Path.of("paper.pdf");
        when(documentPreprocessService.preprocessStoredPdf(pdf, "paper.pdf", null))
                .thenReturn(new PreprocessOutcome(documentId, UUID.randomUUID(), PreprocessStatus.COMPLETED, null, "doi:test", null,
                        3, 10L, 20L, 30L, 40L, 50L, 150L));
        when(ragIngestionFromArtifactService.ingestDocument(documentId, null))
                .thenReturn(new RagDocumentIngestionOutcome(documentId, jobId, RagJobStatus.COMPLETED, null,
                        3, 100L, 90L, null, null, null, null, null, 60L, 70L, 130L));

        RagDocumentIngestionOutcome outcome = service.ingestStoredPdf(pdf, "paper.pdf", null);

        assertEquals(3, outcome.chunkCount());
        assertEquals(100L, outcome.estimatedTokensTotal());
        assertEquals(90L, outcome.providerTokensTotal());
        assertEquals(10L, outcome.uploadMs());
        assertEquals(50L, outcome.jsonlMs());
        assertEquals(60L, outcome.embedMs());
        assertEquals(70L, outcome.persistMs());
        assertEquals(280L, outcome.totalMs());
    }

    @Test
    void getDocumentShouldDelegateToRepository() {
        UUID documentId = UUID.randomUUID();
        RagDocumentRecord record = new RagDocumentRecord(documentId, null, null, null, null, null, null, "Paper",
                List.of(), List.of(), null, null, null, null, "paper.pdf", "data/rag", RagDocumentStatus.COMPLETED, Instant.now(), Instant.now());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(record));

        assertEquals(record, service.getDocument(documentId));
    }

    @Test
    void getJobShouldDelegateToRepository() {
        UUID jobId = UUID.randomUUID();
        RagIngestionJobRecord record = new RagIngestionJobRecord(jobId, UUID.randomUUID(), RagJobStatus.COMPLETED, RagIngestionStage.COMPLETED,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, Instant.now(), Instant.now(), Instant.now(), Instant.now());
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(record));

        assertEquals(record, service.getJob(jobId));
    }

    @Test
    void ingestStoredPdfShouldPreserveDuplicateReasonFromPreprocess() {
        UUID documentId = UUID.randomUUID();
        when(documentPreprocessService.preprocessStoredPdf(Path.of("paper.pdf"), "paper.pdf", null))
                .thenReturn(new PreprocessOutcome(documentId, UUID.randomUUID(), PreprocessStatus.DUPLICATE_SKIPPED, DuplicateReason.PDF_SHA256, "pdf:sha", null,
                        0, 8L, 12L, 0L, 0L, 0L, 20L));
        when(ragIngestionFromArtifactService.ingestDocument(documentId, null))
                .thenReturn(new RagDocumentIngestionOutcome(documentId, UUID.randomUUID(), RagJobStatus.DUPLICATE_SKIPPED, null,
                        0, 0L, 0L, null, null, null, null, null, 0L, 0L, 0L));

        RagDocumentIngestionOutcome outcome = service.ingestStoredPdf(Path.of("paper.pdf"), "paper.pdf", null);

        assertEquals(DuplicateReason.PDF_SHA256, outcome.duplicateReason());
        assertEquals(20L, outcome.totalMs());
    }
}

