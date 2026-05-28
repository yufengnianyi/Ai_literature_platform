package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatsResponse;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagDocumentRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ResultSet resultSet;

    private RagDocumentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RagDocumentRepository();
        ReflectionTestUtils.setField(repository, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void getStatsShouldMapDocumentCounts() throws Exception {
        when(resultSet.getLong("total_documents")).thenReturn(12L);
        when(resultSet.getLong("canonical_completed_documents")).thenReturn(8L);
        when(resultSet.getLong("processing_documents")).thenReturn(2L);
        when(resultSet.getLong("duplicate_documents")).thenReturn(1L);
        when(resultSet.getLong("failed_documents")).thenReturn(1L);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<RagDocumentStatsResponse> mapper = invocation.getArgument(1);
            return mapper.mapRow(resultSet, 0);
        });

        RagDocumentStatsResponse stats = repository.getStats();

        assertEquals(12L, stats.totalDocuments());
        assertEquals(8L, stats.canonicalCompletedDocuments());
        assertEquals(2L, stats.processingDocuments());
        assertEquals(1L, stats.duplicateDocuments());
        assertEquals(1L, stats.failedDocuments());
    }
}
