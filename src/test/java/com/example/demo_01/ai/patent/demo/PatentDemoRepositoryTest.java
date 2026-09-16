package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.ExistingRagDocument;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatentDemoRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistsWhitelistAsParameterizedJsonbConfig() {
        UUID runId = UUID.randomUUID();
        doAnswer(invocation -> {
            assertThat(invocation.<String>getArgument(0)).contains("config_json", "?::jsonb");
            Object[] args = (Object[]) invocation.getRawArguments()[1];
            assertThat(args[0]).isEqualTo(runId);
            var config = objectMapper.readTree((String) args[9]);
            assertThat(config.path("publicationNumbers").size()).isEqualTo(1);
            assertThat(config.path("publicationNumbers").get(0).asText()).isEqualTo("CN106857590B");
            return 1;
        }).when(jdbcTemplate).update(anyString(), any(Object[].class));

        repository().insertRun(runId, "manifest.csv", "pdf", "cat", 1, 1L, true, true,
                List.of("CN106857590B"));
    }

    @Test
    void readsLiveQuestionRunStatusAndCountsValidCurrentRowsInThatRunOnly() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(anyString())).thenReturn(null);
        when(rs.getObject(anyString(), eq(UUID.class))).thenReturn(null);
        when(rs.getObject("run_id", UUID.class)).thenReturn(runId);
        when(rs.getObject("extraction_run_id", UUID.class)).thenReturn(extractionId);
        when(rs.getString(anyString())).thenReturn(null);
        when(rs.getInt(anyString())).thenReturn(0);
        when(rs.getString("status")).thenReturn("COMPLETED");
        when(rs.getString("config_json")).thenReturn("{\"publicationNumbers\":[\"CN106857590B\"]}");
        when(rs.getString("extraction_status")).thenReturn("RUNNING", "COMPLETED", "FAILED");
        when(rs.getInt("live_evidence_rows")).thenReturn(5);
        when(rs.getInt("valid_evidence_rows")).thenReturn(2);
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PatentDemoRunRecord>>any(), eq(runId)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    assertThat(sql).contains("LEFT JOIN evidence_question_extraction_run q ON q.run_id = r.extraction_run_id",
                            "COALESCE(q.evidence_rows, 0) AS live_evidence_rows", "COALESCE(q.status, 'UNKNOWN')",
                            "FROM generic_evidence_record e", "e.extraction_run_id = q.run_id",
                            "e.question_id = q.question_id", "e.is_current = TRUE", "e.validation_status = 'VALID'");
                    assertThat(sql).doesNotContain("evidence_extraction_run", "review_status = 'VALID'");
                    RowMapper<PatentDemoRunRecord> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var repository = repository();
        for (String expectedStatus : List.of("RUNNING", "COMPLETED", "FAILED")) {
            var run = repository.findRun(runId).orElseThrow();
            assertThat(run.status()).isEqualTo(PatentDemoRunStatus.COMPLETED);
            assertThat(run.extractionStatus()).isEqualTo(expectedStatus);
            assertThat(run.evidenceRows()).isEqualTo(5);
            assertThat(run.validEvidenceRows()).isEqualTo(2);
            assertThat(run.publicationNumbers()).containsExactly("CN106857590B");
        }
    }

    @Test
    void legacyConfigAndNoExtractionReturnEmptyWhitelistAndNoExtractionStatus() throws Exception {
        UUID runId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenReturn(null);
        when(rs.getString("status")).thenReturn("COMPLETED");
        when(rs.getString("config_json")).thenReturn("{}");
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PatentDemoRunRecord>>any(), eq(runId)))
                .thenAnswer(invocation -> {
                    RowMapper<PatentDemoRunRecord> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var run = repository().findRun(runId).orElseThrow();

        assertThat(run.publicationNumbers()).isEmpty();
        assertThat(run.extractionStatus()).isNull();
        assertThat(run.validEvidenceRows()).isZero();
        assertThat(run.evidenceRows()).isZero();
    }

    @Test
    void evidenceCountUsesQuestionExtractionRunTableAndMissingRunReturnsZero() {
        UUID extractionId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(extractionId)))
                .thenAnswer(invocation -> {
                    assertThat(invocation.<String>getArgument(0)).contains("FROM evidence_question_extraction_run", "WHERE run_id = ?")
                            .doesNotContain("FROM evidence_extraction_run");
                    return 7;
                }).thenThrow(new EmptyResultDataAccessException(1));
        var repository = repository();
        assertThat(repository.findExtractionEvidenceRows(null)).isZero();
        verifyNoInteractions(jdbcTemplate);
        assertThat(repository.findExtractionEvidenceRows(extractionId)).isEqualTo(7);
        assertThat(repository.findExtractionEvidenceRows(extractionId)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = RagDocumentStatus.class, names = {"COMPLETED", "FAILED", "PROCESSING", "QUEUED"})
    void canonicalLookupIncludesPartialStatesExcludesDuplicatesAndRecoversLegacyLinks(RagDocumentStatus status) throws Exception {
        UUID documentId = UUID.randomUUID();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("document_id", UUID.class)).thenReturn(documentId);
        when(rs.getString("storage_root")).thenReturn("old/patent-demo/run/CN106857590B");
        when(rs.getString("status")).thenReturn(status.name());
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ExistingRagDocument>>any(),
                eq("patent:CN106857590B"), eq("CN106857590B"), eq("CN106857590B"), eq("CN106857590B"), eq("patent:CN106857590B")))
                .thenAnswer(invocation -> {
                    assertThat(invocation.<String>getArgument(0)).contains(
                            "d.duplicate_of_document_id IS NULL", "d.status <> 'DUPLICATE_SKIPPED'",
                            "d.canonical_key = ?", "d.canonical_key IS NULL", "p.document_id = d.document_id",
                            "p.publication_number = ?", "'%/patent-demo/%'", "d.created_at, d.document_id")
                            .doesNotContain("AND d.status = 'COMPLETED'", "pdf_sha256 =");
                    RowMapper<ExistingRagDocument> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var document = repository().findCanonicalRagDocumentByPublicationNumber("CN106857590B").orElseThrow();

        assertThat(document.documentId()).isEqualTo(documentId);
        assertThat(document.status()).isEqualTo(status);
    }

    private PatentDemoRepository repository() {
        return new PatentDemoRepository(jdbcTemplate, new AiPersistenceProperties(), objectMapper);
    }
}
