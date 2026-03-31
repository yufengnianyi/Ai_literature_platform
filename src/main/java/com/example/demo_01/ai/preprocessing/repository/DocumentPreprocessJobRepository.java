package com.example.demo_01.ai.preprocessing.repository;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessJobMetrics;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessJobRecord;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStage;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.DuplicateReason;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentPreprocessJobRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    public void insert(UUID jobId, UUID documentId, UUID batchId, PreprocessStatus status, PreprocessStage stage, Long uploadMs) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into document_preprocess_job (
                    job_id, document_id, batch_id, status, stage, upload_ms, started_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                jobId,
                documentId,
                batchId,
                status.name(),
                stage.name(),
                uploadMs,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public Optional<PreprocessJobRecord> findById(UUID jobId) {
        List<PreprocessJobRecord> rows = jdbcTemplate.query("select * from document_preprocess_job where job_id = ?", (rs, rowNum) -> new PreprocessJobRecord(
                rs.getObject("job_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                PreprocessStatus.valueOf(rs.getString("status")),
                PreprocessStage.valueOf(rs.getString("stage")),
                rs.getString("duplicate_reason") == null ? null : DuplicateReason.valueOf(rs.getString("duplicate_reason")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                (Long) rs.getObject("upload_ms"),
                (Long) rs.getObject("header_ms"),
                (Long) rs.getObject("fulltext_ms"),
                (Long) rs.getObject("tei_parse_ms"),
                (Long) rs.getObject("jsonl_ms"),
                (Long) rs.getObject("total_ms"),
                (Integer) rs.getObject("chunk_count"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()
        ), jobId);
        return rows.stream().findFirst();
    }

    public void update(UUID jobId,
                       PreprocessStatus status,
                       PreprocessStage stage,
                       DuplicateReason duplicateReason,
                       String errorCode,
                       String errorMessage,
                       PreprocessJobMetrics metrics,
                       Instant finishedAt) {
        jdbcTemplate.update("""
                update document_preprocess_job
                set status = ?,
                    stage = ?,
                    duplicate_reason = ?,
                    error_code = ?,
                    error_message = ?,
                    upload_ms = ?,
                    header_ms = ?,
                    fulltext_ms = ?,
                    tei_parse_ms = ?,
                    jsonl_ms = ?,
                    total_ms = ?,
                    chunk_count = ?,
                    finished_at = ?,
                    updated_at = ?
                where job_id = ?
                """,
                status.name(),
                stage.name(),
                duplicateReason == null ? null : duplicateReason.name(),
                errorCode,
                errorMessage,
                metrics == null ? null : metrics.uploadMs,
                metrics == null ? null : metrics.headerMs,
                metrics == null ? null : metrics.fulltextMs,
                metrics == null ? null : metrics.teiParseMs,
                metrics == null ? null : metrics.jsonlMs,
                metrics == null ? null : metrics.totalMs,
                metrics == null ? null : metrics.chunkCount,
                finishedAt == null ? null : Timestamp.from(finishedAt),
                Timestamp.from(Instant.now()),
                jobId);
    }
}
