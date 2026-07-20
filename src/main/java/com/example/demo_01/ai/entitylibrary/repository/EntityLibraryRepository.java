package com.example.demo_01.ai.entitylibrary.repository;

import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityEvidenceView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryEntryView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryRow;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EvidenceItem;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewCandidateView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EntityLibraryRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<EvidenceItem>> EVIDENCE_LIST_TYPE = new TypeReference<>() {
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public void insertCandidate(UUID candidateId,
                                String entityType,
                                String mentionText,
                                String canonicalName,
                                String normalizedKey,
                                List<String> aliases,
                                String reason,
                                List<EvidenceItem> evidence,
                                double confidence,
                                UUID sourceDocumentId,
                                String sourceTitle,
                                UUID matchedEntityId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into entity_review_candidate (
                    candidate_id, entity_type, mention_text, canonical_name, normalized_key,
                    aliases_json, reason, evidence_json, confidence,
                    source_document_id, source_title, review_status, matched_entity_id,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?)
                """,
                candidateId,
                entityType,
                mentionText,
                canonicalName,
                normalizedKey,
                toJson(aliases == null ? List.of() : aliases),
                reason,
                toJson(evidence == null ? List.of() : evidence),
                confidence,
                sourceDocumentId,
                sourceTitle,
                ReviewStatus.PENDING.name(),
                matchedEntityId,
                now,
                now);
    }

    public List<ReviewCandidateView> listCandidates(ReviewStatus status) {
        String sql = """
                select candidate_id, entity_type, mention_text, canonical_name, normalized_key,
                       aliases_json::text as aliases_json, reason, evidence_json::text as evidence_json,
                       confidence, source_document_id, source_title, review_status, review_note,
                       reviewed_at, matched_entity_id, created_at
                from entity_review_candidate
                """;
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql += " where review_status = ?";
            args.add(status.name());
        }
        sql += " order by created_at desc";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ReviewCandidateView(
                rs.getObject("candidate_id", UUID.class),
                rs.getString("entity_type"),
                rs.getString("mention_text"),
                rs.getString("canonical_name"),
                rs.getString("normalized_key"),
                readStringList(rs.getString("aliases_json")),
                rs.getString("reason"),
                readEvidenceList(rs.getString("evidence_json")),
                rs.getDouble("confidence"),
                rs.getObject("source_document_id", UUID.class),
                rs.getString("source_title"),
                rs.getString("review_status"),
                rs.getString("review_note"),
                toInstant(rs.getTimestamp("reviewed_at")),
                rs.getObject("matched_entity_id", UUID.class),
                toInstant(rs.getTimestamp("created_at"))
        ), args.toArray());
    }

    public Optional<ReviewCandidateView> findCandidate(UUID candidateId) {
        List<ReviewCandidateView> rows = jdbcTemplate.query("""
                select candidate_id, entity_type, mention_text, canonical_name, normalized_key,
                       aliases_json::text as aliases_json, reason, evidence_json::text as evidence_json,
                       confidence, source_document_id, source_title, review_status, review_note,
                       reviewed_at, matched_entity_id, created_at
                from entity_review_candidate
                where candidate_id = ?
                """, (rs, rowNum) -> new ReviewCandidateView(
                rs.getObject("candidate_id", UUID.class),
                rs.getString("entity_type"),
                rs.getString("mention_text"),
                rs.getString("canonical_name"),
                rs.getString("normalized_key"),
                readStringList(rs.getString("aliases_json")),
                rs.getString("reason"),
                readEvidenceList(rs.getString("evidence_json")),
                rs.getDouble("confidence"),
                rs.getObject("source_document_id", UUID.class),
                rs.getString("source_title"),
                rs.getString("review_status"),
                rs.getString("review_note"),
                toInstant(rs.getTimestamp("reviewed_at")),
                rs.getObject("matched_entity_id", UUID.class),
                toInstant(rs.getTimestamp("created_at"))
        ), candidateId);
        return rows.stream().findFirst();
    }

    public void updateCandidateDecision(UUID candidateId,
                                        ReviewStatus status,
                                        String reviewNote,
                                        UUID matchedEntityId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                update entity_review_candidate
                set review_status = ?,
                    review_note = ?,
                    reviewed_at = ?,
                    matched_entity_id = coalesce(?, matched_entity_id),
                    updated_at = ?
                where candidate_id = ?
                """,
                status.name(),
                reviewNote,
                now,
                matchedEntityId,
                now,
                candidateId);
    }

    public Optional<EntityLibraryRow> findByKey(String entityType, String normalizedKey) {
        List<EntityLibraryRow> rows = jdbcTemplate.query("""
                select entity_id, entity_type, normalized_key, canonical_name,
                       aliases_json::text as aliases_json, definition, status, source_count
                from entity_library
                where entity_type = ? and normalized_key = ?
                """, (rs, rowNum) -> new EntityLibraryRow(
                rs.getObject("entity_id", UUID.class),
                rs.getString("entity_type"),
                rs.getString("normalized_key"),
                rs.getString("canonical_name"),
                readStringList(rs.getString("aliases_json")),
                rs.getString("definition"),
                rs.getString("status"),
                rs.getInt("source_count")
        ), entityType, normalizedKey);
        return rows.stream().findFirst();
    }

    public Optional<EntityLibraryRow> lockByKey(String entityType, String normalizedKey) {
        List<EntityLibraryRow> rows = jdbcTemplate.query("""
                select entity_id, entity_type, normalized_key, canonical_name,
                       aliases_json::text as aliases_json, definition, status, source_count
                from entity_library
                where entity_type = ? and normalized_key = ?
                for update
                """, (rs, rowNum) -> new EntityLibraryRow(
                rs.getObject("entity_id", UUID.class),
                rs.getString("entity_type"),
                rs.getString("normalized_key"),
                rs.getString("canonical_name"),
                readStringList(rs.getString("aliases_json")),
                rs.getString("definition"),
                rs.getString("status"),
                rs.getInt("source_count")
        ), entityType, normalizedKey);
        return rows.stream().findFirst();
    }

    public void insertEntity(UUID entityId,
                             String entityType,
                             String normalizedKey,
                             String canonicalName,
                             List<String> aliases,
                             int sourceCount) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into entity_library (
                    entity_id, entity_type, normalized_key, canonical_name, aliases_json,
                    definition, status, source_count, created_at, updated_at
                ) values (?, ?, ?, ?, cast(? as jsonb), null, 'ACTIVE', ?, ?, ?)
                on conflict (entity_type, normalized_key) do nothing
                """,
                entityId,
                entityType,
                normalizedKey,
                canonicalName,
                toJson(aliases == null ? List.of() : aliases),
                sourceCount,
                now,
                now);
    }

    public void updateEntityOnMerge(UUID entityId, List<String> mergedAliases) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                update entity_library
                set aliases_json = cast(? as jsonb),
                    source_count = source_count + 1,
                    updated_at = ?
                where entity_id = ?
                """,
                toJson(mergedAliases == null ? List.of() : mergedAliases),
                now,
                entityId);
    }

    public void insertEvidenceIfAbsent(UUID entityId,
                                       String reason,
                                       String evidenceText,
                                       double confidence,
                                       UUID sourceDocumentId,
                                       String sourceTitle,
                                       String quoteHash) {
        jdbcTemplate.update("""
                insert into entity_library_evidence (
                    entity_id, reason, evidence_text, confidence,
                    source_document_id, source_title, quote_hash, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (entity_id, quote_hash) do nothing
                """,
                entityId,
                reason,
                evidenceText,
                confidence,
                sourceDocumentId,
                sourceTitle,
                quoteHash,
                Timestamp.from(Instant.now()));
    }

    public List<EntityLibraryEntryView> listEntities(String entityType, String query, boolean includeEvidence) {
        StringBuilder sql = new StringBuilder("""
                select entity_id, entity_type, normalized_key, canonical_name,
                       aliases_json::text as aliases_json, definition, status, source_count,
                       created_at, updated_at
                from entity_library
                where status = 'ACTIVE'
                """);
        List<Object> args = new ArrayList<>();
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" and entity_type = ?");
            args.add(entityType.trim().toUpperCase());
        }
        if (query != null && !query.isBlank()) {
            sql.append(" and (canonical_name ilike ? or normalized_key ilike ? or aliases_json::text ilike ?)");
            String pattern = "%" + query.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" order by updated_at desc, canonical_name asc");

        List<EntityLibraryEntryView> entities = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            UUID entityId = rs.getObject("entity_id", UUID.class);
            return new EntityLibraryEntryView(
                    entityId,
                    rs.getString("entity_type"),
                    rs.getString("normalized_key"),
                    rs.getString("canonical_name"),
                    readStringList(rs.getString("aliases_json")),
                    rs.getString("definition"),
                    rs.getString("status"),
                    rs.getInt("source_count"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")),
                    includeEvidence ? listEvidence(entityId) : List.of()
            );
        }, args.toArray());
        return entities;
    }

    public Optional<EntityLibraryEntryView> findEntity(UUID entityId) {
        List<EntityLibraryEntryView> rows = jdbcTemplate.query("""
                select entity_id, entity_type, normalized_key, canonical_name,
                       aliases_json::text as aliases_json, definition, status, source_count,
                       created_at, updated_at
                from entity_library
                where entity_id = ?
                """, (rs, rowNum) -> {
            UUID id = rs.getObject("entity_id", UUID.class);
            return new EntityLibraryEntryView(
                    id,
                    rs.getString("entity_type"),
                    rs.getString("normalized_key"),
                    rs.getString("canonical_name"),
                    readStringList(rs.getString("aliases_json")),
                    rs.getString("definition"),
                    rs.getString("status"),
                    rs.getInt("source_count"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")),
                    listEvidence(id)
            );
        }, entityId);
        return rows.stream().findFirst();
    }

    public List<EntityEvidenceView> listEvidence(UUID entityId) {
        return jdbcTemplate.query("""
                select evidence_id, reason, evidence_text, confidence,
                       source_document_id, source_title, created_at
                from entity_library_evidence
                where entity_id = ?
                order by created_at desc, evidence_id desc
                """, (rs, rowNum) -> new EntityEvidenceView(
                rs.getLong("evidence_id"),
                rs.getString("reason"),
                rs.getString("evidence_text"),
                rs.getDouble("confidence"),
                rs.getObject("source_document_id", UUID.class),
                rs.getString("source_title"),
                toInstant(rs.getTimestamp("created_at"))
        ), entityId);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST_TYPE);
            return values == null ? List.of() : values;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse aliases_json", e);
        }
    }

    private List<EvidenceItem> readEvidenceList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<EvidenceItem> values = objectMapper.readValue(json, EVIDENCE_LIST_TYPE);
            return values == null ? List.of() : values;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse evidence_json", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize entity library json", e);
        }
    }
}
