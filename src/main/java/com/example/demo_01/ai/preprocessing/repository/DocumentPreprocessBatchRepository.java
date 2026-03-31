package com.example.demo_01.ai.preprocessing.repository;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchMetrics;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchRecord;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchStatus;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentPreprocessBatchRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    public void insert(UUID batchId, String sourceFolder, PreprocessBatchStatus status, int totalFiles) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into document_preprocess_batch (
                    batch_id, source_folder, status, total_files, processed_files, completed_files, duplicate_files, failed_files,
                    chunk_count, started_at, created_at, updated_at
                ) values (?, ?, ?, ?, 0, 0, 0, 0, 0, ?, ?, ?)
                """,
                batchId,
                sourceFolder,
                status.name(),
                totalFiles,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public Optional<PreprocessBatchRecord> findById(UUID batchId) {
        List<PreprocessBatchRecord> rows = jdbcTemplate.query("select * from document_preprocess_batch where batch_id = ?", (rs, rowNum) -> new PreprocessBatchRecord(
                rs.getObject("batch_id", UUID.class),
                rs.getString("source_folder"),
                PreprocessBatchStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("total_files"),
                (Integer) rs.getObject("processed_files"),
                (Integer) rs.getObject("completed_files"),
                (Integer) rs.getObject("duplicate_files"),
                (Integer) rs.getObject("failed_files"),
                (Integer) rs.getObject("chunk_count"),
                (Long) rs.getObject("upload_ms"),
                (Long) rs.getObject("header_ms"),
                (Long) rs.getObject("fulltext_ms"),
                (Long) rs.getObject("tei_parse_ms"),
                (Long) rs.getObject("jsonl_ms"),
                (Long) rs.getObject("total_elapsed_ms"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()
        ), batchId);
        return rows.stream().findFirst();
    }

    public void update(UUID batchId, PreprocessBatchStatus status, PreprocessBatchMetrics metrics, Instant finishedAt) {
        jdbcTemplate.update("""
                update document_preprocess_batch
                set status = ?,
                    total_files = ?,
                    processed_files = ?,
                    completed_files = ?,
                    duplicate_files = ?,
                    failed_files = ?,
                    chunk_count = ?,
                    upload_ms = ?,
                    header_ms = ?,
                    fulltext_ms = ?,
                    tei_parse_ms = ?,
                    jsonl_ms = ?,
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
                metrics == null ? null : metrics.uploadMs,
                metrics == null ? null : metrics.headerMs,
                metrics == null ? null : metrics.fulltextMs,
                metrics == null ? null : metrics.teiParseMs,
                metrics == null ? null : metrics.jsonlMs,
                metrics == null ? null : metrics.totalElapsedMs,
                finishedAt == null ? null : Timestamp.from(finishedAt),
                Timestamp.from(Instant.now()),
                batchId);
    }
}
