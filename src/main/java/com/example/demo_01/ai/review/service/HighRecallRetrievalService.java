package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.example.demo_01.ai.rag.retrieval.Bm25ContentRetriever;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

    public List<RetrievedChunk> retrieve(List<String> queries) {
        ReviewProperties.Retrieval cfg = reviewProperties.getRetrieval();
        Map<String, RetrievedChunk> candidateMap = new LinkedHashMap<>();

        // Phase 1: Document-level FTS
        Set<UUID> docIds = new LinkedHashSet<>();
        for (String query : queries) {
            try {
                List<UUID> hits = reviewRepository.searchDocumentsByFts(query, cfg.getDocFtsMaxResults());
                docIds.addAll(hits);
            } catch (Exception e) {
                log.warn("FTS query failed for '{}': {}", truncate(query), e.getMessage());
            }
        }
        log.info("Phase 1 FTS: {} unique documents from {} queries", docIds.size(), queries.size());

        // Phase 2: Chunk-level dense + BM25
        ContentRetriever denseRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(quwenEmbeddingModel)
                .maxResults(cfg.getDenseMaxResults())
                .minScore(cfg.getDenseMinScore())
                .build();
        ContentRetriever bm25Retriever = new Bm25ContentRetriever(
                bm25IndexService, objectMapper, cfg.getBm25MaxResults());

        for (String query : queries) {
            Query q = Query.from(query);
            retrieveAndMerge(denseRetriever, q, "DENSE", candidateMap);
            try {
                retrieveAndMerge(bm25Retriever, q, "BM25", candidateMap);
            } catch (Exception e) {
                log.warn("BM25 retrieval failed for query: {}", e.getMessage());
            }
        }
        log.info("Phase 2 chunk retrieval: {} unique chunks", candidateMap.size());

        // Phase 3: Document-level expansion
        List<UUID> topDocs = docIds.stream().limit(cfg.getDocExpandTop()).toList();
        if (!topDocs.isEmpty()) {
            List<RetrievedChunk> expanded = reviewRepository.findChunksByDocumentIds(new LinkedHashSet<>(topDocs));
            for (RetrievedChunk chunk : expanded) {
                candidateMap.putIfAbsent(chunk.chunkId(), chunk);
            }
            log.info("Phase 3 doc expansion: added {} chunks from {} documents, total {}",
                    expanded.size(), topDocs.size(), candidateMap.size());
        }

        List<RetrievedChunk> result = new ArrayList<>(candidateMap.values());
        if (result.size() > cfg.getMaxCandidates()) {
            result = result.subList(0, cfg.getMaxCandidates());
        }
        log.info("High-recall retrieval complete: {} candidates", result.size());
        return result;
    }

    private void retrieveAndMerge(ContentRetriever retriever, Query query,
                                   String source, Map<String, RetrievedChunk> map) {
        List<Content> contents = retriever.retrieve(query);
        for (Content content : contents) {
            TextSegment seg = content.textSegment();
            String chunkId = seg.metadata().getString("chunk_id");
            if (chunkId == null || chunkId.isBlank()) {
                chunkId = "anon_" + Integer.toHexString(seg.text().hashCode());
            }
            String docId = seg.metadata().getString("document_id");
            String title = seg.metadata().getString("title");
            String sectionPath = seg.metadata().getString("section_path");
            Object scoreObj = content.metadata() != null
                    ? content.metadata().get(dev.langchain4j.rag.content.ContentMetadata.SCORE)
                    : null;
            double score = scoreObj instanceof Number n ? n.doubleValue() : 0.0;

            RetrievedChunk existing = map.get(chunkId);
            if (existing == null || score > existing.score()) {
                map.put(chunkId, new RetrievedChunk(
                        chunkId,
                        docId != null ? parseUuid(docId) : null,
                        title,
                        seg.text(),
                        sectionPath,
                        score,
                        source
                ));
            }
        }
    }

    private UUID parseUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private String truncate(String s) {
        return s != null && s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
