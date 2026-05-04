package com.example.demo_01.ai.rag;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.ai.rag.api.RagBatchController;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagBatchAcceptedResponse;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagBatchStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionBatchRecord;
import com.example.demo_01.ai.rag.service.RagBatchIngestionService;
import com.example.demo_01.exception.GlobalExceptionHandler;
import com.example.demo_01.user.constant.UserConstant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagBatchController.class)
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class RagBatchApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagBatchIngestionService ragBatchIngestionService;

    @Test
    void batchImportEndpointsShouldRequireAdminRole() throws Exception {
        assertAdminOnly(RagBatchController.class.getMethod("ingestFolder", com.example.demo_01.ai.rag.model.RagPipelineModels.RagFolderBatchRequest.class));
        assertAdminOnly(RagBatchController.class.getMethod("getBatch", UUID.class));
    }

    @Test
    void folderBatchShouldReturnAcceptedPayload() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(ragBatchIngestionService.ingestFolder("docs/pdfs"))
                .thenReturn(new RagBatchAcceptedResponse(batchId, RagBatchStatus.QUEUED, 12));

        mockMvc.perform(post("/rag/batches/folder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"folderPath":"docs/pdfs"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.totalFiles").value(12));
    }

    @Test
    void getBatchShouldReturnBatchState() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(ragBatchIngestionService.getBatch(batchId)).thenReturn(new RagIngestionBatchRecord(
                batchId,
                "D:/Project/ai_coding_platform/demo_01/docs/pdfs",
                RagBatchStatus.COMPLETED,
                3,
                3,
                2,
                1,
                0,
                44,
                1500L,
                1480L,
                100L,
                200L,
                300L,
                50L,
                40L,
                600L,
                70L,
                1360L,
                Instant.parse("2026-03-20T06:00:00Z"),
                Instant.parse("2026-03-20T06:22:40Z"),
                Instant.parse("2026-03-20T06:00:00Z"),
                Instant.parse("2026-03-20T06:22:40Z")
        ));

        mockMvc.perform(get("/rag/batches/{batchId}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.providerTokensTotal").value(1480))
                .andExpect(jsonPath("$.data.totalElapsedMs").value(1360));
    }

    private static void assertAdminOnly(Method method) {
        AuthCheck authCheck = method.getAnnotation(AuthCheck.class);
        assertThat(authCheck).isNotNull();
        assertThat(authCheck.mustRole()).isEqualTo(UserConstant.ADMIN_ROLE);
    }
}
