package com.example.demo_01.ai.rag.retrieval;

import com.example.demo_01.ai.rag.RagRetrievalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class Bm25ContentRetrieverTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRetrieveMatchesFromTitleSectionAndText() {
        EmbeddingStoreTextRepository repository = Mockito.mock(EmbeddingStoreTextRepository.class);
        when(repository.countRows()).thenReturn(0L);
        when(repository.fetchAll()).thenReturn(List.of());

        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setBm25IndexPath(tempDir.resolve("bm25-index").toString());
        ObjectMapper objectMapper = new ObjectMapper();
        Bm25IndexService indexService = new Bm25IndexService(repository, properties, objectMapper);
        indexService.bootstrapIfNeeded();
        indexService.index(List.of(
                entry("row-1", "doc-1", "chunk-1", "Phylogenetic analysis", "Introduction", "RLK family expansion in oomycetes"),
                entry("row-2", "doc-2", "chunk-2", "Other paper", "Methods", "Genome-wide survey of kinase domains")
        ));

        Bm25ContentRetriever retriever = new Bm25ContentRetriever(indexService, objectMapper, 5);

        List<Content> titleHits = retriever.retrieve(Query.from("Phylogenetic"));
        List<Content> sectionHits = retriever.retrieve(Query.from("Introduction"));
        List<Content> textHits = retriever.retrieve(Query.from("oomycetes"));

        assertEquals("chunk-1", titleHits.get(0).textSegment().metadata().getString("chunk_id"));
        assertEquals("chunk-1", sectionHits.get(0).textSegment().metadata().getString("chunk_id"));
        assertEquals("chunk-1", textHits.get(0).textSegment().metadata().getString("chunk_id"));
    }

    @Test
    void shouldReturnEmptyForNullQuery() {
        EmbeddingStoreTextRepository repository = Mockito.mock(EmbeddingStoreTextRepository.class);
        when(repository.countRows()).thenReturn(0L);
        when(repository.fetchAll()).thenReturn(List.of());

        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setBm25IndexPath(tempDir.resolve("blank-index").toString());
        Bm25IndexService indexService = new Bm25IndexService(repository, properties, new ObjectMapper());
        indexService.bootstrapIfNeeded();

        Bm25ContentRetriever retriever = new Bm25ContentRetriever(indexService, new ObjectMapper(), 5);

        assertTrue(retriever.retrieve(null).isEmpty());
    }

    private Bm25IndexEntry entry(String id,
                                 String documentId,
                                 String chunkId,
                                 String title,
                                 String sectionPath,
                                 String text) {
        Metadata metadata = new Metadata()
                .put("document_id", documentId)
                .put("chunk_id", chunkId)
                .put("title", title)
                .put("section_path", sectionPath);
        return new Bm25IndexEntry(id, documentId, chunkId, title, sectionPath, text, metadata);
    }
}
