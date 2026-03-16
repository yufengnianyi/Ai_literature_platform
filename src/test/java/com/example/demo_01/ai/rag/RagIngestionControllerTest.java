package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.RagBootstrapMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagIngestionController.class)
class RagIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagIngestionService ragIngestionService;

    @Test
    void postShouldUseRebuildAsDefaultMode() throws Exception {
        Instant updatedAt = Instant.parse("2026-03-12T00:00:00Z");
        when(ragIngestionService.status()).thenReturn(new RagIngestionService.RagIngestionStatus(10L, "abc", updatedAt));

        mockMvc.perform(post("/rag/ingestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("REBUILD"))
                .andExpect(jsonPath("$.rowCount").value(10))
                .andExpect(jsonPath("$.datasetHash").value("abc"))
                .andExpect(jsonPath("$.updatedAt").value("2026-03-12T00:00:00Z"));

        verify(ragIngestionService).ingest(RagBootstrapMode.REBUILD);
    }

    @Test
    void postShouldAcceptIfEmptyMode() throws Exception {
        when(ragIngestionService.status()).thenReturn(new RagIngestionService.RagIngestionStatus(0L, null, null));

        mockMvc.perform(post("/rag/ingestions").param("mode", "if-empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("IF_EMPTY"));

        verify(ragIngestionService).ingest(RagBootstrapMode.IF_EMPTY);
    }

    @Test
    void postShouldRejectInvalidMode() throws Exception {
        mockMvc.perform(post("/rag/ingestions").param("mode", "wrong"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getStatusShouldReturnStatusPayload() throws Exception {
        when(ragIngestionService.status()).thenReturn(
                new RagIngestionService.RagIngestionStatus(3L, "hash-value", Instant.parse("2026-03-12T08:00:00Z"))
        );

        mockMvc.perform(get("/rag/ingestions/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.datasetHash").value("hash-value"))
                .andExpect(jsonPath("$.updatedAt").value("2026-03-12T08:00:00Z"));
    }
}