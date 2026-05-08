package com.example.demo_01.ai.review.repository;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class DocumentKnowledgeRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public Optional<DocumentKnowledgeRecord> findKnowledge(UUID documentId) {
        List<DocumentKnowledgeRecord> rows = jdbcTemplate.query("""
                SELECT * FROM review_document_knowledge WHERE document_id = ?
                """, this::mapKnowledge, documentId);
        return rows.stream().findFirst();
    }

    public Map<UUID, DocumentKnowledgeRecord> findKnowledgeByDocumentIds(Set<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        String sql = "SELECT * FROM review_document_knowledge WHERE document_id IN (%s)".formatted(placeholders);
        List<DocumentKnowledgeRecord> rows = jdbcTemplate.query(sql, this::mapKnowledge, documentIds.toArray());
        Map<UUID, DocumentKnowledgeRecord> result = new LinkedHashMap<>();
        for (DocumentKnowledgeRecord row : rows) {
            result.put(row.documentId(), row);
        }
        return result;
    }

    public Map<UUID, List<DocumentCompoundAlias>> findAliasesByDocumentIds(Set<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        String sql = """
                SELECT * FROM review_document_compound_alias WHERE document_id IN (%s)
                ORDER BY document_id, local_alias
                """.formatted(placeholders);
        List<DocumentCompoundAlias> rows = jdbcTemplate.query(sql, this::mapAlias, documentIds.toArray());
        Map<UUID, List<DocumentCompoundAlias>> result = new LinkedHashMap<>();
        for (DocumentCompoundAlias alias : rows) {
            result.computeIfAbsent(alias.documentId(), ignored -> new java.util.ArrayList<>()).add(alias);
        }
        return result;
    }

    public void upsertKnowledge(DocumentKnowledgeRecord record) {
        jdbcTemplate.update("""
                INSERT INTO review_document_knowledge
                    (document_id, knowledge_json, coverage_chunk_ids_json, prompt_version,
                     knowledge_version, confidence, status, last_seen_task_id, updated_at)
                VALUES (?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                ON CONFLICT (document_id) DO UPDATE
                SET knowledge_json = EXCLUDED.knowledge_json,
                    coverage_chunk_ids_json = EXCLUDED.coverage_chunk_ids_json,
                    prompt_version = EXCLUDED.prompt_version,
                    knowledge_version = EXCLUDED.knowledge_version,
                    confidence = EXCLUDED.confidence,
                    status = EXCLUDED.status,
                    last_seen_task_id = EXCLUDED.last_seen_task_id,
                    updated_at = EXCLUDED.updated_at
                """,
                record.documentId(),
                toJson(record),
                toJson(record.coverageChunkIds()),
                record.promptVersion(),
                record.knowledgeVersion(),
                record.confidence(),
                record.knowledgeStatus().name(),
                record.lastSeenTaskId(),
                Timestamp.from(Instant.now()));
    }

    public UUID upsertCompoundIdentity(CompoundIdentity identity) {
        if (identity == null || identity.compoundId() == null || identity.compoundId().isBlank()) {
            return null;
        }
        try {
            return upsertCompoundIdentityByConflict(identity, """
                    ON CONFLICT (compound_id) DO UPDATE
                    """);
        } catch (DuplicateKeyException e) {
            String message = e.getMostSpecificCause() == null
                    ? e.getMessage()
                    : e.getMostSpecificCause().getMessage();
            if (message != null && message.contains("idx_review_compound_identity_inchi") && hasText(identity.inchiKey())) {
                return upsertCompoundIdentityByConflict(identity, """
                        ON CONFLICT (inchi_key) WHERE inchi_key IS NOT NULL AND inchi_key <> '' DO UPDATE
                        """);
            }
            if (message != null && message.contains("idx_review_compound_identity_smiles") && hasText(identity.smiles())) {
                return upsertCompoundIdentityByConflict(identity, """
                        ON CONFLICT (smiles) WHERE smiles IS NOT NULL AND smiles <> '' DO UPDATE
                        """);
            }
            if (message != null && message.contains("idx_review_compound_identity_cas") && hasText(identity.casNumber())) {
                return upsertCompoundIdentityByConflict(identity, """
                        ON CONFLICT (cas_number) WHERE cas_number IS NOT NULL AND cas_number <> '' DO UPDATE
                        """);
            }
            throw e;
        }
    }

    private UUID upsertCompoundIdentityByConflict(CompoundIdentity identity, String conflictClause) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO review_compound_identity
                    (compound_id, canonical_name, iupac_name, cas_number, smiles, inchi_key,
                     molecular_formula, structure_type, synonyms_json, confidence, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                %s
                SET canonical_name = coalesce(EXCLUDED.canonical_name, review_compound_identity.canonical_name),
                    iupac_name = coalesce(EXCLUDED.iupac_name, review_compound_identity.iupac_name),
                    cas_number = coalesce(EXCLUDED.cas_number, review_compound_identity.cas_number),
                    smiles = coalesce(EXCLUDED.smiles, review_compound_identity.smiles),
                    inchi_key = coalesce(EXCLUDED.inchi_key, review_compound_identity.inchi_key),
                    molecular_formula = coalesce(EXCLUDED.molecular_formula, review_compound_identity.molecular_formula),
                    structure_type = coalesce(EXCLUDED.structure_type, review_compound_identity.structure_type),
                    synonyms_json = EXCLUDED.synonyms_json,
                    confidence = greatest(coalesce(review_compound_identity.confidence, 0), coalesce(EXCLUDED.confidence, 0)),
                    updated_at = EXCLUDED.updated_at
                RETURNING compound_id
                """.formatted(conflictClause),
                UUID.class,
                UUID.fromString(identity.compoundId()),
                identity.canonicalName(),
                identity.iupacName(),
                identity.casNumber(),
                identity.smiles(),
                identity.inchiKey(),
                identity.molecularFormula(),
                identity.structureType(),
                toJson(identity.synonyms()),
                identity.confidence(),
                Timestamp.from(Instant.now()));
    }

    public void upsertAlias(UUID documentId, DocumentKnowledgeCompound compound) {
        if (documentId == null || compound == null || compound.localAlias() == null || compound.localAlias().isBlank()) {
            return;
        }
        UUID normalizedId = parseUuid(compound.normalizedCompoundId());
        CompoundResolutionStatus status = compound.resolutionStatus() == null
                ? CompoundResolutionStatus.UNRESOLVED
                : compound.resolutionStatus();
        jdbcTemplate.update("""
                INSERT INTO review_document_compound_alias
                    (document_id, local_alias, resolved_name, normalized_compound_id, evidence_chunk_id,
                     evidence_text, resolution_status, confidence, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (document_id, local_alias) DO UPDATE
                SET resolved_name = coalesce(EXCLUDED.resolved_name, review_document_compound_alias.resolved_name),
                    normalized_compound_id = coalesce(EXCLUDED.normalized_compound_id, review_document_compound_alias.normalized_compound_id),
                    evidence_chunk_id = coalesce(EXCLUDED.evidence_chunk_id, review_document_compound_alias.evidence_chunk_id),
                    evidence_text = coalesce(EXCLUDED.evidence_text, review_document_compound_alias.evidence_text),
                    resolution_status = EXCLUDED.resolution_status,
                    confidence = greatest(coalesce(review_document_compound_alias.confidence, 0), coalesce(EXCLUDED.confidence, 0)),
                    updated_at = EXCLUDED.updated_at
                """,
                documentId,
                compound.localAlias(),
                firstNonBlank(compound.resolvedName(), compound.canonicalName()),
                normalizedId,
                compound.evidenceChunkId(),
                compound.evidenceText(),
                status.name(),
                compound.confidence(),
                Timestamp.from(Instant.now()));
    }

    public void insertUpdateLog(UUID taskId, UUID documentId, String promptVersion,
                                List<String> updatedFields, List<String> sourceChunkIds) {
        jdbcTemplate.update("""
                INSERT INTO review_document_knowledge_update
                    (task_id, document_id, prompt_version, updated_fields_json, source_chunk_ids_json)
                VALUES (?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, taskId, documentId, promptVersion, toJson(updatedFields), toJson(sourceChunkIds));
    }

    private DocumentKnowledgeRecord mapKnowledge(ResultSet rs, int rowNum) throws SQLException {
        DocumentKnowledgeRecord parsed = fromJson(rs.getString("knowledge_json"), DocumentKnowledgeRecord.class);
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new DocumentKnowledgeRecord(
                rs.getObject("document_id", UUID.class),
                parsed.documentSummary(),
                safeList(parsed.researchObjects()),
                safeList(parsed.species()),
                safeList(parsed.genesOrProteins()),
                safeList(parsed.pathwaysOrProcesses()),
                safeList(parsed.developmentalStages()),
                safeList(parsed.methods()),
                parsed.compounds() == null ? List.of() : parsed.compounds(),
                safeList(parsed.keyFindings()),
                safeList(parsed.innovationPoints()),
                safeList(parsed.limitations()),
                parsed.evidenceAnchors() == null ? List.of() : parsed.evidenceAnchors(),
                KnowledgeStatus.valueOf(rs.getString("status")),
                rs.getString("prompt_version"),
                rs.getString("knowledge_version"),
                rs.getDouble("confidence"),
                fromJsonList(rs.getString("coverage_chunk_ids_json")),
                rs.getObject("last_seen_task_id", UUID.class),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private DocumentCompoundAlias mapAlias(ResultSet rs, int rowNum) throws SQLException {
        UUID normalized = rs.getObject("normalized_compound_id", UUID.class);
        return new DocumentCompoundAlias(
                rs.getObject("document_id", UUID.class),
                rs.getString("local_alias"),
                rs.getString("resolved_name"),
                normalized == null ? null : normalized.toString(),
                rs.getString("evidence_chunk_id"),
                rs.getString("evidence_text"),
                CompoundResolutionStatus.valueOf(rs.getString("resolution_status")),
                rs.getDouble("confidence")
        );
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
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
