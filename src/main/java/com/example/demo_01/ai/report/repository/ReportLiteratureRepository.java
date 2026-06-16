package com.example.demo_01.ai.report.repository;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureAnalysisStatus;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureProfile;
import com.example.demo_01.ai.report.model.ReportModels.ReportClaimDraft;
import com.example.demo_01.ai.report.model.ReportModels.ReportDocumentChunk;
import com.example.demo_01.ai.report.model.ReportModels.SelectedLiterature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Repository
public class ReportLiteratureRepository {

    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate jdbcTemplate;
    private final AiPersistenceProperties persistenceProperties;
    private final ObjectMapper objectMapper;

    public ReportLiteratureRepository(JdbcTemplate jdbcTemplate,
                                      AiPersistenceProperties persistenceProperties,
                                      ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.persistenceProperties = persistenceProperties;
        this.objectMapper = objectMapper;
    }

    public List<ReportDocumentChunk> findDocumentChunks(UUID documentId) {
        return jdbcTemplate.query("""
                select coalesce(metadata->>'chunk_id', embedding_id::text) as chunk_id,
                       case when metadata->>'chunk_index' ~ '^[0-9]+$'
                            then (metadata->>'chunk_index')::int
                            else 2147483647 end as chunk_index,
                       coalesce(metadata->>'section_path', '') as section_path,
                       coalesce(text, '') as text
                from %s
                where metadata->>'document_id' = ?
                  and btrim(coalesce(text, '')) <> ''
                order by chunk_index, chunk_id
                """.formatted(vectorTable()), (rs, rowNum) -> new ReportDocumentChunk(
                rs.getString("chunk_id"),
                rs.getInt("chunk_index"),
                rs.getString("section_path"),
                rs.getString("text")
        ), documentId.toString());
    }

    public boolean hasDocumentChunks(UUID documentId) {
        Boolean present = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from %s
                    where metadata->>'document_id' = ?
                      and btrim(coalesce(text, '')) <> ''
                )
                """.formatted(vectorTable()), Boolean.class, documentId.toString());
        return Boolean.TRUE.equals(present);
    }

    public Optional<LiteratureProfile> findCachedProfile(UUID documentId,
                                                         String documentHash,
                                                         String promptHash,
                                                         String modelName) {
        return jdbcTemplate.query("""
                select analysis_json::text
                from report_literature_analysis_cache
                where document_id = ?
                  and document_hash = ?
                  and prompt_hash = ?
                  and model_name = ?
                order by analyzed_at desc
                limit 1
                """, (rs, rowNum) -> readProfile(rs.getString(1)),
                documentId, documentHash, promptHash, modelName).stream().findFirst();
    }

    public void saveCachedProfile(LiteratureProfile profile,
                                  String promptHash,
                                  String modelName,
                                  int chunkCount) {
        jdbcTemplate.update("""
                insert into report_literature_analysis_cache (
                    cache_id, document_id, document_hash, prompt_hash, model_name,
                    analysis_json, chunk_count
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), ?)
                on conflict (document_id, document_hash, prompt_hash, model_name)
                do update set analysis_json = excluded.analysis_json,
                              chunk_count = excluded.chunk_count,
                              analyzed_at = CURRENT_TIMESTAMP
                """, UUID.randomUUID(), profile.documentId(), profile.documentHash(),
                promptHash, modelName, writeJson(profile), chunkCount);
    }

    @Transactional
    public void replaceLiterature(UUID reportId, List<SelectedLiterature> documents) {
        jdbcTemplate.update("delete from report_literature_link where report_id = ?", reportId);
        for (SelectedLiterature document : documents) {
            jdbcTemplate.update("""
                    insert into report_literature_link (
                        report_id, document_id, source_type, rank, relevance_score,
                        selection_reason, analysis_status
                    ) values (?, ?, ?, ?, ?, ?, 'PENDING')
                    """, reportId, document.documentId(), document.sourceType().name(),
                    document.rank(), document.relevanceScore(), document.selectionReason());
        }
    }

    public void updateLiteratureStatus(UUID reportId,
                                       UUID documentId,
                                       LiteratureAnalysisStatus status,
                                       String errorMessage) {
        jdbcTemplate.update("""
                update report_literature_link
                set analysis_status = ?,
                    error_message = ?,
                    updated_at = CURRENT_TIMESTAMP
                where report_id = ? and document_id = ?
                """, status.name(), truncate(errorMessage, 2000), reportId, documentId);
    }

    @Transactional
    public void replaceClaims(UUID reportId, List<ReportClaimDraft> claims) {
        jdbcTemplate.update("delete from report_claim where report_id = ?", reportId);
        for (ReportClaimDraft claim : claims) {
            UUID claimId = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into report_claim (claim_id, report_id, section_key, claim_text)
                    values (?, ?, ?, ?)
                    """, claimId, reportId, claim.sectionKey(), claim.text());
            for (UUID evidenceId : claim.evidenceIds()) {
                jdbcTemplate.update("""
                        insert into report_claim_evidence (claim_id, evidence_id)
                        values (?, ?)
                        on conflict do nothing
                        """, claimId, evidenceId);
            }
            claim.chunksByDocument().forEach((documentId, chunkIds) -> {
                for (String chunkId : chunkIds) {
                    jdbcTemplate.update("""
                            insert into report_claim_chunk (claim_id, document_id, chunk_id)
                            values (?, ?, ?)
                            on conflict do nothing
                            """, claimId, documentId, chunkId);
                }
            });
        }
    }

    private LiteratureProfile readProfile(String json) {
        try {
            return objectMapper.readValue(json, LiteratureProfile.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read cached literature profile", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize literature analysis", e);
        }
    }

    private String vectorTable() {
        String table = persistenceProperties.getRag().getVectorTable();
        if (!SAFE_SQL_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + table);
        }
        return table;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
