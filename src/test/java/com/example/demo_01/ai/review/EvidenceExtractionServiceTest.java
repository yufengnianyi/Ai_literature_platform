package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.ExtractedEvidence;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.service.EvidenceExtractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceExtractionServiceTest {

    @Test
    void extractShouldCoerceScalarTypedEntityFieldsFromLlmJson() {
        EvidenceExtractionService service = new EvidenceExtractionService();
        ChatModel chatModel = mock(ChatModel.class);
        ReviewProperties properties = new ReviewProperties();
        properties.getExtraction().setBatchSize(3);

        ReflectionTestUtils.setField(service, "chatModel", chatModel);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "reviewProperties", properties);
        ReflectionTestUtils.setField(service, "reviewTaskExecutor", (TaskExecutor) Runnable::run);

        UUID documentId = UUID.randomUUID();
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1",
                documentId,
                "Paper A",
                "Citral inhibited Phytophthora infestans in vitro.",
                "Results",
                0.92,
                "BM25"
        );
        when(chatModel.chat(any(), any())).thenReturn(response("""
                [{
                  "chunkId": "chunk-1",
                  "documentId": "%s",
                  "documentTitle": "Paper A",
                  "claim": "Citral inhibited Phytophthora infestans.",
                  "finding": "Citral showed inhibitory activity.",
                  "methodology": "plate inhibition assay",
                  "typedEntities": {
                    "species": "",
                    "moleculeOrMetabolite": ["citral"],
                    "targetOrganism": "Phytophthora infestans",
                    "reference": "Anti-oomycete activities from essential oils and their major compounds on Phytophthora infestans",
                    "patentStatus": "not mentioned",
                    "cytotoxicitySafety": "XTT reduction IC50 > 200 uM in mammalian cells"
                  },
                  "entities": [],
                  "evidenceType": "EXPERIMENTAL",
                  "confidence": 0.87,
                  "originalText": "Citral inhibited Phytophthora infestans in vitro.",
                  "subQuestion": "Which compounds inhibit Phytophthora infestans?"
                }]
                """.formatted(documentId)));

        List<ExtractedEvidence> result = service.extract(
                "Which compounds inhibit Phytophthora infestans?",
                List.of("Which compounds inhibit Phytophthora infestans?"),
                List.of(chunk)
        );

        assertEquals(1, result.size());
        ExtractedEvidence evidence = result.get(0);
        assertEquals(List.of("Phytophthora infestans"), evidence.typedEntities().targetOrganism());
        assertEquals(
                List.of("Anti-oomycete activities from essential oils and their major compounds on Phytophthora infestans"),
                evidence.typedEntities().reference()
        );
        assertEquals(List.of("not mentioned"), evidence.typedEntities().patentStatus());
        assertEquals(List.of("XTT reduction IC50 > 200 uM in mammalian cells"),
                evidence.typedEntities().cytotoxicitySafety());
        assertTrue(evidence.typedEntities().species().isEmpty());
        assertTrue(evidence.entities().contains("citral"));
        assertTrue(evidence.entities().contains("Phytophthora infestans"));
        assertTrue(evidence.entities().contains("XTT reduction IC50 > 200 uM in mammalian cells"));
    }

    private ChatResponse response(String json) {
        return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
    }
}
