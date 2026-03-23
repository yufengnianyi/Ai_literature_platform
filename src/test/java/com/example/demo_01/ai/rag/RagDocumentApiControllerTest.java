package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.rag.api.RagDocumentController;
import com.example.demo_01.ai.rag.api.RagJobController;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RagDocumentController.class, RagJobController.class})
class RagDocumentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagDocumentIngestionService ragDocumentIngestionService;

    @Test
    void uploadShouldReturnAcceptedPayload() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(ragDocumentIngestionService.upload(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RagUploadAcceptedResponse(jobId, documentId, RagJobStatus.QUEUED, RagIngestionStage.UPLOAD));

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "pdf".getBytes());

        mockMvc.perform(multipart("/rag/documents").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.stage").value("UPLOAD"));
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
                "paper.pdf",
                "D:/data/rag/" + documentId,
                RagDocumentStatus.COMPLETED,
                Instant.parse("2026-03-20T01:00:00Z"),
                Instant.parse("2026-03-20T01:10:00Z")
        ));

        mockMvc.perform(get("/rag/documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.latestJobId").value(latestJobId.toString()))
                .andExpect(jsonPath("$.canonicalKey").value("doi:10.1000/test"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
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
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.stage").value("COMPLETED"))
                .andExpect(jsonPath("$.providerTokensTotal").value(1200));
    }
}
