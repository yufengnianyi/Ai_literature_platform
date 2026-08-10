package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmLabel;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentPage;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentResult;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentMode;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunRecord;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunStatus;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PretreatmentRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
            new TypeReference<>() {
            };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public void insertRun(UUID runId, PretreatmentMode mode, String configJson, String outputDir, Instant startedAt) {
        jdbcTemplate.update("""
                insert into pretreatment_run (
                    run_id, mode, status, config_json, output_dir, started_at, created_at, updated_at
                ) values (?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                """,
                runId,
                mode.name(),
                PretreatmentRunStatus.RUNNING.name(),
                configJson,
                outputDir,
                Timestamp.from(startedAt),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt));
    }

    public void completeRun(PretreatmentRunSummary summary) {
        jdbcTemplate.update("""
                update pretreatment_run
                set status = ?,
                    total_artifacts = ?,
                    processed_documents = ?,
                    accepted_documents = ?,
                    rejected_documents = ?,
                    uncertain_documents = ?,
                    skipped_documents = ?,
                    vectors_removed = ?,
                    dry_run = ?,
                    finished_at = ?,
                    updated_at = ?
                where run_id = ?
                """,
                PretreatmentRunStatus.COMPLETED.name(),
                summary.totalArtifacts(),
                summary.processedDocuments(),
                summary.acceptedDocuments(),
                summary.rejectedDocuments(),
                summary.uncertainDocuments(),
                summary.skippedDocuments(),
                summary.vectorsRemoved(),
                summary.dryRun(),
                Timestamp.from(summary.finishedAt()),
                Timestamp.from(summary.finishedAt()),
                summary.runId());
    }

    public void setCohorts(UUID runId, UUID acceptedCohortId, UUID rejectedCohortId) {
        jdbcTemplate.update("""
                update pretreatment_run
                set accepted_cohort_id = ?,
                    rejected_cohort_id = ?,
                    updated_at = ?
                where run_id = ?
                """, acceptedCohortId, rejectedCohortId,
                Timestamp.from(Instant.now()), runId);
    }

    public void failRun(UUID runId, String errorCode, String errorMessage) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                update pretreatment_run
                set status = ?,
                    error_code = ?,
                    error_message = ?,
                    finished_at = ?,
                    updated_at = ?
                where run_id = ?
                """,
                PretreatmentRunStatus.FAILED.name(),
                errorCode,
                errorMessage,
                Timestamp.from(now),
                Timestamp.from(now),
                runId);
    }

    public void insertResult(PretreatmentDocumentResult result) {
        jdbcTemplate.update("""
                insert into pretreatment_document_result (
                    run_id, document_id, storage_dir, title, journal, doi,
                    quality_decision, quality_metrics_json,
                    llm_label, final_decision, reject_reason_code,
                    taxa_json, research_focus, evidence_chunk_ids_json, reason, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?,
                    cast(? as jsonb), ?, cast(? as jsonb), ?, ?)
                """,
                result.runId(),
                result.documentId(),
                result.storageDir(),
                result.title(),
                result.journal(),
                result.doi(),
                name(result.qualityDecision()),
                toJson(result.qualityMetrics()),
                name(result.llmLabel()),
                name(result.finalDecision()),
                result.rejectReasonCode(),
                toJson(result.taxa()),
                result.researchFocus(),
                toJson(result.evidenceChunkIds()),
                result.reason(),
                Timestamp.from(Instant.now()));
    }

    public PretreatmentRunRecord findRun(UUID runId) {
        return jdbcTemplate.query("""
                select *
                from pretreatment_run
                where run_id = ?
                """, this::mapRun, runId).stream().findFirst().orElse(null);
    }

    public PretreatmentDocumentPage findDocuments(UUID runId, FinalDecision finalDecision,
                                                  int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 200);
        List<Object> args = new java.util.ArrayList<>();
        args.add(runId);
        String where = " where run_id = ?";
        if (finalDecision != null) {
            where += " and final_decision = ?";
            args.add(finalDecision.name());
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from pretreatment_document_result" + where,
                Long.class, args.toArray());
        List<Object> queryArgs = new java.util.ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add(page * size);
        List<PretreatmentDocumentResult> items = jdbcTemplate.query("""
                select *
                from pretreatment_document_result
                """ + where + """
                order by id
                limit ? offset ?
                """, this::mapDocument, queryArgs.toArray());
        return new PretreatmentDocumentPage(items, page, size, total == null ? 0 : total);
    }

    public List<UUID> findDocumentIds(UUID runId, FinalDecision finalDecision) {
        return jdbcTemplate.query("""
                select document_id
                from pretreatment_document_result
                where run_id = ?
                  and final_decision = ?
                  and document_id is not null
                order by id
                """, (rs, rowNum) -> rs.getObject("document_id", UUID.class),
                runId, finalDecision.name());
    }

    private PretreatmentRunRecord mapRun(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PretreatmentRunRecord(
                rs.getObject("run_id", UUID.class),
                PretreatmentMode.valueOf(rs.getString("mode")),
                PretreatmentRunStatus.valueOf(rs.getString("status")),
                rs.getString("output_dir"),
                rs.getInt("total_artifacts"),
                rs.getInt("processed_documents"),
                rs.getInt("accepted_documents"),
                rs.getInt("rejected_documents"),
                rs.getInt("uncertain_documents"),
                rs.getInt("skipped_documents"),
                rs.getInt("vectors_removed"),
                rs.getBoolean("dry_run"),
                rs.getObject("accepted_cohort_id", UUID.class),
                rs.getObject("rejected_cohort_id", UUID.class),
                rs.getString("error_code"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private PretreatmentDocumentResult mapDocument(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        String quality = rs.getString("quality_decision");
        String llm = rs.getString("llm_label");
        return new PretreatmentDocumentResult(
                rs.getObject("run_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("storage_dir"),
                rs.getString("title"),
                rs.getString("journal"),
                rs.getString("doi"),
                quality == null ? null : QualityDecision.valueOf(quality),
                fromJsonMap(rs.getString("quality_metrics_json")),
                llm == null ? null : LlmLabel.valueOf(llm),
                FinalDecision.valueOf(rs.getString("final_decision")),
                rs.getString("reject_reason_code"),
                fromJsonList(rs.getString("taxa_json")),
                rs.getString("research_focus"),
                fromJsonList(rs.getString("evidence_chunk_ids_json")),
                rs.getString("reason")
        );
    }

    public String configJson(PretreatmentProperties properties) {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("artifactRoot", properties.getArtifactRoot());
            config.put("outputRoot", properties.getOutputRoot());
            config.put("promptPath", properties.getPromptPath());
            config.put("maxDocuments", properties.getMaxDocuments());
            config.put("llmMaxAttempts", properties.getLlmMaxAttempts());
            config.put("qualityMinChunks", properties.getQuality().getMinChunks());
            config.put("qualityMinTotalTextChars", properties.getQuality().getMinTotalTextChars());
            config.put("qualityMaxReplacementCharRatio", properties.getQuality().getMaxReplacementCharRatio());
            config.put("qualityMaxShortLineRatio", properties.getQuality().getMaxShortLineRatio());
            config.put("cliMode", properties.getCli().getMode());
            config.put("dryRun", properties.getCli().isDryRun());
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PreTreatment config", e);
        }
    }

    private String toJson(Object values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PreTreatment values", e);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse PreTreatment list", e);
        }
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_OBJECT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse PreTreatment metrics", e);
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
