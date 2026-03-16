package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.config.RagBootstrapMode;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagIngestionServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private EmbeddingModel quwenEmbeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    private JsonlLoader jsonlLoader;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RagIngestionService ragIngestionService;
    private AiPersistenceProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        Path docsFile = tempDir.resolve("sample.jsonl");
        Files.writeString(docsFile, "{\"text\":\"hello\"}\n");

        properties = new AiPersistenceProperties();
        properties.getRag().setDocsPath(tempDir.toString());
        properties.getRag().setVectorTable("embedding_store");
        properties.getRag().setEmbeddingDimension(1024);

        ragIngestionService = new RagIngestionService();
        ReflectionTestUtils.setField(ragIngestionService, "quwenEmbeddingModel", quwenEmbeddingModel);
        ReflectionTestUtils.setField(ragIngestionService, "embeddingStore", embeddingStore);
        ReflectionTestUtils.setField(ragIngestionService, "jsonlLoader", jsonlLoader);
        ReflectionTestUtils.setField(ragIngestionService, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(ragIngestionService, "properties", properties);
    }

    @Test
    void ingestShouldSkipWhenModeIsSkip() {
        properties.getRag().setDocsPath(tempDir.resolve("missing").toString());

        ragIngestionService.ingest(RagBootstrapMode.SKIP);

        verifyNoInteractions(jdbcTemplate, jsonlLoader);
    }

    @Test
    void ingestShouldRebuildWhenModeIsRebuild() {
        when(jsonlLoader.loadDirectory(any(Path.class))).thenReturn(List.of());

        ragIngestionService.ingest(RagBootstrapMode.REBUILD);

        verify(jdbcTemplate).execute("truncate table embedding_store");
        verify(jdbcTemplate).update(startsWith("delete from rag_ingestion_state"), eq("jsonl-docs"));
        verify(jsonlLoader).loadDirectory(Path.of(properties.getRag().getDocsPath()));
    }

    @Test
    void ingestShouldLoadDocumentsWhenIfEmptyAndTableIsEmpty() {
        when(jdbcTemplate.queryForObject("select count(*) from embedding_store", Long.class)).thenReturn(0L);
        when(jsonlLoader.loadDirectory(any(Path.class))).thenReturn(List.of());

        ragIngestionService.ingest(RagBootstrapMode.IF_EMPTY);

        verify(jsonlLoader).loadDirectory(Path.of(properties.getRag().getDocsPath()));
    }

    @Test
    void statusShouldReturnRowCountAndNullStateWhenStateMissing() {
        when(jdbcTemplate.queryForObject("select count(*) from embedding_store", Long.class)).thenReturn(7L);
        doReturn(List.of()).when(jdbcTemplate)
                .query(startsWith("select dataset_hash"), any(org.springframework.jdbc.core.RowMapper.class), eq("jsonl-docs"));

        RagIngestionService.RagIngestionStatus status = ragIngestionService.status();

        assertEquals(7L, status.rowCount());
        assertNull(status.datasetHash());
        assertNull(status.updatedAt());
    }
}