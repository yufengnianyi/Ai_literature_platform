package com.example.demo_01.ai.evidence.repository;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.*;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedAnchor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Repository
public class EvidenceRepository {

    private static final Pattern SAFE_SQL_ID = Pattern.compile("[A-Za-z0-9_]+");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AiPersistenceProperties persistenceProperties;

    public Optional<UUID> findActiveRun(UUID documentId) {
        return jdbcTemplate.query("""
                SELECT run_id
                FROM evidence_extraction_run
                WHERE document_id = ? AND status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getObject("run_id", UUID.class), documentId)
                .stream().findFirst();
    }

    public Optional<ExtractionBatchRecord> findActiveBatch() {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_extraction_batch
                WHERE status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at DESC
                LIMIT 1
                """, this::mapBatch).stream().findFirst();
    }

    public void insertBatch(UUID batchId, boolean force, int totalDocuments) {
        jdbcTemplate.update("""
                INSERT INTO evidence_extraction_batch (
                    batch_id, status, force, total_documents
                ) VALUES (?, 'QUEUED', ?, ?)
                """, batchId, force, totalDocuments);
    }

    public void markBatchRunning(UUID batchId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE evidence_extraction_batch
                SET status = 'RUNNING', started_at = ?, updated_at = ?
                WHERE batch_id = ?
                """, Timestamp.from(now), Timestamp.from(now), batchId);
    }

    public void finishBatch(UUID batchId) {
        refreshBatchCounts(batchId);
        int failures = jdbcTemplate.queryForObject("""
                SELECT failed_documents
                FROM evidence_extraction_batch
                WHERE batch_id = ?
                """, Integer.class, batchId);
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE evidence_extraction_batch
                SET status = ?, finished_at = ?, elapsed_ms = extract(epoch from (? - started_at)) * 1000,
                    updated_at = ?
                WHERE batch_id = ?
                """, failures > 0 ? BatchStatus.PARTIAL_FAILED.name() : BatchStatus.COMPLETED.name(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), batchId);
    }

    public void failBatch(UUID batchId) {
        refreshBatchCounts(batchId);
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE evidence_extraction_batch
                SET status = 'FAILED', finished_at = ?,
                    elapsed_ms = extract(epoch from (? - started_at)) * 1000,
                    updated_at = ?
                WHERE batch_id = ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), batchId);
    }

    public void refreshBatchCounts(UUID batchId) {
        jdbcTemplate.update("""
                UPDATE evidence_extraction_batch b
                SET processed_documents = stats.processed,
                    skipped_documents = stats.skipped,
                    completed_documents = stats.completed,
                    no_evidence_documents = stats.no_evidence,
                    failed_documents = stats.failed,
                    updated_at = CURRENT_TIMESTAMP
                FROM (
                    SELECT
                        count(*) FILTER (WHERE status IN ('COMPLETED', 'NO_EVIDENCE', 'FAILED'))::int AS processed,
                        count(*) FILTER (WHERE skipped)::int AS skipped,
                        count(*) FILTER (WHERE status = 'COMPLETED')::int AS completed,
                        count(*) FILTER (WHERE status = 'NO_EVIDENCE')::int AS no_evidence,
                        count(*) FILTER (WHERE status = 'FAILED')::int AS failed
                    FROM evidence_extraction_run
                    WHERE batch_id = ?
                ) stats
                WHERE b.batch_id = ?
                """, batchId, batchId);
    }

    public UUID insertRun(UUID batchId,
                          UUID documentId,
                          String sourceHash,
                          String promptHash,
                          String modelName) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO evidence_extraction_run (
                    run_id, batch_id, document_id, profile_id, source_hash,
                    prompt_hash, model_name, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED')
                """, runId, batchId, documentId, com.example.demo_01.ai.evidence.model.EvidenceModels.PROFILE_ID,
                sourceHash, promptHash, modelName);
        return runId;
    }

    public UUID insertSkippedRun(UUID batchId, ExtractionRunRecord source) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO evidence_extraction_run (
                    run_id, batch_id, document_id, profile_id, source_hash, prompt_hash,
                    model_name, status, skipped, row_count, output_path,
                    started_at, finished_at, elapsed_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?, 0)
                """, runId, batchId, source.documentId(),
                com.example.demo_01.ai.evidence.model.EvidenceModels.PROFILE_ID,
                source.sourceHash(), source.promptHash(), source.modelName(), source.status().name(),
                source.rowCount(), source.outputPath(), Timestamp.from(now), Timestamp.from(now));
        return runId;
    }

    public void attachRunToBatch(UUID runId, UUID batchId) {
        jdbcTemplate.update("""
                UPDATE evidence_extraction_run
                SET batch_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND batch_id IS NULL
                """, batchId, runId);
    }

    public void markRunRunning(UUID runId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE evidence_extraction_run
                SET status = 'RUNNING', started_at = ?, error_code = NULL,
                    error_message = NULL, updated_at = ?
                WHERE run_id = ?
                """, Timestamp.from(now), Timestamp.from(now), runId);
    }

    public void completeRun(UUID runId, ExtractionStatus status, int rowCount, String outputPath) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE evidence_extraction_run
                SET status = ?, row_count = ?, output_path = ?, finished_at = ?,
                    elapsed_ms = extract(epoch from (? - started_at)) * 1000,
                    updated_at = ?
                WHERE run_id = ?
                """, status.name(), rowCount, outputPath, Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), runId);
    }

    public void failRun(UUID runId, String errorCode, String errorMessage) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE evidence_extraction_run
                SET status = 'FAILED', error_code = ?, error_message = ?, finished_at = ?,
                    elapsed_ms = extract(epoch from (? - started_at)) * 1000,
                    updated_at = ?
                WHERE run_id = ?
                """, errorCode, truncate(errorMessage, 4000), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), runId);
    }

    public Optional<ExtractionRunRecord> findRun(UUID runId) {
        return jdbcTemplate.query(runSelect() + " WHERE r.run_id = ?", this::mapRun, runId)
                .stream().findFirst();
    }

    public Optional<ExtractionRunRecord> findFreshRun(UUID documentId,
                                                      String sourceHash,
                                                      String promptHash) {
        return jdbcTemplate.query(runSelect() + """
                WHERE r.document_id = ?
                  AND r.status IN ('COMPLETED', 'NO_EVIDENCE')
                  AND coalesce(r.source_hash, '') = coalesce(?, '')
                  AND r.prompt_hash = ?
                ORDER BY r.created_at DESC
                LIMIT 1
                """, this::mapRun, documentId, sourceHash, promptHash).stream().findFirst();
    }

    public Optional<ExtractionBatchRecord> findBatch(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_extraction_batch
                WHERE batch_id = ?
                """, this::mapBatch, batchId).stream().findFirst();
    }

    public ExtractionRunPage findBatchRuns(UUID batchId, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 200);
        long total = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM evidence_extraction_run
                WHERE batch_id = ?
                """, Long.class, batchId);
        List<ExtractionRunRecord> items = jdbcTemplate.query(runSelect() + """
                WHERE r.batch_id = ?
                ORDER BY r.created_at, r.document_id
                LIMIT ? OFFSET ?
                """, this::mapRun, batchId, size, page * size);
        return new ExtractionRunPage(items, page, size, total);
    }

    public List<EvidenceChunk> findDocumentChunks(UUID documentId) {
        String table = persistenceProperties.getRag().getVectorTable();
        if (!SAFE_SQL_ID.matcher(table).matches()) {
            throw new IllegalStateException("Unsafe vector table name");
        }
        return jdbcTemplate.query("""
                SELECT coalesce(text, '') AS text, metadata::text AS metadata_json
                FROM %s
                WHERE metadata->>'document_id' = ?
                ORDER BY CASE
                    WHEN metadata->>'chunk_index' ~ '^[0-9]+$' THEN (metadata->>'chunk_index')::int
                    ELSE 2147483647
                END, embedding_id::text
                """.formatted(table), (rs, rowNum) -> {
            Map<String, Object> metadata = parseMap(rs.getString("metadata_json"));
            return new EvidenceChunk(
                    string(metadata.get("chunk_id")),
                    string(metadata.get("section_path")),
                    integer(metadata.get("paragraph_index")),
                    integer(metadata.get("sentence_start")),
                    integer(metadata.get("sentence_end")),
                    rs.getString("text"),
                    string(metadata.get("content_type")),
                    string(metadata.get("source_tei"))
            );
        }, documentId.toString());
    }

    public List<CompoundEvidenceRecord> findReportableEvidence(int requestedLimit) {
        int limit = Math.min(Math.max(1, requestedLimit), 100_000);
        return jdbcTemplate.query("""
                SELECT e.*, d.title AS document_title
                FROM compound_evidence e
                JOIN rag_document d ON d.document_id = e.document_id
                WHERE e.is_current = TRUE
                  AND e.validation_status = 'VALID'
                  AND e.review_status IN ('PENDING', 'APPROVED')
                ORDER BY CASE e.review_status WHEN 'APPROVED' THEN 0 ELSE 1 END,
                         e.model_confidence DESC NULLS LAST,
                         e.updated_at DESC,
                         e.evidence_id
                LIMIT ?
                """, this::mapEvidence, limit);
    }

    public List<CompoundEvidenceRecord> findReportableEvidence(UUID sourceExperimentId, int requestedLimit) {
        int limit = Math.min(Math.max(1, requestedLimit), 100_000);
        return jdbcTemplate.query("""
                SELECT e.*, d.title AS document_title
                FROM compound_evidence e
                JOIN evidence_extraction_run r ON r.run_id = e.run_id
                JOIN rag_document d ON d.document_id = e.document_id
                WHERE r.source_experiment_id = ?
                  AND e.is_current = TRUE
                  AND e.validation_status = 'VALID'
                  AND e.review_status IN ('PENDING', 'APPROVED')
                ORDER BY CASE e.review_status WHEN 'APPROVED' THEN 0 ELSE 1 END,
                         e.model_confidence DESC NULLS LAST,
                         e.updated_at DESC,
                         e.evidence_id
                LIMIT ?
                """, this::mapEvidence, sourceExperimentId, limit);
    }

    @Transactional
    public void replaceDocumentEvidence(UUID runId,
                                        UUID documentId,
                                        List<NormalizedEvidenceRow> rows) {
        List<UUID> evidenceIds = new java.util.ArrayList<>();
        List<List<ValidatedAnchor>> anchors = new java.util.ArrayList<>();
        for (int index = 0; index < (rows == null ? 0 : rows.size()); index++) {
            evidenceIds.add(UUID.randomUUID());
            anchors.add(List.of());
        }
        replaceDocumentEvidence(runId, documentId, rows, evidenceIds, anchors);
    }

    @Transactional
    public void replaceDocumentEvidence(UUID runId,
                                        UUID documentId,
                                        List<NormalizedEvidenceRow> rows,
                                        List<UUID> evidenceIds,
                                        List<List<ValidatedAnchor>> anchorsByRow) {
        jdbcTemplate.update("""
                UPDATE compound_evidence
                SET is_current = FALSE, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND is_current = TRUE
                """, documentId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (evidenceIds == null || anchorsByRow == null
                || evidenceIds.size() != rows.size() || anchorsByRow.size() != rows.size()) {
            throw new IllegalArgumentException("Projected evidence IDs and anchors must align with rows");
        }
        int rowIndex = 0;
        for (NormalizedEvidenceRow row : rows) {
            UUID evidenceId = evidenceIds.get(rowIndex);
            List<ValidatedAnchor> anchors = anchorsByRow.get(rowIndex);
            rowIndex++;
            jdbcTemplate.update("""
                    INSERT INTO compound_evidence (
                        evidence_id, run_id, document_id, row_index,
                        compound_original_name, compound_standard_name, structure_type,
                        source_category, source_description, oomycete_scientific_name,
                        assay_method, activity_data, positive_control, target_or_mechanism,
                        target_validation_method, cytotoxicity, resistance_cross_resistance,
                        synergy, reference_text, patent_information,
                        raw_row_json, row_fingerprint, name_kind, dedup_key,
                        model_confidence, validation_status, validation_warnings,
                        review_status, is_current
                    ) VALUES (
                        ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?::jsonb, ?, ?, ?,
                        NULL, 'VALID', '[]'::jsonb,
                        'PENDING', TRUE
                    )
                    """,
                    evidenceId,
                    runId,
                    documentId,
                    rowIndex,
                    nullIfBlank(row.row().compoundOriginalName()),
                    nullIfBlank(row.row().compoundStandardName()),
                    nullIfBlank(row.row().structureType()),
                    nullIfBlank(row.row().sourceCategory()),
                    nullIfBlank(row.row().sourceDescription()),
                    nullIfBlank(row.row().oomyceteScientificName()),
                    nullIfBlank(row.row().assayMethod()),
                    nullIfBlank(row.row().activityData()),
                    nullIfBlank(row.row().positiveControl()),
                    nullIfBlank(row.row().targetOrMechanism()),
                    nullIfBlank(row.row().targetValidationMethod()),
                    nullIfBlank(row.row().cytotoxicity()),
                    nullIfBlank(row.row().resistanceCrossResistance()),
                    nullIfBlank(row.row().synergy()),
                    nullIfBlank(row.row().referenceText()),
                    nullIfBlank(row.row().patentInformation()),
                    toJson(row.row().cells()),
                    row.rowFingerprint(),
                    row.nameKind().name(),
                    row.dedupKey());
            for (ValidatedAnchor anchor : anchors) {
                jdbcTemplate.update("""
                        INSERT INTO evidence_anchor (
                            evidence_id, chunk_id, section_path, paragraph_index,
                            sentence_start, sentence_end, page_start, page_end,
                            exact_quote, quote_hash
                        ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
                        ON CONFLICT (evidence_id, chunk_id, quote_hash) DO NOTHING
                        """, evidenceId, anchor.chunkId(), anchor.sectionPath(),
                        anchor.paragraphIndex(), anchor.sentenceStart(), anchor.sentenceEnd(),
                        anchor.exactQuote(), anchor.quoteHash());
            }
        }
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String toJson(List<String> cells) {
        try {
            return objectMapper.writeValueAsString(cells);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize evidence row", e);
        }
    }

    public Optional<CompoundEvidenceRecord> findEvidence(UUID evidenceId) {
        return jdbcTemplate.query("""
                SELECT e.*, d.title AS document_title
                FROM compound_evidence e
                JOIN rag_document d ON d.document_id = e.document_id
                WHERE e.evidence_id = ?
                """, this::mapEvidence, evidenceId).stream().findFirst();
    }

    private NameKind nameKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return NameKind.valueOf(value);
    }

    private String runSelect() {
        return """
                SELECT r.*, d.title AS document_title
                FROM evidence_extraction_run r
                JOIN rag_document d ON d.document_id = r.document_id
                """;
    }

    private ExtractionRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new ExtractionRunRecord(
                rs.getObject("run_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                ExtractionStatus.valueOf(rs.getString("status")),
                rs.getBoolean("skipped"),
                rs.getInt("row_count"),
                rs.getString("output_path"),
                rs.getString("source_hash"),
                rs.getString("prompt_hash"),
                rs.getString("model_name"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                (Long) rs.getObject("elapsed_ms"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private CompoundEvidenceRecord mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        UUID evidenceId = rs.getObject("evidence_id", UUID.class);
        CompoundEvidenceRow row = new CompoundEvidenceRow(
                rs.getString("compound_original_name"),
                rs.getString("compound_standard_name"),
                rs.getString("structure_type"),
                rs.getString("source_category"),
                rs.getString("source_description"),
                rs.getString("oomycete_scientific_name"),
                rs.getString("assay_method"),
                rs.getString("activity_data"),
                rs.getString("positive_control"),
                rs.getString("target_or_mechanism"),
                rs.getString("target_validation_method"),
                rs.getString("cytotoxicity"),
                rs.getString("resistance_cross_resistance"),
                rs.getString("synergy"),
                rs.getString("reference_text"),
                rs.getString("patent_information")
        );
        return new CompoundEvidenceRecord(
                evidenceId,
                rs.getObject("run_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getInt("row_index"),
                row,
                rs.getString("row_fingerprint"),
                nameKind(rs.getString("name_kind")),
                rs.getString("dedup_key"),
                (Double) rs.getObject("model_confidence"),
                ValidationStatus.valueOf(rs.getString("validation_status")),
                fromJsonList(rs.getString("validation_warnings")),
                ReviewStatus.valueOf(rs.getString("review_status")),
                rs.getString("review_note"),
                rs.getBoolean("is_current"),
                findAnchors(evidenceId),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private List<EvidenceAnchorRecord> findAnchors(UUID evidenceId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_anchor
                WHERE evidence_id = ?
                ORDER BY anchor_id
                """, (rs, rowNum) -> new EvidenceAnchorRecord(
                rs.getLong("anchor_id"),
                rs.getObject("evidence_id", UUID.class),
                rs.getString("chunk_id"),
                rs.getString("section_path"),
                (Integer) rs.getObject("paragraph_index"),
                (Integer) rs.getObject("sentence_start"),
                (Integer) rs.getObject("sentence_end"),
                (Integer) rs.getObject("page_start"),
                (Integer) rs.getObject("page_end"),
                rs.getString("exact_quote")
        ), evidenceId);
    }

    private ExtractionBatchRecord mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new ExtractionBatchRecord(
                rs.getObject("batch_id", UUID.class),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getBoolean("force"),
                rs.getInt("total_documents"),
                rs.getInt("processed_documents"),
                rs.getInt("skipped_documents"),
                rs.getInt("completed_documents"),
                rs.getInt("no_evidence_documents"),
                rs.getInt("failed_documents"),
                (Long) rs.getObject("elapsed_ms"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private Map<String, Object> parseMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse chunk metadata", e);
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

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
