package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.BatchStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ClassificationStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.EvidencePage;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.GenericEvidenceRecord;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedAnchor;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidationStatus;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionDocumentStatus;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunDocument;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunDocumentPage;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunRecord;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionSourceType;
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
public class QuestionExtractionRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ClassificationStatus>> STATUS_LIST =
            new TypeReference<>() {
            };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public Optional<ExtractionRunRecord> findActiveRun(String questionId, String inputHash) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_question_extraction_run
                WHERE question_id = ?
                  AND input_hash = ?
                  AND status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at DESC
                LIMIT 1
                """, this::mapRun, questionId, inputHash).stream().findFirst();
    }

    public Optional<ExtractionRunRecord> findReusableRun(String questionId,
                                                         String inputHash,
                                                         String configHash,
                                                         String modelName) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_question_extraction_run
                WHERE question_id = ?
                  AND input_hash = ?
                  AND config_hash = ?
                  AND coalesce(model_name, '') = coalesce(?, '')
                  AND status = 'COMPLETED'
                ORDER BY created_at DESC
                LIMIT 1
                """, this::mapRun, questionId, inputHash, configHash, modelName)
                .stream().findFirst();
    }

    public List<ExtractionRunRecord> findRecoverableRuns() {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_question_extraction_run
                WHERE status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at
                """, this::mapRun);
    }

    public List<UUID> findDocumentsToProcess(UUID runId) {
        return jdbcTemplate.query("""
                SELECT document_id
                FROM evidence_question_extraction_document
                WHERE run_id = ?
                  AND status IN ('PENDING', 'RUNNING', 'FAILED')
                ORDER BY created_at, document_id
                """, (rs, rowNum) -> rs.getObject("document_id", UUID.class), runId);
    }

    @Transactional
    public void insertRun(ExtractionRunRecord run,
                          List<QuestionExtractionModels.ExtractionCandidate> candidates) {
        jdbcTemplate.update("""
                INSERT INTO evidence_question_extraction_run (
                    run_id, question_id, label, source_type, classification_batch_id,
                    source_experiment_id, cohort_id, include_statuses_json, profile_version,
                    input_hash, config_hash, config_snapshot_json, model_name, force,
                    status, total_documents
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, 'QUEUED', ?)
                """, run.runId(), run.questionId(), run.label(), run.sourceType().name(),
                run.classificationBatchId(), run.sourceExperimentId(),
                run.cohortId(),
                toJson(run.includeStatuses().stream().map(Enum::name).toList()),
                run.profileVersion(), run.inputHash(), run.configHash(),
                run.configSnapshotJson() == null ? "{}" : run.configSnapshotJson(),
                run.modelName(), run.force(), run.totalDocuments());
        for (QuestionExtractionModels.ExtractionCandidate candidate : candidates) {
            jdbcTemplate.update("""
                    INSERT INTO evidence_question_extraction_document (
                        run_id, document_id, document_title, classification_status, status
                    ) VALUES (?, ?, ?, ?, 'PENDING')
                    """, run.runId(), candidate.document().documentId(),
                    candidate.document().title(),
                    candidate.classificationStatus() == null
                            ? null : candidate.classificationStatus().name());
        }
    }

    public Optional<ExtractionRunRecord> findRun(UUID runId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_question_extraction_run
                WHERE run_id = ?
                """, this::mapRun, runId).stream().findFirst();
    }

    public void markRunRunning(UUID runId) {
        jdbcTemplate.update("""
                UPDATE evidence_question_extraction_run
                SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
                    error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, runId);
    }

    public void finishRun(UUID runId, String outputPath) {
        refreshRunCounts(runId);
        Integer failed = jdbcTemplate.queryForObject("""
                SELECT failed_documents
                FROM evidence_question_extraction_run
                WHERE run_id = ?
                """, Integer.class, runId);
        String status = failed != null && failed > 0 ? "PARTIAL_FAILED" : "COMPLETED";
        jdbcTemplate.update("""
                UPDATE evidence_question_extraction_run
                SET status = ?, output_path = ?, finished_at = CURRENT_TIMESTAMP,
                    elapsed_ms = (extract(epoch from (CURRENT_TIMESTAMP - started_at)) * 1000)::bigint,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, status, outputPath, runId);
    }

    public void failRun(UUID runId, String errorMessage) {
        refreshRunCounts(runId);
        jdbcTemplate.update("""
                UPDATE evidence_question_extraction_run
                SET status = 'FAILED', error_message = ?, finished_at = CURRENT_TIMESTAMP,
                    elapsed_ms = (extract(epoch from (CURRENT_TIMESTAMP - started_at)) * 1000)::bigint,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, truncate(errorMessage, 4000), runId);
    }

    public void refreshRunCounts(UUID runId) {
        jdbcTemplate.update("""
                UPDATE evidence_question_extraction_run r
                SET processed_documents = (
                        SELECT count(*)::int
                        FROM evidence_question_extraction_document d
                        WHERE d.run_id = r.run_id
                          AND d.status NOT IN ('PENDING', 'RUNNING')
                    ),
                    completed_documents = (
                        SELECT count(*)::int
                        FROM evidence_question_extraction_document d
                        WHERE d.run_id = r.run_id AND d.status = 'COMPLETED'
                    ),
                    no_evidence_documents = (
                        SELECT count(*)::int
                        FROM evidence_question_extraction_document d
                        WHERE d.run_id = r.run_id AND d.status = 'NO_EVIDENCE'
                    ),
                    failed_documents = (
                        SELECT count(*)::int
                        FROM evidence_question_extraction_document d
                        WHERE d.run_id = r.run_id AND d.status = 'FAILED'
                    ),
                    evidence_rows = (
                        SELECT count(*)::int
                        FROM generic_evidence_record e
                        WHERE e.extraction_run_id = r.run_id
                    ),
                    updated_at = CURRENT_TIMESTAMP
                WHERE r.run_id = ?
                """, runId);
    }

    public void markDocumentRunning(UUID runId, UUID documentId) {
        jdbcTemplate.update("""
                UPDATE evidence_question_extraction_document
                SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
                    error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND document_id = ?
                """, runId, documentId);
    }

    public void finishDocument(UUID runId, UUID documentId, ExtractionDocumentStatus status,
                               Integer chunkCount, int rowCount, String outputPath,
                               String errorMessage) {
        jdbcTemplate.update("""
                UPDATE evidence_question_extraction_document
                SET status = ?, chunk_count = ?, row_count = ?, output_path = ?,
                    error_message = ?, finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP,
                    elapsed_ms = CASE
                        WHEN started_at IS NULL THEN NULL
                        ELSE (extract(epoch from (CURRENT_TIMESTAMP - started_at)) * 1000)::bigint
                    END
                WHERE run_id = ? AND document_id = ?
                """, status.name(), chunkCount, rowCount, outputPath,
                truncate(errorMessage, 4000), runId, documentId);
    }

    public ExtractionRunDocumentPage findDocuments(UUID runId, ExtractionDocumentStatus status,
                                                   int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 200);
        List<Object> args = new ArrayList<>();
        args.add(runId);
        String where = "";
        if (status != null) {
            where = " AND status = ?";
            args.add(status.name());
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM evidence_question_extraction_document WHERE run_id = ?"
                        + where,
                Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(args);
        queryArgs.add(size);
        queryArgs.add(page * size);
        List<ExtractionRunDocument> items = jdbcTemplate.query("""
                SELECT *
                FROM evidence_question_extraction_document
                WHERE run_id = ?
                """ + where + """
                ORDER BY created_at, document_id
                LIMIT ? OFFSET ?
                """, this::mapDocument, queryArgs.toArray());
        return new ExtractionRunDocumentPage(items, page, size, total == null ? 0 : total);
    }

    public EvidencePage findEvidence(UUID runId, UUID documentId, ReviewStatus reviewStatus,
                                     int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(Math.max(1, requestedSize), 500);
        List<Object> args = new ArrayList<>();
        args.add(runId);
        StringBuilder where = new StringBuilder(" WHERE e.extraction_run_id = ?");
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
                SELECT e.*, coalesce(d.document_title, rd.title) AS document_title
                FROM generic_evidence_record e
                LEFT JOIN evidence_question_extraction_document d
                  ON d.run_id = e.extraction_run_id AND d.document_id = e.document_id
                LEFT JOIN rag_document rd ON rd.document_id = e.document_id
                """ + where + """
                ORDER BY e.document_id, e.row_index
                LIMIT ? OFFSET ?
                """, this::mapEvidence, queryArgs.toArray());
        return new EvidencePage(items, page, size, total == null ? 0 : total);
    }

    private ExtractionRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new ExtractionRunRecord(
                rs.getObject("run_id", UUID.class),
                rs.getString("question_id"),
                rs.getString("label"),
                ExtractionSourceType.valueOf(rs.getString("source_type")),
                rs.getObject("classification_batch_id", UUID.class),
                rs.getObject("source_experiment_id", UUID.class),
                rs.getObject("cohort_id", UUID.class),
                parseStatuses(rs.getString("include_statuses_json")),
                rs.getString("profile_version"),
                rs.getString("input_hash"),
                rs.getString("config_hash"),
                rs.getString("config_snapshot_json"),
                rs.getString("model_name"),
                rs.getBoolean("force"),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getInt("total_documents"),
                rs.getInt("processed_documents"),
                rs.getInt("completed_documents"),
                rs.getInt("no_evidence_documents"),
                rs.getInt("failed_documents"),
                rs.getInt("evidence_rows"),
                rs.getString("output_path"),
                rs.getString("error_message"),
                (Long) rs.getObject("elapsed_ms"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private ExtractionRunDocument mapDocument(ResultSet rs, int rowNum) throws SQLException {
        String classification = rs.getString("classification_status");
        return new ExtractionRunDocument(
                rs.getObject("run_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                classification == null ? null : ClassificationStatus.valueOf(classification),
                ExtractionDocumentStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("chunk_count"),
                rs.getInt("row_count"),
                (Long) rs.getObject("elapsed_ms"),
                rs.getString("output_path"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at"))
        );
    }

    private GenericEvidenceRecord mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        UUID recordId = rs.getObject("record_id", UUID.class);
        return new GenericEvidenceRecord(
                recordId,
                rs.getObject("batch_id", UUID.class),
                rs.getObject("extraction_run_id", UUID.class),
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

    private List<ClassificationStatus> parseStatuses(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> names = objectMapper.readValue(json, STRING_LIST);
            return names.stream().map(ClassificationStatus::valueOf).toList();
        } catch (JsonProcessingException e) {
            try {
                return objectMapper.readValue(json, STATUS_LIST);
            } catch (JsonProcessingException nested) {
                throw new IllegalStateException("Failed to parse include statuses", nested);
            }
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize extraction JSON", e);
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
}
