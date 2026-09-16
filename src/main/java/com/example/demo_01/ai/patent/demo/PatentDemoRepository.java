package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.ExistingRagDocument;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoDocumentPage;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoDocumentRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoDocumentStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTextLayerStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.TextLayerReport;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Repository
public class PatentDemoRepository {

    private static final Pattern SAFE_SQL_ID = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate jdbcTemplate;
    private final AiPersistenceProperties properties;
    private final ObjectMapper objectMapper;

    public PatentDemoRepository(JdbcTemplate jdbcTemplate, AiPersistenceProperties properties,
                                ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void insertRun(UUID runId,
                          String manifestPath,
                          String pdfRoot,
                          String category,
                          int requestedLimit,
                          Long seed,
                          boolean runExtraction,
                          boolean force) {
        insertRun(runId, manifestPath, pdfRoot, category, requestedLimit, seed, runExtraction,
                force, List.of(), "legacy", "balanced", "rules");
    }

    public void insertRun(UUID runId, String manifestPath, String pdfRoot, String category,
                          int requestedLimit, Long seed, boolean runExtraction, boolean force,
                          List<String> publicationNumbers) {
        insertRun(runId, manifestPath, pdfRoot, category, requestedLimit, seed, runExtraction,
                force, publicationNumbers, "legacy", "balanced", "rules");
    }

    public void insertRun(UUID runId, String manifestPath, String pdfRoot, String category,
                          int requestedLimit, Long seed, boolean runExtraction, boolean force,
                          List<String> publicationNumbers, String strategyVersion,
                          String discoveryMode, String subjectMode) {
        String configJson;
        try {
            var config = objectMapper.createObjectNode();
            config.set("publicationNumbers", objectMapper.valueToTree(publicationNumbers));
            config.put("strategyVersion", strategyVersion);
            config.put("discoveryMode", discoveryMode);
            config.put("subjectMode", subjectMode);
            configJson = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize patent demo run config", e);
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO patent_demo_run (
                    run_id, status, manifest_path, pdf_root, category, requested_limit, seed,
                    run_extraction, force, config_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                runId,
                PatentDemoRunStatus.QUEUED.name(),
                manifestPath,
                pdfRoot,
                category,
                requestedLimit,
                seed,
                runExtraction,
                force,
                configJson,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public void markRunRunning(UUID runId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE patent_demo_run
                SET status = ?, started_at = COALESCE(started_at, ?), updated_at = ?
                WHERE run_id = ?
                """, PatentDemoRunStatus.RUNNING.name(), Timestamp.from(now), Timestamp.from(now), runId);
    }

    public void updateRunProgress(UUID runId,
                                  int totalCandidates,
                                  int textLayerPassed,
                                  int ingestedDocuments,
                                  int skippedDocuments,
                                  int failedDocuments,
                                  UUID cohortId,
                                  UUID extractionRunId,
                                  Integer evidenceRows) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                UPDATE patent_demo_run
                SET total_candidates = ?,
                    text_layer_passed = ?,
                    ingested_documents = ?,
                    skipped_documents = ?,
                    failed_documents = ?
                """);
        args.add(totalCandidates);
        args.add(textLayerPassed);
        args.add(ingestedDocuments);
        args.add(skippedDocuments);
        args.add(failedDocuments);
        if (cohortId != null) {
            sql.append(", cohort_id = ?");
            args.add(cohortId);
        }
        if (extractionRunId != null) {
            sql.append(", extraction_run_id = ?");
            args.add(extractionRunId);
        }
        if (evidenceRows != null) {
            sql.append(", evidence_rows = ?");
            args.add(evidenceRows);
        }
        sql.append(", updated_at = ? WHERE run_id = ?");
        args.add(Timestamp.from(Instant.now()));
        args.add(runId);
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    public void finishRun(UUID runId, PatentDemoRunStatus status, String errorMessage) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE patent_demo_run
                SET status = ?, error_message = ?, finished_at = ?, updated_at = ?
                WHERE run_id = ?
                """, status.name(), errorMessage, Timestamp.from(now), Timestamp.from(now), runId);
    }

    public Optional<PatentDemoRunRecord> findRun(UUID runId) {
        return jdbcTemplate.query("""
                SELECT r.*,
                       COALESCE(q.evidence_rows, 0) AS live_evidence_rows,
                       CASE WHEN r.extraction_run_id IS NULL THEN NULL
                            ELSE COALESCE(q.status, 'UNKNOWN') END AS extraction_status,
                       (SELECT count(*)::int FROM generic_evidence_record e
                        WHERE e.extraction_run_id = q.run_id
                          AND e.question_id = q.question_id
                          AND e.is_current = TRUE
                          AND e.validation_status = 'VALID') AS valid_evidence_rows,
                       CASE
                           WHEN r.started_at IS NULL THEN NULL
                           ELSE (EXTRACT(EPOCH FROM (COALESCE(r.finished_at, CURRENT_TIMESTAMP) - r.started_at)) * 1000)::BIGINT
                       END AS elapsed_ms
                FROM patent_demo_run r
                LEFT JOIN evidence_question_extraction_run q ON q.run_id = r.extraction_run_id
                WHERE r.run_id = ?
                """, this::mapRun, runId).stream().findFirst();
    }

    public void insertDocument(UUID runId, PatentCandidate candidate) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO patent_demo_document (
                    run_id, publication_number, title, pdf_path, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, publication_number) DO UPDATE
                SET title = EXCLUDED.title,
                    pdf_path = EXCLUDED.pdf_path,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                runId,
                candidate.publicationNumber(),
                candidate.title(),
                candidate.pdfPath().toString(),
                PatentDemoDocumentStatus.PENDING.name(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public void markDocumentRunning(UUID runId, String publicationNumber) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE patent_demo_document
                SET status = ?, started_at = COALESCE(started_at, ?), updated_at = ?
                WHERE run_id = ? AND publication_number = ?
                """, PatentDemoDocumentStatus.RUNNING.name(), Timestamp.from(now), Timestamp.from(now),
                runId, publicationNumber);
    }

    public void updateTextLayer(UUID runId, String publicationNumber, TextLayerReport report) {
        jdbcTemplate.update("""
                UPDATE patent_demo_document
                SET text_layer_status = ?,
                    page_count = ?,
                    file_size_bytes = ?,
                    sampled_text_chars = ?,
                    usable_page_ratio = ?,
                    replacement_char_ratio = ?,
                    updated_at = ?
                WHERE run_id = ? AND publication_number = ?
                """,
                report.status().name(),
                report.pageCount(),
                report.fileSizeBytes(),
                report.sampledTextChars(),
                report.usablePageRatio(),
                report.replacementCharRatio(),
                Timestamp.from(Instant.now()),
                runId,
                publicationNumber);
    }

    public void markDocumentSkipped(UUID runId,
                                    String publicationNumber,
                                    String reason,
                                    Integer selectedPageCount) {
        finishDocument(runId, publicationNumber, PatentDemoDocumentStatus.SKIPPED,
                selectedPageCount, null, null, reason, null);
    }

    public void markDocumentFailed(UUID runId, String publicationNumber, String errorMessage) {
        finishDocument(runId, publicationNumber, PatentDemoDocumentStatus.FAILED,
                null, null, null, null, errorMessage);
    }

    public void markDocumentFailed(UUID runId,
                                   String publicationNumber,
                                   UUID documentId,
                                   int selectedPageCount,
                                   int chunkCount,
                                   String errorMessage) {
        finishDocument(runId, publicationNumber, PatentDemoDocumentStatus.FAILED,
                selectedPageCount, chunkCount, documentId, null, errorMessage);
    }

    public void markDocumentIngested(UUID runId,
                                     String publicationNumber,
                                     UUID documentId,
                                     int selectedPageCount,
                                     int chunkCount) {
        finishDocument(runId, publicationNumber, PatentDemoDocumentStatus.INGESTED,
                selectedPageCount, chunkCount, documentId, null, null);
    }

    private void finishDocument(UUID runId,
                                String publicationNumber,
                                PatentDemoDocumentStatus status,
                                Integer selectedPageCount,
                                Integer chunkCount,
                                UUID documentId,
                                String skipReason,
                                String errorMessage) {
        Instant now = Instant.now();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                UPDATE patent_demo_document
                SET status = ?
                """);
        args.add(status.name());
        if (selectedPageCount != null) {
            sql.append(", selected_page_count = ?");
            args.add(selectedPageCount);
        }
        if (chunkCount != null) {
            sql.append(", chunk_count = ?");
            args.add(chunkCount);
        }
        if (documentId != null) {
            sql.append(", document_id = ?");
            args.add(documentId);
        }
        if (skipReason != null) {
            sql.append(", skip_reason = ?");
            args.add(skipReason);
        }
        if (errorMessage != null) {
            sql.append(", error_message = ?");
            args.add(errorMessage);
        }
        sql.append(", finished_at = ?, updated_at = ? WHERE run_id = ? AND publication_number = ?");
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(runId);
        args.add(publicationNumber);
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    public void replacePages(UUID runId, String publicationNumber, List<PatentPageAssessment> pages) {
        jdbcTemplate.update("""
                DELETE FROM patent_demo_page
                WHERE run_id = ? AND publication_number = ?
                """, runId, publicationNumber);
        for (PatentPageAssessment page : pages) {
            jdbcTemplate.update("""
                    INSERT INTO patent_demo_page (
                        run_id, publication_number, page_number, score, role, selected, reason, text_chars
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    runId,
                    publicationNumber,
                    page.pageNumber(),
                    page.score(),
                    roleString(page),
                    page.selected(),
                    page.reason(),
                    page.textChars());
        }
    }

    public PatentDemoDocumentPage findDocuments(UUID runId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 500);
        long total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM patent_demo_document WHERE run_id = ?
                """, Long.class, runId);
        List<PatentDemoDocumentRecord> items = jdbcTemplate.query("""
                SELECT *
                FROM patent_demo_document
                WHERE run_id = ?
                ORDER BY created_at, publication_number
                LIMIT ? OFFSET ?
                """, this::mapDocument, runId, safeSize, safePage * safeSize);
        return new PatentDemoDocumentPage(items, safePage, safeSize, total);
    }

    public Optional<UUID> findCompletedDocumentByCanonicalKey(String canonicalKey) {
        return findCompletedRagDocumentByCanonicalKey(canonicalKey)
                .map(ExistingRagDocument::documentId);
    }

    public Optional<ExistingRagDocument> findCompletedRagDocumentByCanonicalKey(String canonicalKey) {
        return jdbcTemplate.query("""
                SELECT document_id, storage_root
                FROM rag_document
                WHERE canonical_key = ?
                  AND duplicate_of_document_id IS NULL
                  AND status = 'COMPLETED'
                ORDER BY updated_at DESC, created_at DESC
                LIMIT 1
                """, (rs, rowNum) -> new ExistingRagDocument(
                        rs.getObject("document_id", UUID.class),
                        rs.getString("storage_root")), canonicalKey)
                .stream().findFirst();
    }

    public void updateRagDocumentStorageRoot(UUID documentId, String storageRoot) {
        jdbcTemplate.update("""
                UPDATE rag_document
                SET storage_root = ?,
                    updated_at = ?
                WHERE document_id = ?
                """, storageRoot, Timestamp.from(Instant.now()), documentId);
    }

    public Optional<ExistingRagDocument> findCanonicalRagDocumentByPublicationNumber(String publicationNumber) {
        // Legacy partial inserts may have failed before the canonical key was stored.
        return jdbcTemplate.query("""
                SELECT d.document_id, d.storage_root, d.status
                FROM rag_document d
                WHERE d.duplicate_of_document_id IS NULL
                  AND d.status <> 'DUPLICATE_SKIPPED'
                  AND (d.canonical_key = ? OR (d.canonical_key IS NULL AND (
                      EXISTS (SELECT 1 FROM patent_demo_document p
                              WHERE p.document_id = d.document_id AND p.publication_number = ?)
                      OR (replace(d.storage_root, chr(92), '/') LIKE '%/patent-demo/%'
                          AND right(replace(d.storage_root, chr(92), '/'), length(?) + 1) = '/' || ?)
                  )))
                ORDER BY (d.canonical_key = ?) DESC NULLS LAST,
                         (d.status = 'COMPLETED') DESC, d.created_at, d.document_id
                LIMIT 1
                """, (rs, rowNum) -> new ExistingRagDocument(
                        rs.getObject("document_id", UUID.class), rs.getString("storage_root"),
                        RagDocumentStatus.valueOf(rs.getString("status"))),
                "patent:" + publicationNumber, publicationNumber, publicationNumber,
                publicationNumber, "patent:" + publicationNumber).stream().findFirst();
    }

    public long countVectorChunks(UUID documentId) {
        String table = properties.getRag().getVectorTable();
        if (!SAFE_SQL_ID.matcher(table).matches()) {
            throw new IllegalStateException("Unsafe vector table name");
        }
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM %s
                WHERE metadata->>'document_id' = ?
                """.formatted(table), Long.class, documentId.toString());
        return count == null ? 0L : count;
    }

    public int findExtractionEvidenceRows(UUID extractionRunId) {
        if (extractionRunId == null) {
            return 0;
        }
        try {
            Integer rows = jdbcTemplate.queryForObject("""
                    SELECT evidence_rows
                    FROM evidence_question_extraction_run
                    WHERE run_id = ?
                    """, Integer.class, extractionRunId);
            return rows == null ? 0 : rows;
        } catch (EmptyResultDataAccessException ignored) {
            return 0;
        }
    }

    private PatentDemoRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new PatentDemoRunRecord(
                rs.getObject("run_id", UUID.class),
                PatentDemoRunStatus.valueOf(rs.getString("status")),
                rs.getString("manifest_path"),
                rs.getString("pdf_root"),
                rs.getString("category"),
                rs.getInt("requested_limit"),
                longObject(rs, "seed"),
                rs.getBoolean("run_extraction"),
                rs.getBoolean("force"),
                rs.getInt("total_candidates"),
                rs.getInt("text_layer_passed"),
                rs.getInt("ingested_documents"),
                rs.getInt("skipped_documents"),
                rs.getInt("failed_documents"),
                rs.getObject("cohort_id", UUID.class),
                rs.getObject("extraction_run_id", UUID.class),
                rs.getInt("live_evidence_rows"),
                rs.getString("error_message"),
                longObject(rs, "elapsed_ms"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                publicationNumbers(rs.getString("config_json")),
                rs.getString("extraction_status"),
                rs.getInt("valid_evidence_rows")
        );
    }

    private List<String> publicationNumbers(String configJson) throws SQLException {
        try {
            var numbers = objectMapper.readTree(configJson == null ? "{}" : configJson)
                    .path("publicationNumbers");
            return numbers.isMissingNode() || numbers.isNull() ? List.of()
                    : objectMapper.convertValue(numbers, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SQLException("Invalid patent demo run config", e);
        }
    }

    private PatentDemoDocumentRecord mapDocument(ResultSet rs, int rowNum) throws SQLException {
        String textLayerStatus = rs.getString("text_layer_status");
        return new PatentDemoDocumentRecord(
                rs.getObject("run_id", UUID.class),
                rs.getString("publication_number"),
                rs.getString("title"),
                rs.getString("pdf_path"),
                PatentDemoDocumentStatus.valueOf(rs.getString("status")),
                textLayerStatus == null ? null : PatentTextLayerStatus.valueOf(textLayerStatus),
                intObject(rs, "page_count"),
                longObject(rs, "file_size_bytes"),
                intObject(rs, "sampled_text_chars"),
                doubleObject(rs, "usable_page_ratio"),
                doubleObject(rs, "replacement_char_ratio"),
                intObject(rs, "selected_page_count"),
                intObject(rs, "chunk_count"),
                rs.getObject("document_id", UUID.class),
                rs.getString("skip_reason"),
                rs.getString("error_message"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private String roleString(PatentPageAssessment page) {
        return page.roles().stream()
                .map(Enum::name)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private Integer intObject(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private Long longObject(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private Double doubleObject(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
