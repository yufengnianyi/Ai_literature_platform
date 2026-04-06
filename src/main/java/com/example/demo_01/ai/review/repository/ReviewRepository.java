package com.example.demo_01.ai.review.repository;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Repository
public class ReviewRepository {

    private static final Pattern SAFE_SQL_ID = Pattern.compile("[A-Za-z0-9_]+");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AiPersistenceProperties aiProperties;

    // ── Task ──

    public void insertTask(UUID taskId, String userId, String question) {
        jdbcTemplate.update("""
                INSERT INTO review_task (task_id, user_id, question, status, stage, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                taskId, userId, question,
                ReviewTaskStatus.QUEUED.name(), ReviewStage.QUERY_ANALYSIS.name(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    public void updateTaskStatus(UUID taskId, ReviewTaskStatus status, ReviewStage stage) {
        jdbcTemplate.update("""
                UPDATE review_task SET status = ?, stage = ?, updated_at = ? WHERE task_id = ?
                """, status.name(), stage.name(), Timestamp.from(Instant.now()), taskId);
    }

    public void updateQueryAnalysis(UUID taskId, QueryAnalysis analysis) {
        jdbcTemplate.update("""
                UPDATE review_task SET query_analysis = cast(? as jsonb), updated_at = ? WHERE task_id = ?
                """, toJson(analysis), Timestamp.from(Instant.now()), taskId);
    }

    public void updateTaskCounts(UUID taskId, int candidateCount, int evidenceCount) {
        jdbcTemplate.update("""
                UPDATE review_task SET candidate_count = ?, evidence_count = ?, updated_at = ? WHERE task_id = ?
                """, candidateCount, evidenceCount, Timestamp.from(Instant.now()), taskId);
    }

    public void updateTaskReport(UUID taskId, String reportMarkdown, String reportJson) {
        jdbcTemplate.update("""
                UPDATE review_task
                SET report_markdown = ?, report_json = cast(? as jsonb), updated_at = ?
                WHERE task_id = ?
                """, reportMarkdown, reportJson, Timestamp.from(Instant.now()), taskId);
    }

    public void updateTaskMetrics(UUID taskId, ReviewTaskMetrics m) {
        jdbcTemplate.update("""
                UPDATE review_task
                SET retrieval_ms = ?, rerank_ms = ?, extraction_ms = ?,
                    fusion_ms = ?, report_ms = ?, total_ms = ?, updated_at = ?
                WHERE task_id = ?
                """, m.retrievalMs(), m.rerankMs(), m.extractionMs(),
                m.fusionMs(), m.reportMs(), m.totalMs(),
                Timestamp.from(Instant.now()), taskId);
    }

    public void completeTask(UUID taskId) {
        jdbcTemplate.update("""
                UPDATE review_task
                SET status = ?, stage = ?, finished_at = ?, updated_at = ?
                WHERE task_id = ?
                """, ReviewTaskStatus.COMPLETED.name(), ReviewStage.COMPLETED.name(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), taskId);
    }

    public void failTask(UUID taskId, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE review_task
                SET status = ?, stage = ?, error_code = ?, error_message = ?,
                    finished_at = ?, updated_at = ?
                WHERE task_id = ?
                """, ReviewTaskStatus.FAILED.name(), ReviewStage.FAILED.name(),
                errorCode, truncate(errorMessage, 2000),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), taskId);
    }

    public Optional<ReviewTaskRecord> findTask(UUID taskId) {
        List<ReviewTaskRecord> rows = jdbcTemplate.query("""
                SELECT * FROM review_task WHERE task_id = ?
                """, this::mapTask, taskId);
        return rows.stream().findFirst();
    }

    public List<ReviewTaskRecord> findTasksByUser(String userId) {
        return jdbcTemplate.query("""
                SELECT * FROM review_task WHERE user_id = ? ORDER BY created_at DESC
                """, this::mapTask, userId);
    }

    public boolean deleteTask(UUID taskId, String userId) {
        int rows = jdbcTemplate.update("""
                DELETE FROM review_task WHERE task_id = ? AND user_id = ?
                """, taskId, userId);
        return rows > 0;
    }

    public void resetTaskForRetry(UUID taskId) {
        jdbcTemplate.update("""
                DELETE FROM review_evidence WHERE task_id = ?
                """, taskId);
        jdbcTemplate.update("""
                DELETE FROM review_candidate WHERE task_id = ?
                """, taskId);
        jdbcTemplate.update("""
                UPDATE review_task
                SET status = ?, stage = ?, query_analysis = NULL,
                    report_json = NULL, report_markdown = NULL,
                    candidate_count = NULL, evidence_count = NULL,
                    retrieval_ms = NULL, rerank_ms = NULL, extraction_ms = NULL,
                    fusion_ms = NULL, report_ms = NULL, total_ms = NULL,
                    error_code = NULL, error_message = NULL,
                    finished_at = NULL, updated_at = ?
                WHERE task_id = ?
                """, ReviewTaskStatus.QUEUED.name(), ReviewStage.QUERY_ANALYSIS.name(),
                Timestamp.from(Instant.now()), taskId);
    }

    // ── Candidate ──

    public void insertCandidates(UUID taskId, List<ReviewCandidate> candidates) {
        for (ReviewCandidate c : candidates) {
            jdbcTemplate.update("""
                    INSERT INTO review_candidate
                        (task_id, chunk_id, document_id, document_title,
                         retrieval_score, retrieval_source, chunk_text)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    taskId, c.chunkId(), c.documentId(), c.documentTitle(),
                    c.retrievalScore(), c.retrievalSource(), c.chunkText());
        }
    }

    public void updateCandidateReranking(UUID taskId, String chunkId,
                                         double rerankScore, Relevance relevance,
                                         String reason, boolean included) {
        jdbcTemplate.update("""
                UPDATE review_candidate
                SET rerank_score = ?, relevance = ?, screening_reason = ?, included = ?
                WHERE task_id = ? AND chunk_id = ?
                """, rerankScore, relevance.name(), reason, included, taskId, chunkId);
    }

    public List<ReviewCandidate> findIncludedCandidates(UUID taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM review_candidate WHERE task_id = ? AND included = TRUE
                ORDER BY rerank_score DESC NULLS LAST
                """, this::mapCandidate, taskId);
    }

    public List<ReviewCandidate> findAllCandidates(UUID taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM review_candidate WHERE task_id = ?
                ORDER BY retrieval_score DESC NULLS LAST
                """, this::mapCandidate, taskId);
    }

    // ── Evidence ──

    public void insertEvidence(UUID taskId, ExtractedEvidence e) {
        jdbcTemplate.update("""
                INSERT INTO review_evidence
                    (task_id, chunk_id, document_id, claim, finding, methodology,
                     entities, evidence_type, confidence, original_text,
                     sub_question)
                VALUES (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                """,
                taskId, e.chunkId(),
                e.documentId() != null ? UUID.fromString(e.documentId()) : null,
                e.claim(), e.finding(), e.methodology(),
                toJson(e.entities()), e.evidenceType(), e.confidence(),
                e.originalText(), e.subQuestion());
    }

    public void updateEvidenceFusion(Long evidenceId, String normalizedGroup, String consistency) {
        jdbcTemplate.update("""
                UPDATE review_evidence SET normalized_group = ?, consistency = ? WHERE id = ?
                """, normalizedGroup, consistency, evidenceId);
    }

    public List<ReviewEvidenceRecord> findEvidenceByTask(UUID taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM review_evidence WHERE task_id = ? ORDER BY id
                """, this::mapEvidence, taskId);
    }

    // ── Document FTS (Phase 1) ──

    public List<UUID> searchDocumentsByFts(String query, int maxResults) {
        return jdbcTemplate.query("""
                SELECT document_id
                FROM rag_document
                WHERE fts_vector @@ plainto_tsquery('english', ?)
                  AND status = 'COMPLETED'
                  AND duplicate_of_document_id IS NULL
                ORDER BY ts_rank(fts_vector, plainto_tsquery('english', ?)) DESC
                LIMIT ?
                """, (rs, rowNum) -> rs.getObject("document_id", UUID.class),
                query, query, maxResults);
    }

    // ── Chunk retrieval by document IDs (Phase 3) ──

    public List<RetrievedChunk> findChunksByDocumentIds(Set<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        String table = vectorTable();
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        String sql = """
                SELECT embedding_id::text AS embedding_id,
                       coalesce(text, '') AS text,
                       metadata::text AS metadata_json
                FROM %s
                WHERE metadata->>'document_id' IN (%s)
                """.formatted(table, placeholders);
        Object[] params = documentIds.stream().map(UUID::toString).toArray();
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String metaJson = rs.getString("metadata_json");
            var meta = parseMetadataMap(metaJson);
            return new RetrievedChunk(
                    getStr(meta, "chunk_id"),
                    parseUuid(getStr(meta, "document_id")),
                    getStr(meta, "title"),
                    rs.getString("text"),
                    getStr(meta, "section_path"),
                    0.0,
                    "DOC_EXPAND"
            );
        }, params);
    }

    // ── Row mappers ──

    private ReviewTaskRecord mapTask(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        String qaJson = rs.getString("query_analysis");
        return new ReviewTaskRecord(
                rs.getObject("task_id", UUID.class),
                rs.getString("user_id"),
                rs.getString("question"),
                ReviewTaskStatus.valueOf(rs.getString("status")),
                rs.getString("stage") == null ? null : ReviewStage.valueOf(rs.getString("stage")),
                qaJson == null ? null : fromJson(qaJson, QueryAnalysis.class),
                rs.getString("report_markdown"),
                (Integer) rs.getObject("candidate_count"),
                (Integer) rs.getObject("evidence_count"),
                new ReviewTaskMetrics(
                        (Long) rs.getObject("retrieval_ms"),
                        (Long) rs.getObject("rerank_ms"),
                        (Long) rs.getObject("extraction_ms"),
                        (Long) rs.getObject("fusion_ms"),
                        (Long) rs.getObject("report_ms"),
                        (Long) rs.getObject("total_ms")
                ),
                rs.getString("error_code"),
                rs.getString("error_message"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant()
        );
    }

    private ReviewCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        String relStr = rs.getString("relevance");
        return new ReviewCandidate(
                rs.getLong("id"),
                rs.getObject("task_id", UUID.class),
                rs.getString("chunk_id"),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getDouble("retrieval_score"),
                rs.getString("retrieval_source"),
                (Double) rs.getObject("rerank_score"),
                relStr == null ? null : Relevance.valueOf(relStr),
                rs.getString("screening_reason"),
                rs.getBoolean("included"),
                rs.getString("chunk_text")
        );
    }

    private ReviewEvidenceRecord mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewEvidenceRecord(
                rs.getLong("id"),
                rs.getObject("task_id", UUID.class),
                (Long) rs.getObject("candidate_id"),
                rs.getString("chunk_id"),
                rs.getObject("document_id", UUID.class),
                rs.getString("claim"),
                rs.getString("finding"),
                rs.getString("methodology"),
                fromJsonList(rs.getString("entities")),
                rs.getString("evidence_type"),
                rs.getDouble("confidence"),
                rs.getString("original_text"),
                rs.getString("normalized_group"),
                rs.getString("sub_question"),
                rs.getString("consistency")
        );
    }

    // ── Helpers ──

    private String vectorTable() {
        String table = aiProperties.getRag().getVectorTable();
        if (!SAFE_SQL_ID.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + table);
        }
        return table;
    }

    private java.util.Map<String, Object> parseMetadataMap(String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return java.util.Map.of();
        }
    }

    private String getStr(java.util.Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON deserialization failed", e);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
