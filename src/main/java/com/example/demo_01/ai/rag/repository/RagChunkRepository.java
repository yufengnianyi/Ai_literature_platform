package com.example.demo_01.ai.rag.repository;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only access to embedded document chunks and FTS document lookup.
 * Replaces the chunk-query portion of the removed review-task repository.
 */
@Repository
public class RagChunkRepository {

    private static final Pattern SAFE_SQL_ID = Pattern.compile("[A-Za-z0-9_]+");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AiPersistenceProperties aiProperties;

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
        if (documentId == null) {
            return List.of();
        }
        String table = vectorTable();
        String sql = """
                SELECT embedding_id::text AS embedding_id,
                       coalesce(text, '') AS text,
                       metadata::text AS metadata_json
                FROM %s
                WHERE metadata->>'document_id' = ?
                ORDER BY CASE
                             WHEN metadata->>'chunk_index' ~ '^[0-9]+$' THEN (metadata->>'chunk_index')::int
                             ELSE 2147483647
                         END,
                         embedding_id::text
                """.formatted(table);
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRetrievedChunk(rs, "DOC_ALL"), documentId.toString());
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
}
