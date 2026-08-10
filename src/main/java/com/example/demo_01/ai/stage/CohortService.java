package com.example.demo_01.ai.stage;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class CohortService {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Transactional
    public UUID create(String name, String sourceType, UUID sourceRefId,
                       List<UUID> documentIds, String reason) {
        List<UUID> ids = documentIds == null ? List.of() : documentIds;
        String inputHash = inputHash(ids);
        UUID existing = jdbcTemplate.query("""
                SELECT cohort_id
                FROM document_cohort
                WHERE name = ?
                LIMIT 1
                """, (rs, rowNum) -> rs.getObject("cohort_id", UUID.class), name)
                .stream().findFirst().orElse(null);
        UUID cohortId = existing == null ? UUID.randomUUID() : existing;
        jdbcTemplate.update("""
                INSERT INTO document_cohort (
                    cohort_id, name, source_type, source_ref_id, input_hash, frozen
                ) VALUES (?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (name) DO UPDATE
                SET source_type = EXCLUDED.source_type,
                    source_ref_id = EXCLUDED.source_ref_id,
                    input_hash = EXCLUDED.input_hash,
                    frozen = TRUE
                """, cohortId, name, sourceType, sourceRefId, inputHash);
        jdbcTemplate.update("DELETE FROM cohort_member WHERE cohort_id = ?", cohortId);
        int ordinal = 0;
        for (UUID documentId : ids) {
            ordinal++;
            jdbcTemplate.update("""
                    INSERT INTO cohort_member (
                        cohort_id, document_id, ordinal, added_reason
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT (cohort_id, document_id) DO NOTHING
                    """, cohortId, documentId, ordinal, reason);
        }
        return cohortId;
    }

    public List<UUID> findDocumentIds(UUID cohortId) {
        if (cohortId == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT document_id
                FROM cohort_member
                WHERE cohort_id = ?
                ORDER BY ordinal, document_id
                """, (rs, rowNum) -> rs.getObject("document_id", UUID.class), cohortId);
    }

    public String inputHash(List<UUID> documentIds) {
        return sha256((documentIds == null ? List.<UUID>of() : documentIds).stream()
                .map(UUID::toString)
                .reduce((left, right) -> left + "\n" + right)
                .orElse(""));
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
