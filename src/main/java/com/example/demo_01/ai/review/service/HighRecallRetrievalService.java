package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.rag.retrieval.Bm25ContentRetriever;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexService;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class HighRecallRetrievalService {

    @Resource
    private ReviewProperties reviewProperties;

    @Resource
    private ReviewRepository reviewRepository;

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private Bm25IndexService bm25IndexService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RagDocumentSynopsisService ragDocumentSynopsisService;

    public List<RetrievedChunk> retrieveSeedChunks(List<String> queries) {
        ReviewProperties.Retrieval cfg = reviewProperties.getRetrieval();
        Map<String, RetrievedChunk> candidateMap = new LinkedHashMap<>();
        String corpusGuard = deriveCorpusGuard(queries);
        List<String> ftsQueries = selectFtsQueries(queries);

        ragDocumentSynopsisService.backfillMissingSynopses(cfg.getSeedFtsMaxResults());

        for (String query : ftsQueries) {
            try {
                List<UUID> hits = reviewRepository.searchDocumentsByFts(query, cfg.getSeedFtsMaxResults());
                for (RetrievedChunk chunk : reviewRepository.findPriorityChunksByDocumentIds(Set.copyOf(hits), 2)) {
                    if (passesCorpusGuard(chunk, corpusGuard)) {
                        candidateMap.putIfAbsent(chunk.chunkId(), chunk);
                    }
                }
            } catch (Exception e) {
                log.warn("FTS query failed for '{}': {}", truncate(query), e.getMessage());
            }
        }

        ContentRetriever denseRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(quwenEmbeddingModel)
                .maxResults(cfg.getSeedDenseMaxResults())
                .minScore(cfg.getSeedDenseMinScore())
                .build();
        ContentRetriever bm25Retriever = new Bm25ContentRetriever(
                bm25IndexService, objectMapper, cfg.getSeedBm25MaxResults());

        for (String query : queries) {
            Query ragQuery = Query.from(query);
            retrieveAndMerge(denseRetriever, ragQuery, "DENSE", candidateMap, corpusGuard);
            try {
                retrieveAndMerge(bm25Retriever, ragQuery, "BM25", candidateMap, corpusGuard);
            } catch (Exception e) {
                log.warn("BM25 retrieval failed for query '{}': {}", truncate(query), e.getMessage());
            }
        }

        List<RetrievedChunk> result = new ArrayList<>(candidateMap.values());
        result.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        if (result.size() > cfg.getMaxCandidates()) {
            result = result.subList(0, cfg.getMaxCandidates());
        }
        log.info("Seed retrieval complete: {} candidates from {} queries", result.size(), queries.size());
        return result;
    }

    public List<RetrievedChunk> retrieve(List<String> queries) {
        return retrieveSeedChunks(queries);
    }

    private void retrieveAndMerge(ContentRetriever retriever,
                                  Query query,
                                  String source,
                                  Map<String, RetrievedChunk> map,
                                  String corpusGuard) {
        List<Content> contents = retriever.retrieve(query);
        for (Content content : contents) {
            TextSegment segment = content.textSegment();
            String chunkId = segment.metadata().getString("chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                chunkId = "anon_" + Integer.toHexString(segment.text().hashCode());
            }
            String documentId = segment.metadata().getString("document_id");
            String title = segment.metadata().getString("title");
            String sectionPath = segment.metadata().getString("section_path");
            Object scoreObj = content.metadata() != null
                    ? content.metadata().get(dev.langchain4j.rag.content.ContentMetadata.SCORE)
                    : null;
            double score = scoreObj instanceof Number number ? number.doubleValue() : 0.0;
            RetrievedChunk candidate = new RetrievedChunk(
                    chunkId,
                    parseUuid(documentId),
                    title,
                    segment.text(),
                    sectionPath,
                    score,
                    source
            );
            if (!passesCorpusGuard(candidate, corpusGuard)) {
                continue;
            }
            RetrievedChunk existing = map.get(chunkId);
            if (existing == null || score > existing.score()) {
                map.put(chunkId, candidate);
            }
        }
    }

    private String deriveCorpusGuard(List<String> queries) {
        String combined = String.join(" ", queries).toLowerCase(Locale.ROOT);
        if (combined.contains("phytophthora") || combined.contains("疫霉")) {
            return "phytophthora";
        }
        return null;
    }

    private List<String> selectFtsQueries(List<String> queries) {
        List<String> concise = queries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(query -> !query.isBlank())
                .filter(this::isAsciiDominant)
                .filter(this::isConciseQuery)
                .distinct()
                .toList();
        return concise.isEmpty() ? queries : concise;
    }

    private boolean isAsciiDominant(String query) {
        long asciiChars = query.chars().filter(ch -> ch < 128).count();
        return asciiChars >= Math.max(4, query.length() / 2);
    }

    private boolean isConciseQuery(String query) {
        return query.split("\\s+").length <= 8 && query.length() <= 96;
    }

    private boolean passesCorpusGuard(RetrievedChunk chunk, String corpusGuard) {
        if (corpusGuard == null) {
            return true;
        }
        String haystack = ((chunk.documentTitle() == null ? "" : chunk.documentTitle()) + " "
                + (chunk.text() == null ? "" : chunk.text())).toLowerCase(Locale.ROOT);
        return haystack.contains(corpusGuard) || haystack.contains("p. ");
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value) {
        return value != null && value.length() > 80 ? value.substring(0, 80) + "..." : value;
    }
}
