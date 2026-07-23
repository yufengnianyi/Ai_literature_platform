package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentResult;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentMode;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunStatus;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    public String configJson(PretreatmentProperties properties) {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("artifactRoot", properties.getArtifactRoot());
            config.put("outputRoot", properties.getOutputRoot());
            config.put("promptPath", properties.getPromptPath());
            config.put("maxDocuments", properties.getMaxDocuments());
            config.put("representativeChunks", properties.getRepresentativeChunks());
            config.put("maxLlmInputChars", properties.getMaxLlmInputChars());
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

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
