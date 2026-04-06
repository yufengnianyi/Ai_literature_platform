package com.example.demo_01.ai.rag.retrieval;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridContentRetrieverTest {

    @Test
    void shouldDeduplicateAndFuseDenseAndBm25Results() {
        Content denseShared = content("chunk-1", "dense shared");
        Content denseOnly = content("chunk-2", "dense only");
        Content sparseShared = content("chunk-1", "sparse shared");
        Content sparseOnly = content("chunk-3", "sparse only");

        ContentRetriever denseRetriever = query -> List.of(denseShared, denseOnly);
        ContentRetriever bm25Retriever = query -> List.of(sparseShared, sparseOnly);

        HybridContentRetriever retriever = new HybridContentRetriever(denseRetriever, bm25Retriever, 60, 5);

        List<Content> results = retriever.retrieve(Query.from("RLK"));

        assertEquals(3, results.size());
        assertEquals("chunk-1", results.get(0).textSegment().metadata().getString("chunk_id"));
        assertEquals("chunk-2", results.get(1).textSegment().metadata().getString("chunk_id"));
        assertEquals("chunk-3", results.get(2).textSegment().metadata().getString("chunk_id"));
    }

    @Test
    void shouldFallBackToDenseResultsWhenBm25IsEmpty() {
        ContentRetriever denseRetriever = query -> List.of(content("chunk-1", "dense only"));
        ContentRetriever bm25Retriever = query -> List.of();

        HybridContentRetriever retriever = new HybridContentRetriever(denseRetriever, bm25Retriever, 60, 5);

        List<Content> results = retriever.retrieve(Query.from("RLK"));

        assertEquals(1, results.size());
        assertEquals("chunk-1", results.get(0).textSegment().metadata().getString("chunk_id"));
    }

    private Content content(String chunkId, String text) {
        Metadata metadata = new Metadata().put("chunk_id", chunkId);
        return Content.from(TextSegment.from(text, metadata));
    }
}
