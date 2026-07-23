package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MultiProfileEvidenceRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public List<SourceDocument> findSourceDocuments(UUID sourceExperimentId) {
        return jdbcTemplate.query("""
                SELECT j.document_id,
                       coalesce(d.title, j.document_title) AS document_title,
                       coalesce(d.authors_json::text, '[]') AS authors_json,
                       d.publication_year,
                       d.journal,
                       coalesce(d.doi_normalized, d.doi_raw) AS doi,
                       d.storage_root
                FROM rag_eval_document_judgment j
                JOIN rag_document d ON d.document_id = j.document_id
                WHERE j.experiment_id = ?
                  AND d.status = 'COMPLETED'
                  AND d.duplicate_of_document_id IS NULL
                ORDER BY j.id
                """, (rs, rowNum) -> new SourceDocument(
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                fromJsonList(rs.getString("authors_json")),
                (Integer) rs.getObject("publication_year"),
                rs.getString("journal"),
                rs.getString("doi"),
                rs.getString("storage_root")
        ), sourceExperimentId);
    }

    public Optional<BatchRecord> findActiveBatch(UUID sourceExperimentId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_multi_profile_batch
                WHERE source_experiment_id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at DESC
                LIMIT 1
                """, this::mapBatch, sourceExperimentId).stream().findFirst();
    }

    public List<BatchRecord> findRecoverableBatches() {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_multi_profile_batch
                WHERE status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at
                """, this::mapBatch);
    }

    public List<UUID> findDocumentsToProcess(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT document_id
                FROM evidence_multi_profile_document
                WHERE batch_id = ?
                  AND status IN ('PENDING', 'RUNNING', 'FAILED', 'PARTIAL_FAILED')
                ORDER BY created_at, document_id
                """, (rs, rowNum) -> rs.getObject("document_id", UUID.class), batchId);
    }

    public Optional<BatchRecord> findReusableBatch(UUID sourceExperimentId,
                                                   String sourceHash,
                                                   String profileVersion,
                                                   String promptHash,
                                                   String modelName) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_multi_profile_batch
                WHERE source_experiment_id = ?
                  AND source_hash = ?
                  AND profile_version = ?
                  AND prompt_hash = ?
                  AND coalesce(model_name, '') = coalesce(?, '')
                  AND status = 'COMPLETED'
                ORDER BY created_at DESC
                LIMIT 1
                """, this::mapBatch, sourceExperimentId, sourceHash, profileVersion, promptHash, modelName)
                .stream().findFirst();
    }

    @Transactional
    public void insertBatch(BatchRecord batch, List<SourceDocument> documents) {
        jdbcTemplate.update("""
                INSERT INTO evidence_multi_profile_batch (
                    batch_id, source_experiment_id, source_hash, profile_version,
                    prompt_hash, model_name, force, status, total_documents
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                """, batch.batchId(), batch.sourceExperimentId(), batch.sourceHash(),
                batch.profileVersion(), batch.promptHash(), batch.modelName(), batch.force(),
                batch.totalDocuments());
        for (SourceDocument document : documents) {
            jdbcTemplate.update("""
                    INSERT INTO evidence_multi_profile_document (
                        batch_id, document_id, document_title, status
                    ) VALUES (?, ?, ?, 'PENDING')
                    """, batch.batchId(), document.documentId(), document.title());
        }
    }

    public Optional<BatchRecord> findBatch(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_multi_profile_batch
                WHERE batch_id = ?
                """, this::mapBatch, batchId).stream().findFirst();
    }

    public void markBatchRunning(UUID batchId) {
        jdbcTemplate.update("""
                UPDATE evidence_multi_profile_batch
                SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
                    error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                """, batchId);
    }

    public void finishBatch(UUID batchId, String outputPath) {
        refreshBatchCounts(batchId);
        Integer failed = jdbcTemplate.queryForObject("""
                SELECT failed_profiles
                FROM evidence_multi_profile_batch
                WHERE batch_id = ?
                """, Integer.class, batchId);
        String status = failed != null && failed > 0 ? "PARTIAL_FAILED" : "COMPLETED";
        jdbcTemplate.update("""
                UPDATE evidence_multi_profile_batch
                SET status = ?, output_path = ?, finished_at = CURRENT_TIMESTAMP,
                    elapsed_ms = (extract(epoch from (CURRENT_TIMESTAMP - started_at)) * 1000)::bigint,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                """, status, outputPath, batchId);
    }

    public void failBatch(UUID batchId, String errorMessage) {
        refreshBatchCounts(batchId);
        jdbcTemplate.update("""
                UPDATE evidence_multi_profile_batch
                SET status = 'FAILED', error_message = ?, finished_at = CURRENT_TIMESTAMP,
                    elapsed_ms = (extract(epoch from (CURRENT_TIMESTAMP - started_at)) * 1000)::bigint,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                """, truncate(errorMessage, 4000), batchId);
    }

    public void refreshBatchCounts(UUID batchId) {
        jdbcTemplate.update("""
                UPDATE evidence_multi_profile_batch b
                SET processed_documents = (
                        SELECT count(*)::int
                        FROM evidence_multi_profile_document d
                        WHERE d.batch_id = b.batch_id
                          AND d.status NOT IN ('PENDING', 'RUNNING')
                    ),
                    supported_matches = (
                        SELECT count(*)::int
                        FROM evidence_document_question_match m
                        WHERE m.batch_id = b.batch_id
                          AND m.classification_status = 'SUPPORTED'
                    ),
                    uncertain_matches = (
                        SELECT count(*)::int
                        FROM evidence_document_question_match m
                        WHERE m.batch_id = b.batch_id
                          AND m.classification_status = 'UNCERTAIN'
                    ),
                    extracted_profiles = (
                        SELECT count(*)::int
                        FROM evidence_document_question_match m
                        WHERE m.batch_id = b.batch_id
                          AND m.extraction_status = 'COMPLETED'
                    ),
                    no_evidence_profiles = (
                        SELECT count(*)::int
                        FROM evidence_document_question_match m
                        WHERE m.batch_id = b.batch_id
                          AND m.extraction_status = 'NO_EVIDENCE'
                    ),
                    failed_profiles = (
                        SELECT count(*)::int
                        FROM evidence_document_question_match m
                        WHERE m.batch_id = b.batch_id
                          AND (
                            m.classification_status = 'FAILED'
                            OR m.extraction_status = 'FAILED'
                          )
                    ),
                    updated_at = CURRENT_TIMESTAMP
                WHERE b.batch_id = ?
                """, batchId);
    }

    public void markDocumentRunning(UUID batchId, UUID documentId) {
        jdbcTemplate.update("""
                UPDATE evidence_multi_profile_document
                SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
                    error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ? AND document_id = ?
                """, batchId, documentId);
    }

    public void finishDocument(UUID batchId, UUID documentId, DocumentStatus status,
                               Integer chunkCount, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE evidence_multi_profile_document
                SET status = ?, chunk_count = ?, error_message = ?,
                    finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ? AND document_id = ?
                """, status.name(), chunkCount, truncate(errorMessage, 4000), batchId, documentId);
    }

    public void upsertMatch(UUID batchId, UUID documentId, ClassifiedQuestion result) {
        ProfileExtractionStatus extractionStatus =
                result.status() == ClassificationStatus.SUPPORTED
                        || result.status() == ClassificationStatus.UNCERTAIN
                        ? ProfileExtractionStatus.QUEUED
                        : ProfileExtractionStatus.NOT_REQUESTED;
        jdbcTemplate.update("""
                INSERT INTO evidence_document_question_match (
                    batch_id, document_id, question_id, classification_status,
                    confidence, reason, evidence_chunk_ids, extraction_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (batch_id, document_id, question_id) DO UPDATE
                SET classification_status = EXCLUDED.classification_status,
                    confidence = EXCLUDED.confidence,
                    reason = EXCLUDED.reason,
                    evidence_chunk_ids = EXCLUDED.evidence_chunk_ids,
                    extraction_status = EXCLUDED.extraction_status,
                    evidence_count = 0,
                    output_path = NULL,
                    error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """, batchId, documentId, result.questionId(), result.status().name(),
                result.confidence(), result.reason(), toJson(result.chunkIds()), extractionStatus.name());
    }

    public void markExtractionRunning(UUID batchId, UUID documentId, String questionId) {
        jdbcTemplate.update("""
                UPDATE evidence_document_question_match
                SET extraction_status = 'RUNNING', error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ? AND document_id = ? AND question_id = ?
                """, batchId, documentId, questionId);
    }

    public void finishExtraction(UUID batchId, UUID documentId, String questionId,
                                 ProfileExtractionStatus status, int evidenceCount,
                                 String outputPath, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE evidence_document_question_match
                SET extraction_status = ?, evidence_count = ?, output_path = ?,
                    error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ? AND document_id = ? AND question_id = ?
                """, status.name(), evidenceCount, outputPath, truncate(errorMessage, 4000),
                batchId, documentId, questionId);
    }

    @Transactional
    public void replaceEvidence(UUID batchId, UUID documentId, String questionId,
                                String profileVersion, ClassificationStatus classificationStatus,
                                List<ValidatedEvidenceRow> rows) {
        jdbcTemplate.update("""
                DELETE FROM generic_evidence_record
                WHERE batch_id = ? AND document_id = ? AND question_id = ?
                """, batchId, documentId, questionId);
        jdbcTemplate.update("""
                UPDATE generic_evidence_record
                SET is_current = FALSE, updated_at = CURRENT_TIMESTAMP
                WHERE document_id = ? AND question_id = ? AND is_current = TRUE
                """, documentId, questionId);
        int rowIndex = 0;
        for (ValidatedEvidenceRow row : rows) {
            rowIndex++;
            jdbcTemplate.update("""
                    INSERT INTO generic_evidence_record (
                        record_id, batch_id, document_id, question_id, profile_version,
                        row_index, cells_json, row_fingerprint, classification_status,
                        validation_status, verification_note, review_status, is_current
                    ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'PENDING', TRUE)
                    """, row.recordId(), batchId, documentId, questionId, profileVersion,
                    rowIndex, toJson(row.cells()), row.fingerprint(), classificationStatus.name(),
                    (row.validationStatus() == null
                            ? ValidationStatus.VALID : row.validationStatus()).name(),
                    row.verificationNote());
            for (ValidatedAnchor anchor : row.anchors()) {
                jdbcTemplate.update("""
                        INSERT INTO generic_evidence_anchor (
                            record_id, chunk_id, section_path, paragraph_index,
                            sentence_start, sentence_end, exact_quote, quote_hash
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, row.recordId(), anchor.chunkId(), anchor.sectionPath(),
                        anchor.paragraphIndex(), anchor.sentenceStart(), anchor.sentenceEnd(),
                        anchor.exactQuote(), anchor.quoteHash());
            }
        }
    }

    public List<QuestionMatchRecord> findMatches(UUID batchId, UUID documentId) {
        return jdbcTemplate.query("""
                SELECT m.*, d.document_title
                FROM evidence_document_question_match m
                JOIN evidence_multi_profile_document d
                  ON d.batch_id = m.batch_id AND d.document_id = m.document_id
                WHERE m.batch_id = ? AND m.document_id = ?
                ORDER BY m.question_id
                """, this::mapMatch, batchId, documentId);
    }

    public DocumentPage findDocuments(UUID batchId, String questionId,
                                      ClassificationStatus classificationStatus,
                                      ProfileExtractionStatus extractionStatus,
                                      int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 200);
        List<Object> filters = new ArrayList<>();
        String where = documentFilter(questionId, classificationStatus, extractionStatus, filters);
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(batchId);
        countArgs.addAll(filters);
        Long total = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM evidence_multi_profile_document d
                WHERE d.batch_id = ?
                """ + where, Long.class, countArgs.toArray());
        List<Object> queryArgs = new ArrayList<>(countArgs);
        queryArgs.add(size);
        queryArgs.add(page * size);
        List<DocumentRecord> documents = jdbcTemplate.query("""
                SELECT d.*
                FROM evidence_multi_profile_document d
                WHERE d.batch_id = ?
                """ + where + """
                ORDER BY d.created_at, d.document_id
                LIMIT ? OFFSET ?
                """, this::mapDocument, queryArgs.toArray());
        List<DocumentResult> items = documents.stream()
                .map(document -> new DocumentResult(document,
                        findMatches(batchId, document.documentId())))
                .toList();
        return new DocumentPage(items, page, size, total == null ? 0 : total);
    }

    public EvidencePage findEvidence(UUID batchId, String questionId, UUID documentId,
                                     ReviewStatus reviewStatus, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 500);
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        StringBuilder where = new StringBuilder(" WHERE e.batch_id = ?");
        if (questionId != null && !questionId.isBlank()) {
            where.append(" AND e.question_id = ?");
            args.add(questionId);
        }
        if (documentId != null) {
            where.append(" AND e.document_id = ?");
            args.add(documentId);
        }
        if (reviewStatus != null) {
            where.append(" AND e.review_status = ?");
            args.add(reviewStatus.name());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM generic_evidence_record e" + where,
                Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add(page * size);
        List<GenericEvidenceRecord> items = jdbcTemplate.query("""
                SELECT e.*, d.document_title
                FROM generic_evidence_record e
                JOIN evidence_multi_profile_document d
                  ON d.batch_id = e.batch_id AND d.document_id = e.document_id
                """ + where + "\n" + """
                ORDER BY e.question_id, e.document_id, e.row_index
                LIMIT ? OFFSET ?
                """, this::mapEvidence, queryArgs.toArray());
        return new EvidencePage(items, page, size, total == null ? 0 : total);
    }

    public List<DocumentRecord> findAllDocuments(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_multi_profile_document
                WHERE batch_id = ?
                ORDER BY created_at, document_id
                """, this::mapDocument, batchId);
    }

    public List<QuestionMatchRecord> findAllMatches(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT m.*, d.document_title
                FROM evidence_document_question_match m
                JOIN evidence_multi_profile_document d
                  ON d.batch_id = m.batch_id AND d.document_id = m.document_id
                WHERE m.batch_id = ?
                ORDER BY m.document_id, m.question_id
                """, this::mapMatch, batchId);
    }

    public List<GenericEvidenceRecord> findAllEvidence(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT e.*, d.document_title
                FROM generic_evidence_record e
                JOIN evidence_multi_profile_document d
                  ON d.batch_id = e.batch_id AND d.document_id = e.document_id
                WHERE e.batch_id = ?
                ORDER BY e.question_id, e.document_id, e.row_index
                """, this::mapEvidence, batchId);
    }

    private String documentFilter(String questionId,
                                  ClassificationStatus classificationStatus,
                                  ProfileExtractionStatus extractionStatus,
                                  List<Object> args) {
        if ((questionId == null || questionId.isBlank())
                && classificationStatus == null && extractionStatus == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder("""
                 AND EXISTS (
                    SELECT 1
                    FROM evidence_document_question_match m
                    WHERE m.batch_id = d.batch_id
                      AND m.document_id = d.document_id
                """);
        if (questionId != null && !questionId.isBlank()) {
            sql.append(" AND m.question_id = ?");
            args.add(questionId);
        }
        if (classificationStatus != null) {
            sql.append(" AND m.classification_status = ?");
            args.add(classificationStatus.name());
        }
        if (extractionStatus != null) {
            sql.append(" AND m.extraction_status = ?");
            args.add(extractionStatus.name());
        }
        sql.append(")");
        return sql.toString();
    }

    private BatchRecord mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new BatchRecord(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("source_experiment_id", UUID.class),
                rs.getString("source_hash"),
                rs.getString("profile_version"),
                rs.getString("prompt_hash"),
                rs.getString("model_name"),
                rs.getBoolean("force"),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getInt("total_documents"),
                rs.getInt("processed_documents"),
                rs.getInt("supported_matches"),
                rs.getInt("uncertain_matches"),
                rs.getInt("extracted_profiles"),
                rs.getInt("no_evidence_profiles"),
                rs.getInt("failed_profiles"),
                rs.getString("output_path"),
                rs.getString("error_message"),
                (Long) rs.getObject("elapsed_ms"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private DocumentRecord mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentRecord(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                DocumentStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("chunk_count"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private QuestionMatchRecord mapMatch(ResultSet rs, int rowNum) throws SQLException {
        return new QuestionMatchRecord(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("question_id"),
                ClassificationStatus.valueOf(rs.getString("classification_status")),
                rs.getDouble("confidence"),
                rs.getString("reason"),
                fromJsonList(rs.getString("evidence_chunk_ids")),
                ProfileExtractionStatus.valueOf(rs.getString("extraction_status")),
                rs.getInt("evidence_count"),
                rs.getString("output_path"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private GenericEvidenceRecord mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        UUID recordId = rs.getObject("record_id", UUID.class);
        return new GenericEvidenceRecord(
                recordId,
                rs.getObject("batch_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("question_id"),
                rs.getString("profile_version"),
                rs.getInt("row_index"),
                fromJsonList(rs.getString("cells_json")),
                rs.getString("row_fingerprint"),
                ClassificationStatus.valueOf(rs.getString("classification_status")),
                ValidationStatus.valueOf(rs.getString("validation_status")),
                rs.getString("verification_note"),
                ReviewStatus.valueOf(rs.getString("review_status")),
                rs.getString("review_note"),
                rs.getBoolean("is_current"),
                findAnchors(recordId),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private List<ValidatedAnchor> findAnchors(UUID recordId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM generic_evidence_anchor
                WHERE record_id = ?
                ORDER BY anchor_id
                """, (rs, rowNum) -> new ValidatedAnchor(
                rs.getString("chunk_id"),
                rs.getString("section_path"),
                (Integer) rs.getObject("paragraph_index"),
                (Integer) rs.getObject("sentence_start"),
                (Integer) rs.getObject("sentence_end"),
                rs.getString("exact_quote"),
                rs.getString("quote_hash")
        ), recordId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize evidence JSON", e);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse evidence JSON", e);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record SourceDocument(
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
