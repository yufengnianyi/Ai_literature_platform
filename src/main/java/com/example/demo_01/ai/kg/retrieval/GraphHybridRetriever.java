package com.example.demo_01.ai.kg.retrieval;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GraphHybridRetriever implements ContentRetriever {

    private final ContentRetriever textRetriever;
    private final ContentRetriever graphRetriever;
    private final int rrfK;
    private final int fusedMaxResults;

    public GraphHybridRetriever(ContentRetriever textRetriever,
                                ContentRetriever graphRetriever,
                                int rrfK,
                                int fusedMaxResults) {
        this.textRetriever = textRetriever;
        this.graphRetriever = graphRetriever;
        this.rrfK = rrfK;
        this.fusedMaxResults = fusedMaxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> textResults = textRetriever.retrieve(query);
        List<Content> graphResults = graphRetriever.retrieve(query);
        if (graphResults.isEmpty()) {
            return textResults.stream().limit(fusedMaxResults).toList();
        }
        List<List<Content>> rankedLists = new ArrayList<>();
        rankedLists.add(textResults);
        rankedLists.add(graphResults);
        return deduplicate(ReciprocalRankFuser.fuse(rankedLists, rrfK)).stream()
                .limit(fusedMaxResults)
                .toList();
    }

    private List<Content> deduplicate(List<Content> contents) {
        Map<String, Content> deduplicated = new LinkedHashMap<>();
        for (Content content : contents) {
            String key = content.textSegment().metadata().getString("chunk_id");
            if (key == null || key.isBlank()) {
                key = content.textSegment().metadata().getString("graph_entity");
            }
            if (key == null || key.isBlank()) {
                key = content.textSegment().text();
            }
            deduplicated.putIfAbsent(key, content);
        }
        return List.copyOf(deduplicated.values());
    }
}
