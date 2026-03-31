package com.example.demo_01.ai.kg.repository;

import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.ChunkRelationExtraction;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncStatus;
import com.example.demo_01.ai.kg.model.KgModels.KgExtractionJobView;
import com.example.demo_01.ai.kg.model.KgModels.KgExtractionStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class KgExtractionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public void insertJob(UUID jobId, UUID documentId, GraphBuilderSyncStatus graphBuilderStatus) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into kg_extraction_job (
                    job_id, document_id, status, graph_builder_status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
                jobId,
                documentId,
                KgExtractionStatus.QUEUED.name(),
                graphBuilderStatus.name(),
                now,
                now);
    }

    public void markJobRunning(UUID jobId) {
        jdbcTemplate.update("""
                update kg_extraction_job
                set status = ?, started_at = ?, updated_at = ?
                where job_id = ?
                """,
                KgExtractionStatus.RUNNING.name(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                jobId);
    }

    public void markJobCompleted(UUID jobId,
                                 int entityCount,
                                 int relationCount,
                                 String payloadPath,
                                 GraphBuilderSyncStatus graphBuilderStatus) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                update kg_extraction_job
                set status = ?,
                    entity_count = ?,
                    relation_count = ?,
                    payload_path = ?,
                    graph_builder_status = ?,
                    finished_at = ?,
                    updated_at = ?
                where job_id = ?
                """,
                KgExtractionStatus.COMPLETED.name(),
                entityCount,
                relationCount,
                payloadPath,
                graphBuilderStatus.name(),
                now,
                now,
                jobId);
    }

    public void markJobFailed(UUID jobId,
                              int entityCount,
                              int relationCount,
                              String payloadPath,
                              GraphBuilderSyncStatus graphBuilderStatus,
                              String errorCode,
                              String errorMessage) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                update kg_extraction_job
                set status = ?,
                    entity_count = ?,
                    relation_count = ?,
                    payload_path = ?,
                    graph_builder_status = ?,
                    error_code = ?,
                    error_message = ?,
                    finished_at = ?,
                    updated_at = ?
                where job_id = ?
                """,
                KgExtractionStatus.FAILED.name(),
                entityCount,
                relationCount,
                payloadPath,
                graphBuilderStatus.name(),
                errorCode,
                errorMessage,
                now,
                now,
                jobId);
    }

    public void replaceChunkEntities(UUID documentId, List<ChunkEntityExtraction> entities) {
        jdbcTemplate.update("delete from kg_chunk_entity where document_id = ?", documentId);
        for (ChunkEntityExtraction entity : entities) {
            jdbcTemplate.update("""
                    insert into kg_chunk_entity (
                        document_id, chunk_id, mention_text, canonical_name, entity_type, normalized_key, aliases_json,
                        evidence_text, confidence, created_at
                    ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                    """,
                    entity.documentId(),
                    entity.chunkId(),
                    entity.mentionText(),
                    entity.canonicalName(),
                    entity.entityType().name(),
                    entity.normalizedKey(),
                    toJson(entity.aliases()),
                    entity.evidenceText(),
                    entity.confidence(),
                    Timestamp.from(Instant.now()));
        }
    }

    public void replaceChunkRelations(UUID documentId, List<ChunkRelationExtraction> relations) {
        jdbcTemplate.update("delete from kg_chunk_relation where document_id = ?", documentId);
        for (ChunkRelationExtraction relation : relations) {
            jdbcTemplate.update("""
                    insert into kg_chunk_relation (
                        document_id, chunk_id, head_normalized_key, relation_type, tail_normalized_key,
                        evidence_text, confidence, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    relation.documentId(),
                    relation.chunkId(),
                    relation.headNormalizedKey(),
                    relation.relationType().name(),
                    relation.tailNormalizedKey(),
                    relation.evidenceText(),
                    relation.confidence(),
                    Timestamp.from(Instant.now()));
        }
    }

    public void upsertPaperPayload(UUID documentId, String payloadJson, int entityCount, int relationCount, String payloadPath) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into kg_paper_graph_payload (
                    document_id, payload_json, entity_count, relation_count, payload_path, created_at, updated_at
                ) values (?, cast(? as jsonb), ?, ?, ?, ?, ?)
                on conflict (document_id) do update
                set payload_json = excluded.payload_json,
                    entity_count = excluded.entity_count,
                    relation_count = excluded.relation_count,
                    payload_path = excluded.payload_path,
                    updated_at = excluded.updated_at
                """,
                documentId,
                payloadJson,
                entityCount,
                relationCount,
                payloadPath,
                now,
                now);
    }

    public void insertSyncLog(UUID syncId,
                              UUID documentId,
                              GraphBuilderSyncStatus status,
                              String endpoint,
                              String requestPayload,
                              String responsePayload,
                              String errorMessage) {
        jdbcTemplate.update("""
                insert into kg_graph_builder_sync_log (
                    sync_id, document_id, status, endpoint, request_payload, response_payload, error_message, created_at
                ) values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?)
                """,
                syncId,
                documentId,
                status.name(),
                endpoint,
                jsonOrNull(requestPayload),
                jsonOrNull(responsePayload),
                errorMessage,
                Timestamp.from(Instant.now()));
    }

    public Optional<KgExtractionJobView> findLatestJob(UUID documentId) {
        List<KgExtractionJobView> rows = jdbcTemplate.query("""
                select job_id, document_id, status, error_code, error_message, entity_count, relation_count,
                       payload_path, graph_builder_status
                from kg_extraction_job
                where document_id = ?
                order by created_at desc
                limit 1
                """, (rs, rowNum) -> new KgExtractionJobView(
                rs.getObject("job_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                KgExtractionStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                (Integer) rs.getObject("entity_count"),
                (Integer) rs.getObject("relation_count"),
                rs.getString("payload_path"),
                GraphBuilderSyncStatus.valueOf(rs.getString("graph_builder_status"))
        ), documentId);
        return rows.stream().findFirst();
    }

    public Optional<Map<String, Object>> findPaperPayload(UUID documentId) {
        List<String> rows = jdbcTemplate.query("""
                select payload_json::text
                from kg_paper_graph_payload
                where document_id = ?
                """, (rs, rowNum) -> rs.getString(1), documentId);
        return rows.stream().findFirst().map(this::readJsonMap);
    }

    public List<Map<String, Object>> findChunkEntities(UUID documentId) {
        return jdbcTemplate.query("""
                select chunk_id, mention_text, canonical_name, entity_type, normalized_key,
                       aliases_json::text as aliases_json, evidence_text, confidence
                from kg_chunk_entity
                where document_id = ?
                order by id asc
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", rs.getString("chunk_id"));
            row.put("mentionText", rs.getString("mention_text"));
            row.put("canonicalName", rs.getString("canonical_name"));
            row.put("entityType", rs.getString("entity_type"));
            row.put("normalizedKey", rs.getString("normalized_key"));
            row.put("aliases", readJsonValue(rs.getString("aliases_json")));
            row.put("evidenceText", rs.getString("evidence_text"));
            row.put("confidence", rs.getDouble("confidence"));
            return row;
        }, documentId);
    }

    public List<Map<String, Object>> findChunkRelations(UUID documentId) {
        return jdbcTemplate.query("""
                select chunk_id, head_normalized_key, relation_type, tail_normalized_key, evidence_text, confidence
                from kg_chunk_relation
                where document_id = ?
                order by id asc
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", rs.getString("chunk_id"));
            row.put("headNormalizedKey", rs.getString("head_normalized_key"));
            row.put("relationType", rs.getString("relation_type"));
            row.put("tailNormalizedKey", rs.getString("tail_normalized_key"));
            row.put("evidenceText", rs.getString("evidence_text"));
            row.put("confidence", rs.getDouble("confidence"));
            return row;
        }, documentId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize KG payload", e);
        }
    }

    private String jsonOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Map<String, Object> readJsonMap(String value) {
        Object parsed = readJsonValue(value);
        if (parsed instanceof Map<?, ?> map) {
            return map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()),
                    Map.Entry::getValue,
                    (left, right) -> right,
                    LinkedHashMap::new
            ));
        }
        return Map.of("raw", parsed);
    }

    private Object readJsonValue(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse KG json payload", e);
        }
    }
}
