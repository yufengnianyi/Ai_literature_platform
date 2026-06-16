package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.rag.evaluation.config.RagEvaluationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QwenRerankClient {

    @Resource
    private RagEvaluationProperties properties;

    private final RestClient restClient = RestClient.create();

    public RerankResult rerank(String query, List<RerankDocument> documents, String model) {
        if (documents == null || documents.isEmpty()) {
            return new RerankResult(List.of(), 0L, 0L);
        }
        RagEvaluationProperties.Rerank cfg = properties.getRerank();
        String apiKey = cfg.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DashScope rerank API key is not configured");
        }
        String resolvedModel = model == null || model.isBlank() ? cfg.getModel() : model.trim();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", resolvedModel);
        payload.put("input", Map.of(
                "query", query == null ? "" : query,
                "documents", documents.stream().map(RerankDocument::text).toList()
        ));
        payload.put("parameters", Map.of(
                "return_documents", false,
                "top_n", documents.size()
        ));

        Instant startedAt = Instant.now();
        try {
            JsonNode root = restClient.post()
                    .uri(cfg.getEndpoint())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
            return new RerankResult(parseScores(root, documents), totalTokens(root), elapsedMs);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("DashScope rerank failed: " + ex.getResponseBodyAsString(), ex);
        }
    }

    private List<RerankScore> parseScores(JsonNode root, List<RerankDocument> documents) {
        JsonNode results = root == null ? null : root.path("output").path("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            if (index < 0 || index >= documents.size()) {
                continue;
            }
            scores.add(new RerankScore(documents.get(index).id(), node.path("relevance_score").asDouble(0.0)));
        }
        return scores;
    }

    private long totalTokens(JsonNode root) {
        JsonNode usage = root == null ? null : root.path("usage");
        if (usage == null || usage.isMissingNode()) {
            return 0L;
        }
        return usage.path("total_tokens").asLong(0L);
    }

    public record RerankDocument(String id, String text) {
    }

    public record RerankScore(String id, double score) {
    }

    public record RerankResult(List<RerankScore> scores, Long providerTotalTokens, Long elapsedMs) {
    }
}
