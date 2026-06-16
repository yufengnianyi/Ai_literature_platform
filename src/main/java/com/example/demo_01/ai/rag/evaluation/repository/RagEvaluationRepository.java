package com.example.demo_01.ai.rag.evaluation.repository;

import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RagEvaluationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public void insertExperiment(UUID experimentId, String userId, String question,
                                 Map<String, Object> config, String reportRoot) {
        jdbcTemplate.update("""
                INSERT INTO rag_eval_experiment
                    (experiment_id, user_id, question, status, config_json, report_root, created_at, updated_at)
                VALUES (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                """,
                experimentId, userId, question, ExperimentStatus.QUEUED.name(),
                toJson(config == null ? Map.of() : config), reportRoot,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    public void updateStatus(UUID experimentId, ExperimentStatus status) {
        jdbcTemplate.update("""
                UPDATE rag_eval_experiment
                SET status = ?,
                    updated_at = ?,
                    error_code = CASE WHEN ? IN (?, ?) THEN NULL ELSE error_code END,
                    error_message = CASE WHEN ? IN (?, ?) THEN NULL ELSE error_message END,
                    finished_at = CASE WHEN ? IN (?, ?) THEN NULL ELSE finished_at END
                WHERE experiment_id = ?
                """,
                status.name(), Timestamp.from(Instant.now()),
                status.name(), ExperimentStatus.QUEUED.name(), ExperimentStatus.RUNNING.name(),
                status.name(), ExperimentStatus.QUEUED.name(), ExperimentStatus.RUNNING.name(),
                status.name(), ExperimentStatus.QUEUED.name(), ExperimentStatus.RUNNING.name(),
                experimentId);
    }

    public void completeExperiment(UUID experimentId, RagEvaluationMetrics metrics) {
        jdbcTemplate.update("""
                UPDATE rag_eval_experiment
                SET status = ?, metrics_json = cast(? as jsonb), finished_at = ?, updated_at = ?,
                    error_code = NULL, error_message = NULL
                WHERE experiment_id = ?
                """,
                ExperimentStatus.COMPLETED.name(), toJson(metrics),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), experimentId);
    }

    public void updateMetrics(UUID experimentId, RagEvaluationMetrics metrics) {
        jdbcTemplate.update("""
                UPDATE rag_eval_experiment
                SET metrics_json = cast(? as jsonb), updated_at = ?
                WHERE experiment_id = ?
                """, toJson(metrics), Timestamp.from(Instant.now()), experimentId);
    }

    public void mergeConfig(UUID experimentId, Map<String, Object> configPatch) {
        if (configPatch == null || configPatch.isEmpty()) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE rag_eval_experiment
                SET config_json = config_json || cast(? as jsonb),
                    updated_at = ?
                WHERE experiment_id = ?
                """, toJson(configPatch), Timestamp.from(Instant.now()), experimentId);
    }

    public void failExperiment(UUID experimentId, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE rag_eval_experiment
                SET status = ?, error_code = ?, error_message = ?, finished_at = ?, updated_at = ?
                WHERE experiment_id = ?
                """,
                ExperimentStatus.FAILED.name(), errorCode, truncate(errorMessage, 2000),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), experimentId);
    }

    public Optional<RagEvaluationExperimentRecord> findExperiment(UUID experimentId) {
        List<RagEvaluationExperimentRecord> rows = jdbcTemplate.query("""
                SELECT * FROM rag_eval_experiment WHERE experiment_id = ?
                """, this::mapExperiment, experimentId);
        return rows.stream().findFirst();
    }

    public List<DocumentForEvaluation> findCompletedDocuments() {
        return jdbcTemplate.query("""
                SELECT document_id, title
                FROM rag_document
                WHERE status = 'COMPLETED'
                  AND duplicate_of_document_id IS NULL
                ORDER BY updated_at DESC, document_id
                """, (rs, rowNum) -> new DocumentForEvaluation(
                rs.getObject("document_id", UUID.class),
                rs.getString("title")
        ));
    }

    public List<AntimicrobialDocument> findAntimicrobialSourceDocuments(UUID sourceExperimentId) {
        return jdbcTemplate.query("""
                SELECT j.document_id,
                       coalesce(r.title, j.document_title) AS document_title,
                       coalesce(r.authors_json::text, '[]') AS authors_json,
                       r.publication_year,
                       r.journal,
                       coalesce(r.doi_normalized, r.doi_raw) AS doi,
                       r.storage_root
                FROM rag_eval_document_judgment j
                LEFT JOIN rag_document r ON r.document_id = j.document_id
                WHERE j.experiment_id = ?
                ORDER BY j.id
                """, (rs, rowNum) -> new AntimicrobialDocument(
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                fromJsonList(rs.getString("authors_json")),
                (Integer) rs.getObject("publication_year"),
                rs.getString("journal"),
                rs.getString("doi"),
                rs.getString("storage_root")
        ), sourceExperimentId);
    }

    public void upsertAntimicrobialResult(AntimicrobialPaperResult result) {
        jdbcTemplate.update("""
                INSERT INTO rag_eval_antimicrobial_result
                    (experiment_id, document_id, document_title, status, relevant, chunk_count,
                     judgment_reason, output_path, error_message, started_at, finished_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (experiment_id, document_id) DO UPDATE
                SET document_title = EXCLUDED.document_title,
                    status = EXCLUDED.status,
                    relevant = EXCLUDED.relevant,
                    chunk_count = EXCLUDED.chunk_count,
                    judgment_reason = EXCLUDED.judgment_reason,
                    output_path = EXCLUDED.output_path,
                    error_message = EXCLUDED.error_message,
                    started_at = COALESCE(EXCLUDED.started_at, rag_eval_antimicrobial_result.started_at),
                    finished_at = EXCLUDED.finished_at,
                    updated_at = EXCLUDED.updated_at
                """,
                result.experimentId(),
                result.documentId(),
                result.documentTitle(),
                result.status().name(),
                result.relevant(),
                result.chunkCount(),
                result.judgmentReason(),
                result.outputPath(),
                truncate(result.errorMessage(), 2000),
                timestamp(result.startedAt()),
                timestamp(result.finishedAt()),
                timestamp(result.createdAt() == null ? Instant.now() : result.createdAt()),
                timestamp(result.updatedAt() == null ? Instant.now() : result.updatedAt()));
    }

    public List<AntimicrobialPaperResult> findAntimicrobialResults(UUID experimentId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM rag_eval_antimicrobial_result
                WHERE experiment_id = ?
                ORDER BY created_at, document_id
                """, (rs, rowNum) -> new AntimicrobialPaperResult(
                rs.getObject("experiment_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                AntimicrobialResultStatus.valueOf(rs.getString("status")),
                (Boolean) rs.getObject("relevant"),
                (Integer) rs.getObject("chunk_count"),
                rs.getString("judgment_reason"),
                rs.getString("output_path"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        ), experimentId);
    }

    public void upsertJudgment(RagEvaluationDocumentJudgment judgment) {
        jdbcTemplate.update("""
                INSERT INTO rag_eval_document_judgment
                    (experiment_id, document_id, document_title, llm_label, override_label, effective_label,
                     key_entities_json, key_chunk_ids_json, llm_reason, report_path, confidence,
                     override_note, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                ON CONFLICT (experiment_id, document_id) DO UPDATE
                SET document_title = EXCLUDED.document_title,
                    llm_label = EXCLUDED.llm_label,
                    effective_label = COALESCE(rag_eval_document_judgment.override_label, EXCLUDED.llm_label),
                    key_entities_json = EXCLUDED.key_entities_json,
                    key_chunk_ids_json = EXCLUDED.key_chunk_ids_json,
                    llm_reason = EXCLUDED.llm_reason,
                    report_path = EXCLUDED.report_path,
                    confidence = EXCLUDED.confidence,
                    updated_at = EXCLUDED.updated_at
                """,
                judgment.experimentId(),
                judgment.documentId(),
                judgment.documentTitle(),
                judgment.llmLabel().name(),
                judgment.overrideLabel() == null ? null : judgment.overrideLabel().name(),
                judgment.effectiveLabel().name(),
                toJson(judgment.keyEntities()),
                toJson(judgment.keyChunkIds()),
                judgment.llmReason(),
                judgment.reportPath(),
                judgment.confidence(),
                judgment.overrideNote(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
    }

    public List<RagEvaluationDocumentJudgment> findJudgments(UUID experimentId) {
        return jdbcTemplate.query("""
                SELECT * FROM rag_eval_document_judgment
                WHERE experiment_id = ?
                ORDER BY document_title NULLS LAST, document_id
                """, this::mapJudgment, experimentId);
    }

    public void updateOverride(UUID experimentId, UUID documentId, JudgmentLabel label,
                               List<String> keyChunkIds, String note) {
        jdbcTemplate.update("""
                UPDATE rag_eval_document_judgment
                SET override_label = ?,
                    effective_label = ?,
                    key_chunk_ids_json = CASE
                        WHEN ? IS NULL THEN key_chunk_ids_json
                        ELSE cast(? as jsonb)
                    END,
                    override_note = ?,
                    updated_at = ?
                WHERE experiment_id = ? AND document_id = ?
                """,
                label == null ? null : label.name(),
                label == null ? null : label.name(),
                keyChunkIds == null ? null : "present",
                keyChunkIds == null ? null : toJson(keyChunkIds),
                note,
                Timestamp.from(Instant.now()),
                experimentId,
                documentId);
    }

    public void deleteHits(UUID experimentId) {
        jdbcTemplate.update("DELETE FROM rag_eval_retrieval_hit WHERE experiment_id = ?", experimentId);
    }

    public void insertHits(UUID experimentId, List<RagEvaluationRetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (RagEvaluationRetrievalHit hit : hits) {
            jdbcTemplate.update("""
                    INSERT INTO rag_eval_retrieval_hit
                        (experiment_id, route, query, rank, document_id, chunk_id, score)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    experimentId,
                    hit.route().name(),
                    hit.query(),
                    hit.rank(),
                    hit.documentId(),
                    hit.chunkId(),
                    hit.score());
        }
    }

    public List<RagEvaluationRetrievalHit> findHits(UUID experimentId) {
        return jdbcTemplate.query("""
                SELECT * FROM rag_eval_retrieval_hit
                WHERE experiment_id = ?
                ORDER BY route, rank, id
                """, this::mapHit, experimentId);
    }

    private RagEvaluationExperimentRecord mapExperiment(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        return new RagEvaluationExperimentRecord(
                rs.getObject("experiment_id", UUID.class),
                rs.getString("user_id"),
                rs.getString("question"),
                ExperimentStatus.valueOf(rs.getString("status")),
                fromJsonMap(rs.getString("config_json")),
                fromJsonMetrics(rs.getString("metrics_json")),
                rs.getString("report_root"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant()
        );
    }

    private RagEvaluationDocumentJudgment mapJudgment(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        String overrideLabel = rs.getString("override_label");
        return new RagEvaluationDocumentJudgment(
                rs.getLong("id"),
                rs.getObject("experiment_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                JudgmentLabel.valueOf(rs.getString("llm_label")),
                overrideLabel == null ? null : JudgmentLabel.valueOf(overrideLabel),
                JudgmentLabel.valueOf(rs.getString("effective_label")),
                fromJsonList(rs.getString("key_entities_json")),
                fromJsonList(rs.getString("key_chunk_ids_json")),
                rs.getString("llm_reason"),
                rs.getString("report_path"),
                rs.getDouble("confidence"),
                rs.getString("override_note"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private RagEvaluationRetrievalHit mapHit(ResultSet rs, int rowNum) throws SQLException {
        return new RagEvaluationRetrievalHit(
                rs.getLong("id"),
                rs.getObject("experiment_id", UUID.class),
                RetrievalRoute.valueOf(rs.getString("route")),
                rs.getString("query"),
                rs.getInt("rank"),
                rs.getObject("document_id", UUID.class),
                rs.getString("chunk_id"),
                rs.getDouble("score")
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private RagEvaluationMetrics fromJsonMetrics(String json) {
        if (json == null || json.isBlank()) {
            return RagEvaluationMetrics.empty();
        }
        try {
            return objectMapper.readValue(json, RagEvaluationMetrics.class);
        } catch (JsonProcessingException e) {
            return RagEvaluationMetrics.empty();
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record DocumentForEvaluation(UUID documentId, String title) {
    }

    public record AntimicrobialDocument(
            UUID documentId,
            String title,
            List<String> authors,
            Integer publicationYear,
            String journal,
            String doi,
            String storageRoot
    ) {
    }
}
