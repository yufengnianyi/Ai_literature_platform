package com.example.demo_01.ai.rag.entity.service;

import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntity;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityExtraction;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagDocumentEntityExtractionServiceTest {

    @Test
    void shouldExtractEntitiesForDocumentChunks() {
        UUID documentId = UUID.randomUUID();
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        ReviewReasoningChatClient reasoningChatClient = mock(ReviewReasoningChatClient.class);
        RagDocumentEntityExtractionService service = service(reviewRepository, reasoningChatClient);

        when(reviewRepository.findAllChunksByDocumentId(documentId)).thenReturn(List.of(
                new RetrievedChunk("chunk-1", documentId, "Test Paper",
                        "Allicin inhibited pathogen growth.", "Results", 0.0, "DOC_ALL"),
                new RetrievedChunk("chunk-2", documentId, "Test Paper",
                        "The assay used Phytophthora capsici.", "Methods", 0.0, "DOC_ALL")
        ));
        when(reasoningChatClient.chatStandard(any(), any())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {"entities":[
                          {"mentionText":"Allicin","canonicalName":"allicin","entityType":"compound",
                           "aliases":["diallyl thiosulfinate"],"sourceChunkIds":["chunk-1"],
                           "evidenceTexts":["Allicin inhibited pathogen growth."],"confidence":0.93},
                          {"mentionText":"Phytophthora capsici","canonicalName":"Phytophthora capsici","entityType":"pathogen",
                           "aliases":[],"sourceChunkIds":["chunk-2"],
                           "evidenceTexts":["The assay used Phytophthora capsici."],"confidence":0.88}
                        ],"warnings":[]}
                        """))
                .build());

        RagDocumentEntityExtraction extraction = service.extractDocument(documentId, "antimicrobial activity");

        assertEquals(documentId, extraction.documentId());
        assertEquals("Test Paper", extraction.documentTitle());
        assertEquals(2, extraction.chunkCount());
        assertEquals(2, extraction.entities().size());
        assertEquals("allicin", extraction.entities().get(0).canonicalName());
        assertEquals("COMPOUND", extraction.entities().get(0).entityType());
        assertEquals(List.of("chunk-1"), extraction.entities().get(0).sourceChunkIds());
    }

    @Test
    void shouldMergeDuplicateEntitiesAcrossBatches() {
        RagDocumentEntityExtractionService service = service(mock(ReviewRepository.class), mock(ReviewReasoningChatClient.class));
        RagDocumentEntity first = new RagDocumentEntity(
                "allicin", "Allicin", "COMPOUND", List.of("alias-a"),
                List.of("chunk-1"), List.of("evidence 1"), 0.6);
        RagDocumentEntity second = new RagDocumentEntity(
                "Allicin", "Allicin", "COMPOUND", List.of("alias-b"),
                List.of("chunk-2"), List.of("evidence 2"), 0.9);

        RagDocumentEntityExtractionService.EntityExtractionEnvelope merged = service.merge(List.of(
                new RagDocumentEntityExtractionService.EntityExtractionEnvelope(List.of(first), List.of()),
                new RagDocumentEntityExtractionService.EntityExtractionEnvelope(List.of(second), List.of("warn"))
        ));

        assertEquals(1, merged.entities().size());
        assertEquals(List.of("alias-a", "alias-b"), merged.entities().get(0).aliases());
        assertEquals(List.of("chunk-1", "chunk-2"), merged.entities().get(0).sourceChunkIds());
        assertEquals(0.9, merged.entities().get(0).confidence());
        assertEquals(List.of("warn"), merged.warnings());
    }

    @Test
    void shouldExtractJsonFromFencedModelText() {
        RagDocumentEntityExtractionService service = service(mock(ReviewRepository.class), mock(ReviewReasoningChatClient.class));

        String json = service.extractJson("```json\n{\"entities\":[],\"warnings\":[]}\n```");

        assertEquals("{\"entities\":[],\"warnings\":[]}", json);
    }

    private RagDocumentEntityExtractionService service(ReviewRepository reviewRepository,
                                                       ReviewReasoningChatClient reasoningChatClient) {
        RagDocumentEntityExtractionService service = new RagDocumentEntityExtractionService();
        ReflectionTestUtils.setField(service, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(service, "reasoningChatClient", reasoningChatClient);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return service;
    }
}
