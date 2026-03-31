package com.example.demo_01.ai.rag.retrieval;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HybridContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridContentRetriever.class);

    private final ContentRetriever denseRetriever;
    private final ContentRetriever bm25Retriever;
    private final int rrfK;
    private final int fusedMaxResults;

    public HybridContentRetriever(ContentRetriever denseRetriever,
                                  ContentRetriever bm25Retriever,
                                  int rrfK,
                                  int fusedMaxResults) {
        this.denseRetriever = denseRetriever;
        this.bm25Retriever = bm25Retriever;
        this.rrfK = rrfK;
        this.fusedMaxResults = fusedMaxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> denseResults = denseRetriever.retrieve(query);
        List<Content> bm25Results;
        try {
            bm25Results = bm25Retriever.retrieve(query);
        } catch (Exception e) {
            log.warn("BM25 retriever failed, falling back to dense-only retrieval: {}", e.getMessage());
            bm25Results = List.of();
        }

        List<List<Content>> rankedLists = new ArrayList<>();
        rankedLists.add(denseResults);
        if (!bm25Results.isEmpty()) {
            rankedLists.add(bm25Results);
        }
        List<Content> fused = rankedLists.size() == 1
                ? denseResults
                : ReciprocalRankFuser.fuse(rankedLists, rrfK);
        return deduplicateByChunkId(fused).stream()
                .limit(fusedMaxResults)
                .toList();
    }

    private List<Content> deduplicateByChunkId(List<Content> contents) {
        Map<String, Content> deduplicated = new LinkedHashMap<>();
        for (Content content : contents) {
            String chunkId = content.textSegment().metadata().getString("chunk_id");
            String dedupeKey = (chunkId == null || chunkId.isBlank())
                    ? content.textSegment().text()
                    : chunkId;
            deduplicated.putIfAbsent(dedupeKey, content);
        }
        return List.copyOf(deduplicated.values());
    }
}
