package com.example.demo_01.ai.rag.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import jakarta.annotation.Resource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RagDocumentRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public void insertInitial(UUID documentId, String sourceFilename, String storageRoot, RagDocumentStatus status) {
        jdbcTemplate.update("""
                insert into rag_document (
                    document_id, source_filename, storage_root, status, preprocess_status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                documentId,
                sourceFilename,
                storageRoot,
                status.name(),
                PreprocessStatus.QUEUED.name(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
    }

    public Optional<RagDocumentRecord> findById(UUID documentId) {
        List<RagDocumentRecord> rows = jdbcTemplate.query(selectSql() + " where d.document_id = ?", this::mapRow, documentId);
        return rows.stream().findFirst();
    }

    public Optional<RagDocumentRecord> findCanonicalByDoi(String doiNormalized) {
        if (doiNormalized == null || doiNormalized.isBlank()) {
            return Optional.empty();
        }
        List<RagDocumentRecord> rows = jdbcTemplate.query(selectSql() + " where d.doi_normalized = ? and d.duplicate_of_document_id is null and d.status = 'COMPLETED'", this::mapRow, doiNormalized);
        return rows.stream().findFirst();
    }

    public Optional<RagDocumentRecord> findCanonicalByPdfSha(String pdfSha256) {
        if (pdfSha256 == null || pdfSha256.isBlank()) {
            return Optional.empty();
        }
        List<RagDocumentRecord> rows = jdbcTemplate.query(selectSql() + " where d.pdf_sha256 = ? and d.duplicate_of_document_id is null and d.status = 'COMPLETED'", this::mapRow, pdfSha256);
        return rows.stream().findFirst();
    }

    public void markProcessing(UUID documentId, RagDocumentMetadata metadata, String pdfSha256, String canonicalKey) {
        updateDocument(documentId, metadata, pdfSha256, canonicalKey, null, RagDocumentStatus.PROCESSING);
    }

    public void markCompleted(UUID documentId, RagDocumentMetadata metadata, String pdfSha256, String canonicalKey) {
        updateDocument(documentId, metadata, pdfSha256, canonicalKey, null, RagDocumentStatus.COMPLETED);
    }

    public void markDuplicate(UUID documentId, UUID canonicalDocumentId, RagDocumentMetadata metadata, String pdfSha256, String canonicalKey) {
        updateDocument(documentId, metadata, pdfSha256, canonicalKey, canonicalDocumentId, RagDocumentStatus.DUPLICATE_SKIPPED);
    }

    public void markFailed(UUID documentId) {
        jdbcTemplate.update("""
                update rag_document
                set status = ?, updated_at = ?
                where document_id = ?
                """, RagDocumentStatus.FAILED.name(), Timestamp.from(Instant.now()), documentId);
    }

    public void updatePreprocessState(UUID documentId,
                                      UUID latestPreprocessJobId,
                                      PreprocessStatus preprocessStatus) {
        jdbcTemplate.update("""
                update rag_document
                set latest_preprocess_job_id = ?,
                    preprocess_status = ?,
                    updated_at = ?
                where document_id = ?
                """,
                latestPreprocessJobId,
                preprocessStatus == null ? null : preprocessStatus.name(),
                Timestamp.from(Instant.now()),
                documentId);
    }

    public void updatePreprocessState(UUID documentId, PreprocessStatus preprocessStatus) {
        updatePreprocessState(documentId, null, preprocessStatus);
    }

    public boolean isPreprocessCompleted(UUID documentId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select preprocess_status = ?
                from rag_document
                where document_id = ?
                """, Boolean.class, PreprocessStatus.COMPLETED.name(), documentId));
    }

    public PreprocessStatus findPreprocessStatus(UUID documentId) {
        List<String> rows = jdbcTemplate.query("""
                select preprocess_status
                from rag_document
                where document_id = ?
                """, (rs, rowNum) -> rs.getString(1), documentId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return PreprocessStatus.valueOf(rows.get(0));
    }

    private void updateDocument(UUID documentId,
                                RagDocumentMetadata metadata,
                                String pdfSha256,
                                String canonicalKey,
                                UUID duplicateOfDocumentId,
                                RagDocumentStatus status) {
        jdbcTemplate.update("""
                update rag_document
                set duplicate_of_document_id = ?,
                    canonical_key = ?,
                    doi_raw = ?,
                    doi_normalized = ?,
                    pdf_sha256 = ?,
                    title = ?,
                    authors_json = cast(? as jsonb),
                    affiliations_json = cast(? as jsonb),
                    abstract_text = ?,
                    journal = ?,
                    publication_date = ?,
                    publication_year = ?,
                    status = ?,
                    updated_at = ?
                where document_id = ?
                """,
                duplicateOfDocumentId,
                canonicalKey,
                metadata == null ? null : metadata.doiRaw(),
                metadata == null ? null : metadata.doiNormalized(),
                pdfSha256,
                metadata == null ? null : metadata.title(),
                toJson(metadata == null ? List.of() : metadata.authors()),
                toJson(metadata == null ? List.of() : metadata.affiliations()),
                metadata == null ? null : metadata.abstractText(),
                metadata == null ? null : metadata.journal(),
                metadata == null ? null : metadata.publicationDate(),
                metadata == null ? null : metadata.publicationYear(),
                status.name(),
                Timestamp.from(Instant.now()),
                documentId);
    }

    private RagDocumentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        String latestJobId = rs.getString("latest_job_id");
        return new RagDocumentRecord(
                rs.getObject("document_id", UUID.class),
                rs.getObject("duplicate_of_document_id", UUID.class),
                latestJobId == null ? null : UUID.fromString(latestJobId),
                rs.getString("canonical_key"),
                rs.getString("doi_raw"),
                rs.getString("doi_normalized"),
                rs.getString("pdf_sha256"),
                rs.getString("title"),
                fromJson(rs.getString("authors_json")),
                fromJson(rs.getString("affiliations_json")),
                rs.getString("abstract_text"),
                rs.getString("journal"),
                rs.getString("publication_date"),
                (Integer) rs.getObject("publication_year"),
                rs.getString("source_filename"),
                rs.getString("storage_root"),
                RagDocumentStatus.valueOf(rs.getString("status")),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant());
    }

    private String selectSql() {
        return """
                select d.*,
                       (
                           select j.job_id::text
                           from rag_ingestion_job j
                           where j.document_id = d.document_id
                           order by j.created_at desc
                           limit 1
                       ) as latest_job_id
                from rag_document d
                """;
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize metadata list", e);
        }
    }

    private List<String> fromJson(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize metadata list", e);
        }
    }
}

