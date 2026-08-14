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

    /** Inserts a per-document run stub used as compound_evidence provenance for Q1 mirror writes. */
    public UUID insertRun(UUID batchId,
                          UUID documentId,
                          String sourceHash,
                          String promptHash,
                          String modelName) {
        jdbcTemplate.update("""
                UPDATE evidence_extraction_run
                SET status = 'FAILED',
                    error_message = 'Superseded by a new Q1 mirror run',
                    finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                """, documentId);
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

    /**
     * Returns all current, reportable compound-evidence rows for one document.
     * Used by chat-time LOCAL_LABEL / NATURAL_EXTRACT name resolution to reconstruct
     * the per-paper evidence table without relying on on-disk Markdown paths.
     */
    public List<CompoundEvidenceRecord> findByDocumentId(UUID documentId) {
        if (documentId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT e.*, d.title AS document_title
                FROM compound_evidence e
                JOIN rag_document d ON d.document_id = e.document_id
                WHERE e.document_id = ?
                  AND e.is_current = TRUE
                  AND e.validation_status = 'VALID'
                  AND e.review_status IN ('PENDING', 'APPROVED')
                ORDER BY e.row_index ASC, e.evidence_id
                """, this::mapEvidence, documentId);
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

}
