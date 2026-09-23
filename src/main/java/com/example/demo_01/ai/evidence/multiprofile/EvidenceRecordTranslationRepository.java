package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.EvidenceRecordTranslation;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.TranslationStatus;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvidenceRecordTranslationRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public void save(EvidenceRecordTranslation translation) {
        jdbcTemplate.update("""
                INSERT INTO evidence_record_translation (
                    translation_id, record_id, language_code, profile_version,
                    translator_model, prompt_hash, payload_json, status, error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (record_id, language_code, prompt_hash) DO UPDATE
                SET profile_version = EXCLUDED.profile_version,
                    translator_model = EXCLUDED.translator_model,
                    payload_json = EXCLUDED.payload_json,
                    status = EXCLUDED.status,
                    error_message = EXCLUDED.error_message,
                    updated_at = CURRENT_TIMESTAMP
                """, translation.translationId(), translation.recordId(),
                translation.languageCode(), translation.profileVersion(),
                translation.translatorModel(), translation.promptHash(),
                toJson(translation.payload()), translation.status().name(),
                translation.errorMessage());
    }

    public Optional<EvidenceRecordTranslation> findLatest(UUID recordId, String languageCode) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_record_translation
                WHERE record_id = ? AND language_code = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """, this::mapTranslation, recordId, languageCode).stream().findFirst();
    }

    public List<EvidenceRecordTranslation> findAll(UUID recordId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM evidence_record_translation
                WHERE record_id = ?
                ORDER BY language_code, updated_at DESC
                """, this::mapTranslation, recordId);
    }

    private EvidenceRecordTranslation mapTranslation(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceRecordTranslation(
                rs.getObject("translation_id", UUID.class),
                rs.getObject("record_id", UUID.class),
                rs.getString("language_code"),
                rs.getString("profile_version"),
                rs.getString("translator_model"),
                rs.getString("prompt_hash"),
                fromJsonMap(rs.getString("payload_json")),
                TranslationStatus.valueOf(rs.getString("status")),
                rs.getString("error_message"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private String toJson(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize evidence translation", e);
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse evidence translation", e);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
