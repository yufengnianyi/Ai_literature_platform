package com.example.demo_01.ai.rag;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.ai.rag.api.RagDocumentController;
import com.example.demo_01.ai.rag.api.RagJobController;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntity;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityExtraction;
import com.example.demo_01.ai.rag.entity.service.RagDocumentEntityExtractionService;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.service.RagBatchIngestionService;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import com.example.demo_01.ai.rag.service.RagIngestionFromArtifactService;
import com.example.demo_01.exception.GlobalExceptionHandler;
import com.example.demo_01.user.constant.UserConstant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RagDocumentController.class, RagJobController.class})
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class RagDocumentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagDocumentIngestionService ragDocumentIngestionService;

    @MockitoBean
    private RagBatchIngestionService ragBatchIngestionService;

    @MockitoBean
    private RagIngestionFromArtifactService ragIngestionFromArtifactService;

    @MockitoBean
    private RagDocumentEntityExtractionService ragDocumentEntityExtractionService;

    @Test
    void documentImportEndpointsShouldRequireAdminRole() throws Exception {
        assertAdminOnly(RagDocumentController.class.getMethod("upload", org.springframework.web.multipart.MultipartFile.class));
        assertAdminOnly(RagDocumentController.class.getMethod("uploadBatch", org.springframework.web.multipart.MultipartFile[].class));
        assertAdminOnly(RagDocumentController.class.getMethod("ingest", UUID.class));
        assertAdminOnly(RagDocumentController.class.getMethod("getStats"));
        assertAdminOnly(RagDocumentController.class.getMethod("getDocument", UUID.class));
        assertAdminOnly(RagDocumentController.class.getMethod("extractEntities",
                UUID.class,
                com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityExtractionRequest.class));
        assertAdminOnly(RagDocumentController.class.getMethod("extractEntitiesBatch",
                com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityBatchExtractionRequest.class));
        assertAdminOnly(RagJobController.class.getMethod("getJob", UUID.class));
    }

    @Test
    void uploadShouldReturnAcceptedPayload() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(ragDocumentIngestionService.upload(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RagUploadAcceptedResponse(jobId, documentId, RagJobStatus.QUEUED, RagIngestionStage.UPLOAD));

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "pdf".getBytes());

        mockMvc.perform(multipart("/rag/documents").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.data.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.stage").value("UPLOAD"));
    }

    @Test
    void uploadBatchShouldReturnAcceptedBatchPayload() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(ragBatchIngestionService.uploadFiles(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RagBatchAcceptedResponse(batchId, RagBatchStatus.QUEUED, 2));

        MockMultipartFile first = new MockMultipartFile("files", "a.pdf", "application/pdf", "pdf-a".getBytes());
        MockMultipartFile second = new MockMultipartFile("files", "b.pdf", "application/pdf", "pdf-b".getBytes());

        mockMvc.perform(multipart("/rag/documents/batch").file(first).file(second))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.totalFiles").value(2));
    }

    @Test
    void ingestShouldReturnAcceptedPayload() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(ragIngestionFromArtifactService.enqueueDocument(documentId, null))
                .thenReturn(new RagUploadAcceptedResponse(jobId, documentId, RagJobStatus.QUEUED, RagIngestionStage.EMBEDDING));

        mockMvc.perform(post("/rag/documents/{documentId}/ingest", documentId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.data.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.data.stage").value("EMBEDDING"));
    }

    @Test
    void getStatsShouldReturnDocumentCounts() throws Exception {
        when(ragDocumentIngestionService.getStats())
                .thenReturn(new RagDocumentStatsResponse(12, 8, 2, 1, 1));

        mockMvc.perform(get("/rag/documents/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalDocuments").value(12))
                .andExpect(jsonPath("$.data.canonicalCompletedDocuments").value(8))
                .andExpect(jsonPath("$.data.processingDocuments").value(2))
                .andExpect(jsonPath("$.data.duplicateDocuments").value(1))
                .andExpect(jsonPath("$.data.failedDocuments").value(1));
    }

    @Test
    void getDocumentShouldReturnPersistedDocument() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID latestJobId = UUID.randomUUID();
        when(ragDocumentIngestionService.getDocument(documentId)).thenReturn(new RagDocumentRecord(
                documentId,
                null,
                latestJobId,
                "doi:10.1000/test",
                "10.1000/test",
                "10.1000/test",
                "sha",
                "Test Paper",
                List.of("Alice", "Bob"),
                List.of("Org A"),
                "Abstract",
                "Nature",
                "2024-01-01",
                2024,
                null,
                "paper.pdf",
                "D:/data/rag/" + documentId,
                RagDocumentStatus.COMPLETED,
                Instant.parse("2026-03-20T01:00:00Z"),
                Instant.parse("2026-03-20T01:10:00Z")
        ));

        mockMvc.perform(get("/rag/documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.data.latestJobId").value(latestJobId.toString()))
                .andExpect(jsonPath("$.data.canonicalKey").value("doi:10.1000/test"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void extractEntitiesShouldReturnDocumentEntities() throws Exception {
        UUID documentId = UUID.randomUUID();
        RagDocumentEntityExtraction extraction = new RagDocumentEntityExtraction(
                documentId,
                "Test Paper",
                "question",
                2,
                List.of(new RagDocumentEntity(
                        "allicin",
                        "Allicin",
                        "COMPOUND",
                        List.of("diallyl thiosulfinate"),
                        List.of("chunk-1"),
                        List.of("allicin inhibited pathogen growth"),
                        0.91)),
                List.of());
        when(ragDocumentEntityExtractionService.extractDocument(documentId, "question"))
                .thenReturn(extraction);

        mockMvc.perform(post("/rag/documents/{documentId}/entities/extract", documentId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"question\":\"question\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.data.documentTitle").value("Test Paper"))
                .andExpect(jsonPath("$.data.entities[0].canonicalName").value("Allicin"))
                .andExpect(jsonPath("$.data.entities[0].entityType").value("COMPOUND"))
                .andExpect(jsonPath("$.data.entities[0].sourceChunkIds[0]").value("chunk-1"));
    }

    @Test
    void extractEntitiesBatchShouldReturnMultipleDocuments() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(ragDocumentEntityExtractionService.extractBatch(List.of(firstId, secondId), "question"))
                .thenReturn(List.of(
                        new RagDocumentEntityExtraction(firstId, "First", "question", 1, List.of(), List.of()),
                        new RagDocumentEntityExtraction(secondId, "Second", "question", 1, List.of(), List.of())));

        mockMvc.perform(post("/rag/documents/entities/extract-batch")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"question":"question","documentIds":["%s","%s"]}
                                """.formatted(firstId, secondId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].documentId").value(firstId.toString()))
                .andExpect(jsonPath("$.data[1].documentId").value(secondId.toString()));
    }

    @Test
    void getJobShouldReturnPersistedJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(ragDocumentIngestionService.getJob(jobId)).thenReturn(new RagIngestionJobRecord(
                jobId,
                documentId,
                RagJobStatus.COMPLETED,
                RagIngestionStage.COMPLETED,
                null,
                null,
                null,
                10L,
                20L,
                30L,
                40L,
                50L,
                60L,
                70L,
                280L,
                4,
                1234L,
                1200L,
                Instant.parse("2026-03-20T01:00:00Z"),
                Instant.parse("2026-03-20T01:04:40Z"),
                Instant.parse("2026-03-20T01:00:00Z"),
                Instant.parse("2026-03-20T01:04:40Z")
        ));

        mockMvc.perform(get("/rag/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.data.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.stage").value("COMPLETED"))
                .andExpect(jsonPath("$.data.providerTokensTotal").value(1200));
    }

    private static void assertAdminOnly(Method method) {
        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
        assertThat(authCheck).isNotNull();
        assertThat(authCheck.mustRole()).isEqualTo(UserConstant.ADMIN_ROLE);
    }
}
