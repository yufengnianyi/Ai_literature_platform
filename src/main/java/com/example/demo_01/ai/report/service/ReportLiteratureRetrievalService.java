package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.report.config.ReportProperties;
import com.example.demo_01.ai.rag.retrieval.Bm25ContentRetriever;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportLiteratureRetrievalService {

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private Bm25IndexService bm25IndexService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReportProperties properties;

    public List<DocumentHit> retrieve(List<String> queries, int maxDocuments) {
        if (queries == null || queries.isEmpty() || maxDocuments <= 0) {
            return List.of();
        }
        ContentRetriever dense = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(quwenEmbeddingModel)
                .maxResults(Math.max(1, properties.getDenseMaxResults()))
                .minScore(properties.getDenseMinScore())
                .build();
        ContentRetriever bm25 = new Bm25ContentRetriever(
                bm25IndexService, objectMapper, Math.max(1, properties.getBm25MaxResults()));

        Map<UUID, MutableDocumentHit> documents = new HashMap<>();
        for (String query : queries.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(Math.max(1, properties.getMaxQueriesPerRound()))
                .toList()) {
            accumulate(documents, query, dense.retrieve(Query.from(query)), "dense");
            accumulate(documents, query, bm25.retrieve(Query.from(query)), "bm25");
        }

        return documents.values().stream()
                .map(MutableDocumentHit::freeze)
                .sorted(Comparator.comparingDouble(DocumentHit::score).reversed()
                        .thenComparing(hit -> hit.documentId().toString()))
                .limit(maxDocuments)
                .toList();
    }

    private void accumulate(Map<UUID, MutableDocumentHit> documents,
                            String query,
                            List<Content> contents,
                            String route) {
        Map<String, Content> uniqueChunks = new LinkedHashMap<>();
        for (Content content : contents) {
            String chunkId = content.textSegment().metadata().getString("chunk_id");
            uniqueChunks.putIfAbsent(
                    chunkId == null || chunkId.isBlank()
                            ? content.textSegment().text()
                            : chunkId,
                    content);
        }
        int rank = 0;
        for (Content content : uniqueChunks.values()) {
            rank++;
            String rawDocumentId = content.textSegment().metadata().getString("document_id");
            UUID documentId = parseUuid(rawDocumentId);
            if (documentId == null) {
                continue;
            }
            String title = content.textSegment().metadata().getString("title");
            String chunkId = content.textSegment().metadata().getString("chunk_id");
            MutableDocumentHit hit = documents.computeIfAbsent(
                    documentId, ignored -> new MutableDocumentHit(documentId, title));
            hit.score += 1.0 / (Math.max(1, properties.getRrfK()) + rank);
            hit.queries.putIfAbsent(query, route);
            if (chunkId != null && !chunkId.isBlank()) {
                hit.chunkIds.add(chunkId);
            }
        }
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record DocumentHit(
            UUID documentId,
            String title,
            double score,
            List<String> matchedQueries,
            List<String> matchedChunkIds
    ) {
    }

    private static final class MutableDocumentHit {
        private final UUID documentId;
        private final String title;
        private double score;
        private final Map<String, String> queries = new LinkedHashMap<>();
        private final List<String> chunkIds = new ArrayList<>();

        private MutableDocumentHit(UUID documentId, String title) {
            this.documentId = documentId;
            this.title = title;
        }

        private DocumentHit freeze() {
            return new DocumentHit(
                    documentId,
                    title,
                    score,
                    List.copyOf(queries.keySet()),
                    chunkIds.stream().distinct().toList());
        }
    }
}
