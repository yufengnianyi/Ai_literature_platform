package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.rag.evaluation.config.RagEvaluationProperties;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagEvaluationServiceTest {

    @Test
    void shouldNormalizeEmptyLlmOutput() {
        RagEvaluationService service = new RagEvaluationService();

        LlmDocumentJudgmentOutput normalized = service.normalizeOutput(null);

        assertEquals(JudgmentLabel.IRRELEVANT, normalized.label());
        assertEquals(List.of(), normalized.keyEntities());
        assertEquals(List.of(), normalized.keyChunkIds());
        assertEquals(0.0, normalized.confidence());
    }

    @Test
    void shouldExtractJsonFromModelText() throws Exception {
        RagEvaluationService service = new RagEvaluationService();
        ObjectMapper objectMapper = new ObjectMapper();
        String raw = "```json\n{\"label\":\"RELEVANT\",\"keyEntities\":[\"compound\"],\"keyChunkIds\":[\"chunk-1\"],\"reason\":\"ok\",\"summary\":\"summary\",\"confidence\":0.8}\n```";

        LlmDocumentJudgmentOutput output = objectMapper.readValue(
                service.extractJson(raw), LlmDocumentJudgmentOutput.class);

        assertEquals(JudgmentLabel.RELEVANT, output.label());
        assertEquals(List.of("compound"), output.keyEntities());
        assertEquals(List.of("chunk-1"), output.keyChunkIds());
    }

    @Test
    void shouldUseOnlyOriginalQuestionForBaselineQueries() {
        RagEvaluationService service = new RagEvaluationService();

        List<String> queries = service.baselineQueries("  original question  ");

        assertEquals(List.of("original question"), queries);
    }

    @Test
    void shouldBuildReviewEntityQueriesFromRawAnalysisTerms() {
        RagEvaluationService service = new RagEvaluationService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setMaxEntityTerms(2);
        properties.setReviewEntityBestRecallEnabled(false);
        ReflectionTestUtils.setField(service, "properties", properties);
        QueryAnalysis analysis = new QueryAnalysis(
                "expanded main",
                List.of(),
                List.of("novel-species", "unknown peptide", "ignored extra"),
                List.of("antimicrobial mechanism")
        );

        List<String> queries = service.buildReviewEntityQueries(
                "original question",
                analysis,
                List.of("expanded main", "expanded subquestion")
        );

        assertEquals(List.of(
                "expanded main",
                "expanded subquestion",
                "novel-species",
                "original question novel-species",
                "unknown peptide",
                "original question unknown peptide",
                "expanded main novel-species",
                "expanded main unknown peptide"
        ), queries);
    }

    @Test
    void shouldUseConfiguredBestRecallReviewEntitiesWhenEnabled() {
        RagEvaluationService service = new RagEvaluationService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setMaxEntityTerms(2);
        properties.setReviewEntityBestRecallEnabled(true);
        properties.setReviewEntityBestRecallTerms(List.of("antibacterial compounds", "Gram-positive bacteria"));
        ReflectionTestUtils.setField(service, "properties", properties);

        List<String> queries = service.buildReviewEntityQueries(
                "original question",
                null,
                List.of("expanded main")
        );

        assertEquals(List.of(
                "expanded main",
                "antibacterial compounds",
                "original question antibacterial compounds",
                "Gram-positive bacteria",
                "original question Gram-positive bacteria",
                "expanded main antibacterial compounds",
                "expanded main Gram-positive bacteria"
        ), queries);
    }

    @Test
    void shouldBuildGoldEntityQueriesOnlyFromRelevantJudgments() {
        RagEvaluationService service = new RagEvaluationService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setMaxEntityTerms(10);
        ReflectionTestUtils.setField(service, "properties", properties);
        UUID experimentId = UUID.randomUUID();

        List<String> queries = service.buildGoldEntityQueries(
                "original question",
                List.of("expanded main"),
                List.of(
                        judgment(experimentId, UUID.randomUUID(), JudgmentLabel.RELEVANT,
                                List.of("relevant compound"), List.of("chunk-1")),
                        judgment(experimentId, UUID.randomUUID(), JudgmentLabel.DISTRACTOR,
                                List.of("distractor compound"), List.of()),
                        judgment(experimentId, UUID.randomUUID(), JudgmentLabel.IRRELEVANT,
                                List.of("irrelevant compound"), List.of())
                )
        );

        assertEquals(List.of(
                "expanded main",
                "relevant compound",
                "original question relevant compound",
                "expanded main relevant compound"
        ), queries);
    }

    @Test
    void shouldFuseOverallByRrfAndDeduplicateChunks() {
        RagEvaluationService service = new RagEvaluationService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setRrfK(60);
        ReflectionTestUtils.setField(service, "properties", properties);

        UUID experimentId = UUID.randomUUID();
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        List<RagEvaluationRetrievalHit> hits = List.of(
                hit(experimentId, RetrievalRoute.DENSE, 1, docA, "chunk-a", "q1"),
                hit(experimentId, RetrievalRoute.BM25, 1, docA, "chunk-a", "q1"),
                hit(experimentId, RetrievalRoute.FTS, 1, docB, "chunk-b", "q1")
        );

        List<RagEvaluationRetrievalHit> overall = service.fuseOverall(experimentId, hits);

        assertEquals(2, overall.size());
        assertEquals(RetrievalRoute.OVERALL, overall.get(0).route());
        assertEquals("chunk-a", overall.get(0).chunkId());
        assertEquals("chunk-b", overall.get(1).chunkId());
    }

    @Test
    void shouldFuseCustomRouteGroupToRequestedOverallRoute() {
        RagEvaluationService service = new RagEvaluationService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setRrfK(60);
        ReflectionTestUtils.setField(service, "properties", properties);

        UUID experimentId = UUID.randomUUID();
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        List<RagEvaluationRetrievalHit> hits = List.of(
                hit(experimentId, RetrievalRoute.REVIEW_ENTITY_DENSE, 1, docA, "chunk-a", "q1"),
                hit(experimentId, RetrievalRoute.REVIEW_ENTITY_BM25, 1, docA, "chunk-a", "q2"),
                hit(experimentId, RetrievalRoute.GOLD_ENTITY_DENSE, 1, docB, "chunk-b", "q3")
        );

        List<RagEvaluationRetrievalHit> overall = service.fuseOverall(
                experimentId,
                hits,
                List.of(RetrievalRoute.REVIEW_ENTITY_DENSE, RetrievalRoute.REVIEW_ENTITY_BM25),
                RetrievalRoute.REVIEW_ENTITY_OVERALL);

        assertEquals(1, overall.size());
        assertEquals(RetrievalRoute.REVIEW_ENTITY_OVERALL, overall.get(0).route());
        assertEquals("chunk-a", overall.get(0).chunkId());
        assertEquals("q1 | q2", overall.get(0).query());
    }

    @Test
    void shouldFilterRetrievalHitsToJudgedDocumentsAndRerankRoutes() {
        RagEvaluationService service = new RagEvaluationService();
        UUID experimentId = UUID.randomUUID();
        UUID judgedDoc = UUID.randomUUID();
        UUID otherDoc = UUID.randomUUID();
        List<RagEvaluationRetrievalHit> hits = List.of(
                hit(experimentId, RetrievalRoute.DENSE, 1, otherDoc, "other-1", "q1"),
                hit(experimentId, RetrievalRoute.DENSE, 2, judgedDoc, "judged-1", "q1"),
                hit(experimentId, RetrievalRoute.BM25, 7, judgedDoc, "judged-2", "q1")
        );

        List<RagEvaluationRetrievalHit> filtered = service.filterAndRerankHits(hits, Set.of(judgedDoc));

        assertEquals(2, filtered.size());
        assertEquals(RetrievalRoute.DENSE, filtered.get(0).route());
        assertEquals(1, filtered.get(0).rank());
        assertEquals("judged-1", filtered.get(0).chunkId());
        assertEquals(RetrievalRoute.BM25, filtered.get(1).route());
        assertEquals(1, filtered.get(1).rank());
        assertEquals("judged-2", filtered.get(1).chunkId());
    }

    @Test
    void shouldFilterReviewEntityHitsForHighPrecisionMode() {
        RagEvaluationService service = new RagEvaluationService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setReviewEntityHighPrecisionEnabled(true);
        properties.setReviewEntityHighPrecisionQueryMarker("antibacterial");
        ReflectionTestUtils.setField(service, "properties", properties);

        UUID experimentId = UUID.randomUUID();
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        UUID docC = UUID.randomUUID();
        List<RagEvaluationRetrievalHit> hits = List.of(
                hit(experimentId, RetrievalRoute.REVIEW_ENTITY_FTS, 1, docA, "chunk-a", "antibacterial compounds"),
                hit(experimentId, RetrievalRoute.REVIEW_ENTITY_DENSE, 5, docB, "chunk-b", "antibacterial activity"),
                hit(experimentId, RetrievalRoute.REVIEW_ENTITY_BM25, 7, docC, "chunk-c", "mechanism of action")
        );

        List<RagEvaluationRetrievalHit> filtered = service.filterHighPrecisionReviewEntityHits(Map.of(), hits);

        assertEquals(1, filtered.size());
        assertEquals(RetrievalRoute.REVIEW_ENTITY_DENSE, filtered.get(0).route());
        assertEquals(1, filtered.get(0).rank());
        assertEquals(docB, filtered.get(0).documentId());
    }

    @Test
    void shouldUseS1Dense300ProfileForRerankBestRecallPhase() {
        RagEvaluationService service = new RagEvaluationService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setFtsMaxResults(500);
        properties.setDenseMaxResults(500);
        properties.setBm25MaxResults(500);
        properties.setPriorityChunksPerFtsDocument(2);
        properties.setRrfK(60);
        properties.setReviewEntityHighPrecisionEnabled(true);
        ReflectionTestUtils.setField(service, "properties", properties);
        RagEvaluationExperimentRequest request = new RagEvaluationExperimentRequest(
                "question",
                RetrievalScope.JUDGED_DOCUMENTS,
                ExperimentPhase.RERANK_BEST_RECALL,
                100,
                null,
                null,
                null,
                false,
                false,
                true,
                "qwen3-vl-rerank",
                null);

        @SuppressWarnings("unchecked")
        Map<String, Object> config = ReflectionTestUtils.invokeMethod(
                service, "config", RetrievalScope.JUDGED_DOCUMENTS, request, null);

        assertEquals(100, config.get("ftsMaxResults"));
        assertEquals(300, config.get("denseMaxResults"));
        assertEquals(100, config.get("bm25MaxResults"));
        assertEquals(2, config.get("priorityChunksPerFtsDocument"));
        assertEquals(60, config.get("rrfK"));
        assertEquals(false, config.get("reviewEntityHighPrecisionEnabled"));
        assertEquals("S1 dense300 review entity (Recall 97.44%, Precision 48.10%)",
                config.get("reviewEntityProfile"));
    }

    private RagEvaluationRetrievalHit hit(UUID experimentId,
                                          RetrievalRoute route,
                                          int rank,
                                          UUID documentId,
                                          String chunkId,
                                          String query) {
        return new RagEvaluationRetrievalHit(null, experimentId, route, query, rank, documentId, chunkId, 1.0);
    }

    private RagEvaluationDocumentJudgment judgment(UUID experimentId,
                                                   UUID documentId,
                                                   JudgmentLabel label,
                                                   List<String> keyEntities,
                                                   List<String> keyChunkIds) {
        return new RagEvaluationDocumentJudgment(
                null, experimentId, documentId, "Paper", label, null, label,
                keyEntities, keyChunkIds, "reason", null, 0.8, null,
                Instant.now(), Instant.now());
    }
}
