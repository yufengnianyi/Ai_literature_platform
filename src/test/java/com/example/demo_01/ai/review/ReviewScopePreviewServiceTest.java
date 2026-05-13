package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.DocumentKnowledgeRepository;
import com.example.demo_01.ai.review.service.ReviewScopePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewScopePreviewServiceTest {

    @Test
    void buildPreviewShouldReturnCumulativeScoreThresholdCounts() {
        ReviewScopePreviewService service = new ReviewScopePreviewService();
        DocumentKnowledgeRepository documentKnowledgeRepository = mock(DocumentKnowledgeRepository.class);
        ReflectionTestUtils.setField(service, "documentKnowledgeRepository", documentKnowledgeRepository);
        when(documentKnowledgeRepository.findKnowledgeByDocumentIds(anySet())).thenReturn(Map.of());
        when(documentKnowledgeRepository.findAliasesByDocumentIds(anySet())).thenReturn(Map.of());

        QueryAnalysis analysis = new QueryAnalysis("question", List.of("sub question"), List.of(), List.of());
        List<ReviewDocumentCandidate> documents = List.of(
                document(0.96),
                document(0.82),
                document(0.60),
                document(null)
        );

        ReviewScopePreview preview = service.buildPreview(analysis, documents, List.of());
        Map<Double, Integer> counts = preview.scoreThresholdCounts().stream()
                .collect(Collectors.toMap(ReviewScoreThresholdCount::threshold, ReviewScoreThresholdCount::documentCount));

        assertEquals(1, counts.get(0.95));
        assertEquals(2, counts.get(0.80));
        assertEquals(3, counts.get(0.60));
        assertEquals(3, counts.get(0.00));
    }

    private ReviewDocumentCandidate document(Double score) {
        UUID documentId = UUID.randomUUID();
        return new ReviewDocumentCandidate(
                null,
                UUID.randomUUID(),
                documentId,
                "Paper " + documentId,
                1,
                List.of("chunk-" + documentId),
                score,
                score,
                0.0,
                0.0,
                score,
                score,
                Relevance.HIGH,
                "reason",
                null,
                List.of(),
                List.of(),
                true,
                score != null && score >= 0.60
        );
    }
}
