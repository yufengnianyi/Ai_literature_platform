package com.example.demo_01.ai.rag.repository;

import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RagIngestionJobRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    public void insert(UUID jobId, UUID documentId, UUID batchId, RagJobStatus status, RagIngestionStage stage, Long uploadMs) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into rag_ingestion_job (
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

    public Optional<RagIngestionJobRecord> findById(UUID jobId) {
        List<RagIngestionJobRecord> rows = jdbcTemplate.query("select * from rag_ingestion_job where job_id = ?", this::mapRow, jobId);
        return rows.stream().findFirst();
    }

    public void update(UUID jobId,
                       RagJobStatus status,
                       RagIngestionStage stage,
                       DuplicateReason duplicateReason,
                       String errorCode,
                       String errorMessage,
                       RagJobMetrics metrics,
                       Instant finishedAt) {
        jdbcTemplate.update("""
                update rag_ingestion_job
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
                    embed_ms = ?,
                    persist_ms = ?,
                    total_ms = ?,
                    chunk_count = ?,
                    estimated_tokens_total = ?,
                    provider_tokens_total = ?,
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
                metrics == null ? null : metrics.embedMs,
                metrics == null ? null : metrics.persistMs,
                metrics == null ? null : metrics.totalMs,
                metrics == null ? null : metrics.chunkCount,
                metrics == null ? null : metrics.estimatedTokensTotal,
                metrics == null ? null : metrics.providerTokensTotal,
                finishedAt == null ? null : Timestamp.from(finishedAt),
                Timestamp.from(Instant.now()),
                jobId);
    }

    private RagIngestionJobRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RagIngestionJobRecord(
                rs.getObject("job_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                RagJobStatus.valueOf(rs.getString("status")),
                RagIngestionStage.valueOf(rs.getString("stage")),
                rs.getString("duplicate_reason") == null ? null : DuplicateReason.valueOf(rs.getString("duplicate_reason")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                (Long) rs.getObject("upload_ms"),
                (Long) rs.getObject("header_ms"),
                (Long) rs.getObject("fulltext_ms"),
                (Long) rs.getObject("tei_parse_ms"),
                (Long) rs.getObject("jsonl_ms"),
                (Long) rs.getObject("embed_ms"),
                (Long) rs.getObject("persist_ms"),
                (Long) rs.getObject("total_ms"),
                (Integer) rs.getObject("chunk_count"),
                (Long) rs.getObject("estimated_tokens_total"),
                (Long) rs.getObject("provider_tokens_total"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
