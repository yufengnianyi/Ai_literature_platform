package com.example.demo_01.ai.review;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentSynopsis;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.Relevance;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.DocumentPromotionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentPromotionServiceTest {

    @Test
    void promoteShouldFallbackToHeuristicAndExpandTopDocumentWhenLlmFails() {
        DocumentPromotionService service = new DocumentPromotionService();
        ReviewRepository repository = mock(ReviewRepository.class);
        ChatModel chatModel = mock(ChatModel.class);
        ReviewProperties properties = new ReviewProperties();
        properties.getRetrieval().setDocumentShortlistTop(4);
        properties.getRetrieval().setDocumentExpandTop(1);
        properties.getRetrieval().setDocumentExpandChunkLimit(4);

        ReflectionTestUtils.setField(service, "reviewRepository", repository);
        ReflectionTestUtils.setField(service, "chatModel", chatModel);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "reviewProperties", properties);
        ReflectionTestUtils.setField(service, "reviewTaskExecutor", (TaskExecutor) Runnable::run);

        UUID documentId = UUID.randomUUID();
        RetrievedChunk seed = new RetrievedChunk(
                "chunk-1", documentId, "Paper A", "PsMYB1 controls zoospore development",
                "Results", 0.92, "BM25");
        RetrievedChunk promoted = new RetrievedChunk(
                "chunk-2", documentId, "Paper A", "Additional supporting evidence",
                "Discussion", 0.0, "DOC_PROMOTED");
        QueryAnalysis analysis = new QueryAnalysis(
                "哪些基因参与了疫霉菌的生殖和致病？",
                List.of(),
                List.of("Phytophthora"),
                List.of("reproduction", "pathogenicity")
        );
        RagDocumentSynopsis synopsis = new RagDocumentSynopsis(
                "Phytophthora paper about PsMYB1 and zoospore development",
                List.of("Phytophthora"),
                List.of("PsMYB1"),
                List.of("pathogenicity"),
                List.of("zoospore development"),
                List.of("CRISPR/Cas9"),
                List.of("PsMYB1 is required for zoospore development."),
                List.of("This study identifies PsMYB1 as a regulator of reproduction."),
                List.of(),
                "searchable"
        );

        when(repository.findDocumentSynopsisByIds(any())).thenReturn(Map.of(
                documentId, new ReviewRepository.DocumentSynopsisRecord(documentId, "Paper A", synopsis)
        ));
        when(repository.findPriorityChunksByDocumentIds(any(), any(Integer.class))).thenReturn(List.of(promoted));
        when(chatModel.chat(any(), any())).thenThrow(new RuntimeException("llm unavailable"));

        DocumentPromotionService.DocumentPromotionResult result =
                service.promote(analysis, analysis.mainQuestion(), List.of(seed));

        assertEquals(1, result.documentCandidates().size());
        assertEquals(1, result.expandedChunks().size());
        assertEquals("chunk-2", result.expandedChunks().get(0).chunkId());
        assertEquals("DOC_PROMOTED", result.expandedChunks().get(0).source());
        assertTrue(result.documentCandidates().get(0).selected());
        assertFalse(result.documentCandidates().get(0).finalScore() == null);
        assertTrue(result.documentCandidates().get(0).relevance().ordinal() <= Relevance.MEDIUM.ordinal());
    }
}
