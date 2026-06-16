package com.example.demo_01.ai.rag.evaluation;

import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.example.demo_01.ai.rag.evaluation.service.RagEvaluationMetricsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationMetricsServiceTest {

    private final RagEvaluationMetricsService service = new RagEvaluationMetricsService();

    @Test
    void shouldCalculateDocumentAndChunkRecallByRoute() {
        UUID experimentId = UUID.randomUUID();
        UUID relevantDoc = UUID.randomUUID();
        UUID missedRelevantDoc = UUID.randomUUID();
        UUID distractorDoc = UUID.randomUUID();
        UUID irrelevantDoc = UUID.randomUUID();

        List<RagEvaluationDocumentJudgment> judgments = List.of(
                judgment(experimentId, relevantDoc, JudgmentLabel.RELEVANT, List.of("chunk-a")),
                judgment(experimentId, missedRelevantDoc, JudgmentLabel.RELEVANT, List.of("chunk-b")),
                judgment(experimentId, distractorDoc, JudgmentLabel.DISTRACTOR, List.of()),
                judgment(experimentId, irrelevantDoc, JudgmentLabel.IRRELEVANT, List.of())
        );
        List<RagEvaluationRetrievalHit> hits = List.of(
                hit(experimentId, RetrievalRoute.BM25, 1, relevantDoc, "chunk-a"),
                hit(experimentId, RetrievalRoute.BM25, 2, distractorDoc, "chunk-x"),
                hit(experimentId, RetrievalRoute.BM25, 3, irrelevantDoc, "chunk-y")
        );

        RagEvaluationMetrics metrics = service.calculate(judgments, hits);
        MetricSlice all = metrics.routes().stream()
                .filter(route -> route.route() == RetrievalRoute.BM25)
                .findFirst()
                .orElseThrow()
                .slices()
                .stream()
                .filter(slice -> "@all".equals(slice.at()))
                .findFirst()
                .orElseThrow();

        assertEquals(0.5, all.relevantDocRecall());
        assertEquals(0.3333, all.precision());
        assertEquals(0.3333, all.distractorRate());
        assertEquals(0.3333, all.irrelevantRate());
        assertEquals(3, all.retrievedDocCount());
        assertEquals(List.of(missedRelevantDoc), all.missedRelevantDocumentIds());
        assertEquals(0.5, all.keyChunkRecall());
        assertEquals(1, all.retrievedKeyChunkCount());
        assertEquals(2, all.totalKeyChunkCount());
        assertEquals(List.of("chunk-b"), all.missedKeyChunkIds());
        assertEquals(0.5, all.recallAtK());
        assertEquals(0.3333, all.precisionAtK());
        assertEquals(1.0, all.mrr());
        assertEquals(0.6131, all.ndcgAtK());
        assertEquals(0.5, all.map());

        MetricSlice at5 = metrics.routes().stream()
                .filter(route -> route.route() == RetrievalRoute.BM25)
                .findFirst()
                .orElseThrow()
                .slices()
                .stream()
                .filter(slice -> "@5".equals(slice.at()))
                .findFirst()
                .orElseThrow();
        assertEquals(0.5, at5.recallAtK());
        assertEquals(0.2, at5.precisionAtK());
        assertEquals(1.0, at5.mrr());
        assertEquals(0.6131, at5.ndcgAtK());
        assertEquals(0.5, at5.map());

        boolean hasAt39 = metrics.routes().stream()
                .filter(route -> route.route() == RetrievalRoute.BM25)
                .findFirst()
                .orElseThrow()
                .slices()
                .stream()
                .filter(slice -> "@39".equals(slice.at()))
                .findAny()
                .isPresent();
        assertTrue(!hasAt39);
    }

    @Test
    void shouldUseEffectiveOverrideLabelInMetrics() {
        UUID experimentId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        RagEvaluationDocumentJudgment overridden = new RagEvaluationDocumentJudgment(
                null, experimentId, documentId, "Paper", JudgmentLabel.IRRELEVANT,
                JudgmentLabel.RELEVANT, JudgmentLabel.RELEVANT, List.of("compound"),
                List.of("chunk-1"), "manual correction", null, 0.8, "override",
                Instant.now(), Instant.now());

        RagEvaluationMetrics metrics = service.calculate(
                List.of(overridden),
                List.of(hit(experimentId, RetrievalRoute.FTS, 1, documentId, "chunk-1"))
        );

        MetricSlice all = metrics.routes().stream()
                .filter(route -> route.route() == RetrievalRoute.FTS)
                .findFirst()
                .orElseThrow()
                .slices()
                .stream()
                .filter(slice -> "@all".equals(slice.at()))
                .findFirst()
                .orElseThrow();

        assertEquals(1.0, all.relevantDocRecall());
        assertEquals(1.0, all.keyChunkRecall());
    }

    private RagEvaluationDocumentJudgment judgment(UUID experimentId,
                                                   UUID documentId,
                                                   JudgmentLabel label,
                                                   List<String> keyChunkIds) {
        return new RagEvaluationDocumentJudgment(
                null, experimentId, documentId, "Paper", label, null, label,
                List.of("compound"), keyChunkIds, "reason", null, 0.8, null,
                Instant.now(), Instant.now());
    }

    private RagEvaluationRetrievalHit hit(UUID experimentId,
                                          RetrievalRoute route,
                                          int rank,
                                          UUID documentId,
                                          String chunkId) {
        return new RagEvaluationRetrievalHit(null, experimentId, route, "query", rank, documentId, chunkId, 1.0);
    }
}
