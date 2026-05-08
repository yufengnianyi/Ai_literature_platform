package com.example.demo_01.ai.review.repository;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentSynopsis;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public void updateTaskCounts(UUID taskId, int candidateCount, int documentCount, int evidenceCount) {
        jdbcTemplate.update("""
                UPDATE review_task
                SET candidate_count = ?, document_count = ?, evidence_count = ?, updated_at = ?
                WHERE task_id = ?
                """, candidateCount, documentCount, evidenceCount, Timestamp.from(Instant.now()), taskId);
    }

    public void updateTaskReport(UUID taskId, String reportMarkdown, String reportJson) {
        jdbcTemplate.update("""
                UPDATE review_task
                SET report_markdown = ?, report_json = cast(? as jsonb), updated_at = ?
                WHERE task_id = ?
                """, reportMarkdown, reportJson, Timestamp.from(Instant.now()), taskId);
    }

    public void updateTaskMetrics(UUID taskId, ReviewTaskMetrics metrics) {
        jdbcTemplate.update("""
                UPDATE review_task
                SET retrieval_ms = ?, document_promotion_ms = ?, rerank_ms = ?, extraction_ms = ?,
                    fusion_ms = ?, report_ms = ?, total_ms = ?, updated_at = ?
                WHERE task_id = ?
                """,
                metrics.retrievalMs(), metrics.documentPromotionMs(), metrics.rerankMs(),
                metrics.extractionMs(), metrics.fusionMs(), metrics.reportMs(),
                metrics.totalMs(), Timestamp.from(Instant.now()), taskId);
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
        jdbcTemplate.update("DELETE FROM review_document_candidate WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM review_evidence WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM review_candidate WHERE task_id = ?", taskId);
        jdbcTemplate.update("""
                UPDATE review_task
                SET status = ?, stage = ?, query_analysis = NULL,
                    report_json = NULL, report_markdown = NULL,
                    candidate_count = NULL, document_count = NULL, evidence_count = NULL,
                    retrieval_ms = NULL, document_promotion_ms = NULL, rerank_ms = NULL, extraction_ms = NULL,
                    fusion_ms = NULL, report_ms = NULL, total_ms = NULL,
                    error_code = NULL, error_message = NULL,
                    finished_at = NULL, updated_at = ?
                WHERE task_id = ?
                """,
                ReviewTaskStatus.QUEUED.name(),
                ReviewStage.QUERY_ANALYSIS.name(),
                Timestamp.from(Instant.now()),
                taskId);
    }

    public void insertCandidates(UUID taskId, List<ReviewCandidate> candidates) {
        for (ReviewCandidate candidate : candidates) {
            jdbcTemplate.update("""
                    INSERT INTO review_candidate
                        (task_id, chunk_id, document_id, document_title, retrieval_score, retrieval_source,
                         section_path, retrieval_phase, document_score, document_relevance, document_reason,
                         chunk_text)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    taskId,
                    candidate.chunkId(),
                    candidate.documentId(),
                    candidate.documentTitle(),
                    candidate.retrievalScore(),
                    candidate.retrievalSource(),
                    candidate.sectionPath(),
                    candidate.retrievalPhase(),
                    candidate.documentScore(),
                    candidate.documentRelevance() == null ? null : candidate.documentRelevance().name(),
                    candidate.documentReason(),
                    candidate.chunkText());
        }
    }

    public void insertDocumentCandidates(UUID taskId, List<ReviewDocumentCandidate> candidates) {
        for (ReviewDocumentCandidate candidate : candidates) {
            jdbcTemplate.update("""
                    INSERT INTO review_document_candidate
                        (task_id, document_id, document_title, seed_chunk_count, seed_chunk_ids,
                         seed_max_score, seed_avg_top3_score, section_prior_score,
                         entity_coverage_score, contribution_score, final_score,
                         relevance, promotion_reason, synopsis_summary,
                         innovation_points, key_findings, expanded, selected)
                    VALUES (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb),
                            cast(? as jsonb), ?, ?)
                    ON CONFLICT (task_id, document_id) DO UPDATE
                    SET document_title = EXCLUDED.document_title,
                        seed_chunk_count = EXCLUDED.seed_chunk_count,
                        seed_chunk_ids = EXCLUDED.seed_chunk_ids,
                        seed_max_score = EXCLUDED.seed_max_score,
                        seed_avg_top3_score = EXCLUDED.seed_avg_top3_score,
                        section_prior_score = EXCLUDED.section_prior_score,
                        entity_coverage_score = EXCLUDED.entity_coverage_score,
                        contribution_score = EXCLUDED.contribution_score,
                        final_score = EXCLUDED.final_score,
                        relevance = EXCLUDED.relevance,
                        promotion_reason = EXCLUDED.promotion_reason,
                        synopsis_summary = EXCLUDED.synopsis_summary,
                        innovation_points = EXCLUDED.innovation_points,
                        key_findings = EXCLUDED.key_findings,
                        expanded = EXCLUDED.expanded,
                        selected = EXCLUDED.selected
                    """,
                    taskId,
                    candidate.documentId(),
                    candidate.documentTitle(),
                    candidate.seedChunkCount(),
                    toJson(candidate.seedChunkIds()),
                    candidate.seedMaxScore(),
                    candidate.seedAvgTop3Score(),
                    candidate.sectionPriorScore(),
                    candidate.entityCoverageScore(),
                    candidate.contributionScore(),
                    candidate.finalScore(),
                    candidate.relevance() == null ? null : candidate.relevance().name(),
                    candidate.promotionReason(),
                    candidate.synopsisSummary(),
                    toJson(candidate.innovationPoints()),
                    toJson(candidate.keyFindings()),
                    candidate.expanded(),
                    candidate.selected());
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
                ORDER BY coalesce(document_score, retrieval_score) DESC NULLS LAST, retrieval_score DESC NULLS LAST
                """, this::mapCandidate, taskId);
    }

    public List<ReviewCandidate> findUserApprovedCandidates(UUID taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM review_candidate
                WHERE task_id = ? AND included = TRUE AND user_excluded = FALSE
                ORDER BY CASE WHEN user_prioritized = TRUE THEN 0 ELSE 1 END,
                         rerank_score DESC NULLS LAST
                """, this::mapCandidate, taskId);
    }

    public List<ReviewDocumentCandidate> findDocumentCandidates(UUID taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM review_document_candidate
                WHERE task_id = ?
                ORDER BY final_score DESC NULLS LAST, seed_max_score DESC NULLS LAST
                """, this::mapDocumentCandidate, taskId);
    }

    public void updateCandidateUserExcluded(UUID taskId, List<String> chunkIds, boolean excluded) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        for (String chunkId : chunkIds) {
            jdbcTemplate.update("""
                    UPDATE review_candidate SET user_excluded = ? WHERE task_id = ? AND chunk_id = ?
                    """, excluded, taskId, chunkId);
        }
    }

    public void updateCandidateUserPrioritized(UUID taskId, List<String> chunkIds, boolean prioritized) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        for (String chunkId : chunkIds) {
            jdbcTemplate.update("""
                    UPDATE review_candidate SET user_prioritized = ? WHERE task_id = ? AND chunk_id = ?
                    """, prioritized, taskId, chunkId);
        }
    }

    public void insertEvidence(UUID taskId, ExtractedEvidence evidence) {
        jdbcTemplate.update("""
                INSERT INTO review_evidence
                    (task_id, chunk_id, document_id, document_title, claim, finding, methodology,
                     typed_entities, entities, evidence_type, confidence, original_text, sub_question)
                VALUES (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?)
                """,
                taskId,
                evidence.chunkId(),
                evidence.documentId() != null ? UUID.fromString(evidence.documentId()) : null,
                evidence.documentTitle(),
                evidence.claim(),
                evidence.finding(),
                evidence.methodology(),
                toJson(evidence.typedEntities()),
                toJson(evidence.entities()),
                evidence.evidenceType(),
                evidence.confidence(),
                evidence.originalText(),
                evidence.subQuestion());
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

    public List<ReviewEvidenceRecord> findUserApprovedEvidence(UUID taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM review_evidence WHERE task_id = ? AND user_excluded = FALSE ORDER BY id
                """, this::mapEvidence, taskId);
    }

    public void updateEvidenceUserExcluded(UUID taskId, List<Long> evidenceIds, boolean excluded) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return;
        }
        for (Long evidenceId : evidenceIds) {
            jdbcTemplate.update("""
                    UPDATE review_evidence SET user_excluded = ? WHERE task_id = ? AND id = ?
                    """, excluded, taskId, evidenceId);
        }
    }

    public void updateTaskUserGuidance(UUID taskId, String guidance, List<String> focusSubQuestions) {
        jdbcTemplate.update("""
                UPDATE review_task SET user_guidance = ?, focus_sub_questions = cast(? as jsonb), updated_at = ?
                WHERE task_id = ?
                """, guidance, toJson(focusSubQuestions), Timestamp.from(Instant.now()), taskId);
    }

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

    public Map<UUID, DocumentSynopsisRecord> findDocumentSynopsisByIds(Set<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        String sql = """
                SELECT document_id, title, synopsis_json
                FROM rag_document
                WHERE document_id IN (%s)
                """.formatted(placeholders);
        Object[] params = documentIds.toArray();
        List<DocumentSynopsisRecord> rows = jdbcTemplate.query(sql, (rs, rowNum) ->
                new DocumentSynopsisRecord(
                        rs.getObject("document_id", UUID.class),
                        rs.getString("title"),
                        fromJsonSynopsis(rs.getString("synopsis_json"))
                ), params);
        Map<UUID, DocumentSynopsisRecord> result = new LinkedHashMap<>();
        for (DocumentSynopsisRecord row : rows) {
            result.put(row.documentId(), row);
        }
        return result;
    }

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
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRetrievedChunk(rs, "DOC_EXPAND"), params);
    }

    public List<RetrievedChunk> findPriorityChunksByDocumentIds(Set<UUID> documentIds, int perDocLimit) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        String table = vectorTable();
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        String sql = """
                WITH ranked AS (
                    SELECT embedding_id::text AS embedding_id,
                           coalesce(text, '') AS text,
                           metadata::text AS metadata_json,
                           row_number() OVER (
                               PARTITION BY metadata->>'document_id'
                               ORDER BY CASE
                                   WHEN lower(coalesce(metadata->>'section_path', '')) LIKE '%%result%%' THEN 1
                                   WHEN lower(coalesce(metadata->>'section_path', '')) LIKE '%%discussion%%' THEN 2
                                   WHEN lower(coalesce(metadata->>'section_path', '')) LIKE '%%conclusion%%' THEN 3
                                   WHEN lower(coalesce(metadata->>'section_path', '')) LIKE '%%abstract%%' THEN 4
                                   WHEN lower(coalesce(metadata->>'section_path', '')) LIKE '%%intro%%' THEN 5
                                   ELSE 6
                               END,
                               embedding_id::text
                           ) AS rn
                    FROM %s
                    WHERE metadata->>'document_id' IN (%s)
                )
                SELECT embedding_id, text, metadata_json
                FROM ranked
                WHERE rn <= ?
                """.formatted(table, placeholders);
        List<Object> params = new ArrayList<>();
        documentIds.stream().map(UUID::toString).forEach(params::add);
        params.add(perDocLimit);
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRetrievedChunk(rs, "DOC_PROMOTED"), params.toArray());
    }

    public List<RetrievedChunk> findAllChunksByDocumentId(UUID documentId) {
        if (documentId == null) return List.of();
        String table = vectorTable();
        String sql = """
                SELECT embedding_id::text AS embedding_id,
                       coalesce(text, '') AS text,
                       metadata::text AS metadata_json
                FROM %s
                WHERE metadata->>'document_id' = ?
                """.formatted(table);
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRetrievedChunk(rs, "DOC_ALL"), documentId.toString());
    }

    public void insertChunkAnchors(UUID taskId, List<ChunkAnchor> anchors) {
        if (anchors == null || anchors.isEmpty()) return;
        for (ChunkAnchor anchor : anchors) {
            jdbcTemplate.update("""
                    INSERT INTO review_chunk_anchor (task_id, chunk_id, anchor_type, reason, matched_tokens)
                    VALUES (?, ?, ?, ?, ?)
                    """, taskId, anchor.chunkId(), anchor.type().name(), anchor.reason(),
                    anchor.matchedTokens() != null ? String.join(",", anchor.matchedTokens()) : null);
        }
    }

    public void upsertDocumentAlias(UUID documentId, String localAlias, String canonicalName, String resolutionStatus) {
        jdbcTemplate.update("""
                INSERT INTO review_document_alias_map (document_id, local_alias, canonical_name, resolution_status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (document_id, local_alias) DO UPDATE
                SET canonical_name = EXCLUDED.canonical_name, resolution_status = EXCLUDED.resolution_status
                """, documentId, localAlias, canonicalName, resolutionStatus);
    }

    public void insertSynthesizedCompound(UUID taskId, SynthesizedCompoundRecord record) {
        String compoundKey = (record.compoundName() != null ? record.compoundName() : "unknown").toLowerCase(java.util.Locale.ROOT);
        UUID documentId = record.documentId() != null ? parseUuid(record.documentId()) : null;
        jdbcTemplate.update("""
                INSERT INTO review_synthesized_compound
                    (task_id, document_id, compound_key, compound_name, role, payload_json, coverage_warnings, confidence)
                VALUES (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
                ON CONFLICT (task_id, document_id, compound_key) DO UPDATE
                SET compound_name = EXCLUDED.compound_name,
                    role = EXCLUDED.role,
                    payload_json = EXCLUDED.payload_json,
                    coverage_warnings = EXCLUDED.coverage_warnings,
                    confidence = EXCLUDED.confidence
                """, taskId, documentId, compoundKey, record.compoundName(),
                record.role() != null ? record.role().name() : null,
                toJson(record), toJson(record.coverageWarnings()), record.confidence());
    }

    public List<SynthesizedCompoundRecord> findSynthesizedCompoundsByTask(UUID taskId) {
        return jdbcTemplate.query("""
                SELECT payload_json, document_id, coverage_warnings, confidence
                FROM review_synthesized_compound
                WHERE task_id = ?
                ORDER BY document_id, confidence DESC
                """, (rs, rowNum) -> {
            String payloadJson = rs.getString("payload_json");
            SynthesizedCompoundRecord base = fromJson(payloadJson, SynthesizedCompoundRecord.class);
            UUID documentId = rs.getObject("document_id", UUID.class);
            List<String> warnings = fromJsonList(rs.getString("coverage_warnings"));
            return new SynthesizedCompoundRecord(
                    base.compoundName(),
                    documentId != null ? documentId.toString() : base.documentId(),
                    base.documentTitle(),
                    base.role(),
                    base.structureType(),
                    base.source(),
                    base.paradigmActivities(),
                    base.mechanismSummary(),
                    base.safetyProfile(),
                    base.comparisons(),
                    base.contextNote(),
                    base.targetOrganisms(),
                    base.confidence(),
                    base.reference(),
                    base.evidenceChunkIds(),
                    warnings != null && !warnings.isEmpty() ? warnings : base.coverageWarnings()
            );
        }, taskId);
    }

    private ReviewTaskRecord mapTask(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        String queryAnalysisJson = rs.getString("query_analysis");
        return new ReviewTaskRecord(
                rs.getObject("task_id", UUID.class),
                rs.getString("user_id"),
                rs.getString("question"),
                ReviewTaskStatus.valueOf(rs.getString("status")),
                rs.getString("stage") == null ? null : ReviewStage.valueOf(rs.getString("stage")),
                queryAnalysisJson == null ? null : fromJson(queryAnalysisJson, QueryAnalysis.class),
                rs.getString("report_markdown"),
                (Integer) rs.getObject("candidate_count"),
                (Integer) rs.getObject("document_count"),
                (Integer) rs.getObject("evidence_count"),
                new ReviewTaskMetrics(
                        (Long) rs.getObject("retrieval_ms"),
                        (Long) rs.getObject("document_promotion_ms"),
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
        String relevance = rs.getString("relevance");
        String documentRelevance = rs.getString("document_relevance");
        return new ReviewCandidate(
                rs.getLong("id"),
                rs.getObject("task_id", UUID.class),
                rs.getString("chunk_id"),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getDouble("retrieval_score"),
                rs.getString("retrieval_source"),
                rs.getString("section_path"),
                rs.getString("retrieval_phase"),
                (Double) rs.getObject("document_score"),
                documentRelevance == null ? null : Relevance.valueOf(documentRelevance),
                rs.getString("document_reason"),
                (Double) rs.getObject("rerank_score"),
                relevance == null ? null : Relevance.valueOf(relevance),
                rs.getString("screening_reason"),
                rs.getBoolean("included"),
                rs.getString("chunk_text")
        );
    }

    private ReviewDocumentCandidate mapDocumentCandidate(ResultSet rs, int rowNum) throws SQLException {
        String relevance = rs.getString("relevance");
        return new ReviewDocumentCandidate(
                rs.getLong("id"),
                rs.getObject("task_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getInt("seed_chunk_count"),
                fromJsonList(rs.getString("seed_chunk_ids")),
                (Double) rs.getObject("seed_max_score"),
                (Double) rs.getObject("seed_avg_top3_score"),
                (Double) rs.getObject("section_prior_score"),
                (Double) rs.getObject("entity_coverage_score"),
                (Double) rs.getObject("contribution_score"),
                (Double) rs.getObject("final_score"),
                relevance == null ? null : Relevance.valueOf(relevance),
                rs.getString("promotion_reason"),
                rs.getString("synopsis_summary"),
                fromJsonList(rs.getString("innovation_points")),
                fromJsonList(rs.getString("key_findings")),
                rs.getBoolean("expanded"),
                rs.getBoolean("selected")
        );
    }

    private ReviewEvidenceRecord mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewEvidenceRecord(
                rs.getLong("id"),
                rs.getObject("task_id", UUID.class),
                (Long) rs.getObject("candidate_id"),
                rs.getString("chunk_id"),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("claim"),
                rs.getString("finding"),
                rs.getString("methodology"),
                fromJson(rs.getString("typed_entities"), TypedEntities.class),
                fromJsonList(rs.getString("entities")),
                rs.getString("evidence_type"),
                rs.getDouble("confidence"),
                rs.getString("original_text"),
                rs.getString("normalized_group"),
                rs.getString("sub_question"),
                rs.getString("consistency")
        );
    }

    private RetrievedChunk mapRetrievedChunk(ResultSet rs, String source) throws SQLException {
        Map<String, Object> metadata = parseMetadataMap(rs.getString("metadata_json"));
        return new RetrievedChunk(
                getStr(metadata, "chunk_id"),
                parseUuid(getStr(metadata, "document_id")),
                getStr(metadata, "title"),
                rs.getString("text"),
                getStr(metadata, "section_path"),
                0.0,
                source
        );
    }

    private String vectorTable() {
        String table = aiProperties.getRag().getVectorTable();
        if (!SAFE_SQL_ID.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + table);
        }
        return table;
    }

    private Map<String, Object> parseMetadataMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String getStr(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
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

    private RagDocumentSynopsis fromJsonSynopsis(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RagDocumentSynopsis.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON deserialization failed", e);
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

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record DocumentSynopsisRecord(
            UUID documentId,
            String title,
            RagDocumentSynopsis synopsis
    ) {
    }
}
