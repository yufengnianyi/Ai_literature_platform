package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.chunk.TeiChunker;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexEntry;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexService;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagVectorIngestionServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    private TokenCountEstimator tokenCountEstimator;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TeiChunker teiChunker;

    @Mock
    private Bm25IndexService bm25IndexService;

    private RagVectorIngestionService service;

    @BeforeEach
    void setUp() {
        service = new RagVectorIngestionService();
        ReflectionTestUtils.setField(service, "quwenEmbeddingModel", embeddingModel);
        ReflectionTestUtils.setField(service, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(service, "tokenCountEstimator", tokenCountEstimator);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "teiChunker", teiChunker);
        ReflectionTestUtils.setField(service, "bm25IndexService", bm25IndexService);
        ReflectionTestUtils.setField(service, "embeddingModelName", "test-embedding-model");

        AiPersistenceProperties properties = new AiPersistenceProperties();
        properties.getRag().setVectorTable("embedding_store");
        ReflectionTestUtils.setField(service, "properties", properties);
    }

    @Test
    void ingestChunksShouldPersistBm25EntriesAfterEmbeddings() {
        UUID documentId = UUID.randomUUID();
        var chunk = new com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk(
                documentId,
                "doi:10.1/test",
                "10.1/test",
                documentId + ":1",
                1,
                "body",
                "Introduction",
                1,
                1,
                2,
                "Paper",
                "Chunk text",
                "source.pdf",
                "source.tei",
                "v1"
        );

        when(teiChunker.composeEmbeddingText("Paper", "Introduction", "Chunk text"))
                .thenReturn("Paper: Paper\nSection: Introduction\nChunk text");
        when(teiChunker.deterministicEmbeddingId(chunk.chunkId())).thenReturn("embedding-1");
        when(tokenCountEstimator.estimateTokenCountInText(any())).thenReturn(12);
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(
                List.of(Embedding.from(new float[]{1.0f, 0.0f})),
                new TokenUsage(12)
        ));

        service.ingestChunks(List.of(chunk));

        verify(embeddingStore).addAll(eq(List.of("embedding-1")), anyList(), anyList());
        ArgumentCaptor<List<Bm25IndexEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(bm25IndexService).index(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("embedding-1", captor.getValue().get(0).id());
        assertEquals(chunk.chunkId(), captor.getValue().get(0).chunkId());
    }

    @Test
    void removeDocumentShouldDeleteVectorRowsAndBm25Entries() {
        UUID documentId = UUID.randomUUID();

        service.removeDocument(documentId);

        verify(jdbcTemplate).update("delete from embedding_store where metadata ->> 'document_id' = ?", documentId.toString());
        verify(bm25IndexService).removeByDocumentId(documentId.toString());
    }

    @Test
    void ingestChunksShouldSurfaceBm25FailuresForPipelineRollback() {
        UUID documentId = UUID.randomUUID();
        var chunk = new com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk(
                documentId,
                "doi:10.1/test",
                "10.1/test",
                documentId + ":1",
                1,
                "body",
                "Introduction",
                1,
                1,
                2,
                "Paper",
                "Chunk text",
                "source.pdf",
                "source.tei",
                "v1"
        );

        when(teiChunker.composeEmbeddingText("Paper", "Introduction", "Chunk text"))
                .thenReturn("Paper: Paper\nSection: Introduction\nChunk text");
        when(teiChunker.deterministicEmbeddingId(chunk.chunkId())).thenReturn("embedding-1");
        when(tokenCountEstimator.estimateTokenCountInText(any())).thenReturn(12);
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(
                List.of(Embedding.from(new float[]{1.0f, 0.0f})),
                new TokenUsage(12)
        ));
        doThrow(new IllegalStateException("bm25 failure")).when(bm25IndexService).index(anyList());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.ingestChunks(List.of(chunk)));

        assertEquals("bm25 failure", thrown.getMessage());
    }
}
