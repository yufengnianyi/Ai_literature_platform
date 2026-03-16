package com.example.demo_01.ai.integration;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PgVectorEmbeddingStoreIntegrationTest extends PostgresIntegrationTestSupport {

    @Test
    void shouldPersistAndSearchEmbeddingsInPgVector() {
        PgVectorEmbeddingStore store = PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("embedding_store")
                .dimension(1024)
                .createTable(false)
                .build();

        store.add(Embedding.from(vectorWithValue(0, 1.0f)), TextSegment.from("alpha"));
        store.add(Embedding.from(vectorWithValue(1, 1.0f)), TextSegment.from("beta"));

        EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(vectorWithValue(0, 1.0f)))
                .maxResults(1)
                .minScore(0.0)
                .build());

        assertFalse(result.matches().isEmpty());
        assertEquals("alpha", result.matches().get(0).embedded().text());
        Integer rowCount = jdbcTemplate.queryForObject("select count(*) from embedding_store", Integer.class);
        assertEquals(2, rowCount);
    }

    private float[] vectorWithValue(int index, float value) {
        float[] vector = new float[1024];
        vector[index] = value;
        return vector;
    }
}