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
public class RagIngestionBatchRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    public void insert(UUID batchId, String sourceFolder, RagBatchStatus status, int totalFiles) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into rag_ingestion_batch (
                    batch_id, source_folder, status, total_files, processed_files, completed_files, duplicate_files, failed_files,
                    chunk_count, estimated_tokens_total, provider_tokens_total, started_at, created_at, updated_at
                ) values (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, ?, ?, ?)
                """,
                batchId,
                sourceFolder,
                status.name(),
                totalFiles,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public Optional<RagIngestionBatchRecord> findById(UUID batchId) {
        List<RagIngestionBatchRecord> rows = jdbcTemplate.query(
                "select * from rag_ingestion_batch where batch_id = ?",
                this::mapRow,
                batchId);
        return rows.stream().findFirst();
    }

    public void update(UUID batchId, RagBatchStatus status, RagBatchMetrics metrics, Instant finishedAt) {
        jdbcTemplate.update("""
                update rag_ingestion_batch
                set status = ?,
                    total_files = ?,
                    processed_files = ?,
                    completed_files = ?,
                    duplicate_files = ?,
                    failed_files = ?,
                    chunk_count = ?,
                    estimated_tokens_total = ?,
                    provider_tokens_total = ?,
                    upload_ms = ?,
                    header_ms = ?,
                    fulltext_ms = ?,
                    tei_parse_ms = ?,
                    jsonl_ms = ?,
                    embed_ms = ?,
                    persist_ms = ?,
                    total_elapsed_ms = ?,
                    finished_at = ?,
                    updated_at = ?
                where batch_id = ?
                """,
                status.name(),
                metrics == null ? 0 : metrics.totalFiles,
                metrics == null ? 0 : metrics.processedFiles,
                metrics == null ? 0 : metrics.completedFiles,
                metrics == null ? 0 : metrics.duplicateFiles,
                metrics == null ? 0 : metrics.failedFiles,
                metrics == null ? 0 : metrics.chunkCount,
                metrics == null ? 0L : metrics.estimatedTokensTotal,
                metrics == null ? 0L : metrics.providerTokensTotal,
                metrics == null ? null : metrics.uploadMs,
                metrics == null ? null : metrics.headerMs,
                metrics == null ? null : metrics.fulltextMs,
                metrics == null ? null : metrics.teiParseMs,
                metrics == null ? null : metrics.jsonlMs,
                metrics == null ? null : metrics.embedMs,
                metrics == null ? null : metrics.persistMs,
                metrics == null ? null : metrics.totalElapsedMs,
                finishedAt == null ? null : Timestamp.from(finishedAt),
                Timestamp.from(Instant.now()),
                batchId);
    }

    private RagIngestionBatchRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RagIngestionBatchRecord(
                rs.getObject("batch_id", UUID.class),
                rs.getString("source_folder"),
                RagBatchStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("total_files"),
                (Integer) rs.getObject("processed_files"),
                (Integer) rs.getObject("completed_files"),
                (Integer) rs.getObject("duplicate_files"),
                (Integer) rs.getObject("failed_files"),
                (Integer) rs.getObject("chunk_count"),
                (Long) rs.getObject("estimated_tokens_total"),
                (Long) rs.getObject("provider_tokens_total"),
                (Long) rs.getObject("upload_ms"),
                (Long) rs.getObject("header_ms"),
                (Long) rs.getObject("fulltext_ms"),
                (Long) rs.getObject("tei_parse_ms"),
                (Long) rs.getObject("jsonl_ms"),
                (Long) rs.getObject("embed_ms"),
                (Long) rs.getObject("persist_ms"),
                (Long) rs.getObject("total_elapsed_ms"),
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
