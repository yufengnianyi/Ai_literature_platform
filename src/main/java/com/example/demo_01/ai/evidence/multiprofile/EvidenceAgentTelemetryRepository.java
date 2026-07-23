package com.example.demo_01.ai.evidence.multiprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class EvidenceAgentTelemetryRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public void insertStep(UUID batchId,
                           UUID documentId,
                           String questionId,
                           String agentName,
                           int attempt,
                           int llmCalls,
                           Integer promptTokens,
                           Integer completionTokens,
                           int retryCount,
                           Long elapsedMs,
                           boolean success,
                           Map<String, Object> detail,
                           String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO evidence_agent_step (
                    batch_id, document_id, question_id, agent_name, attempt,
                    llm_calls, prompt_tokens, completion_tokens, retry_count,
                    elapsed_ms, success, detail_json, error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, batchId, documentId, questionId, agentName, attempt,
                llmCalls, promptTokens, completionTokens, retryCount, elapsedMs,
                success, toJson(detail == null ? Map.of() : detail), truncate(errorMessage, 4000));
    }

    public void upsertCoverageAudit(UUID batchId,
                                    UUID documentId,
                                    String questionId,
                                    int candidateCount,
                                    int extractedBefore,
                                    int recoveredCount,
                                    int extractedAfter,
                                    List<?> candidates,
                                    List<String> recoveredFingerprints) {
        jdbcTemplate.update("""
                INSERT INTO evidence_coverage_audit (
                    batch_id, document_id, question_id, candidate_count,
                    extracted_before, recovered_count, extracted_after,
                    candidates_json, recovered_fingerprints_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (batch_id, document_id, question_id) DO UPDATE
                SET candidate_count = EXCLUDED.candidate_count,
                    extracted_before = EXCLUDED.extracted_before,
                    recovered_count = EXCLUDED.recovered_count,
                    extracted_after = EXCLUDED.extracted_after,
                    candidates_json = EXCLUDED.candidates_json,
                    recovered_fingerprints_json = EXCLUDED.recovered_fingerprints_json
                """, batchId, documentId, questionId, candidateCount, extractedBefore,
                recoveredCount, extractedAfter, toJson(candidates == null ? List.of() : candidates),
                toJson(recoveredFingerprints == null ? List.of() : recoveredFingerprints));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize agent telemetry JSON", e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
