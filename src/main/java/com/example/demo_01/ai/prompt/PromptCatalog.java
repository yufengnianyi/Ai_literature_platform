package com.example.demo_01.ai.prompt;

/**
 * Canonical classpath locations for prompt resources.
 *
 * <p>Constants used by {@code @SystemMessage(fromResource = ...)} must remain
 * compile-time constant expressions. Do not replace them with getters, maps,
 * enum fields, or runtime-built values.</p>
 */
public final class PromptCatalog {

    public static final String AI_CODE_HELPER_SYSTEM = "prompts/ai/ai-code-helper-system.txt";
    public static final String AI_CODE_HELPER_SERVICE_SYSTEM = "prompts/ai/ai-code-helper-service-system.txt";
    public static final String AI_RAG_CHAT_SYSTEM = "prompts/ai/rag-chat-system.txt";
    public static final String AI_Q1_EVIDENCE_CHAT_SYSTEM = "prompts/ai/q1-evidence-chat-system.txt";
    public static final String AI_Q1_COMPOUND_REFERENCE_RESOLUTION_SYSTEM =
            "prompts/ai/q1-compound-reference-resolution-system.txt";

    public static final String KG_CHUNK_ENTITY_EXTRACTION_SYSTEM = "prompts/kg/chunk-entity-extraction-system.txt";
    public static final String KG_CHUNK_ENTITY_EXTRACTION_USER = "prompts/kg/chunk-entity-extraction-user.txt";
    public static final String KG_CHUNK_RELATION_EXTRACTION_SYSTEM = "prompts/kg/chunk-relation-extraction-system.txt";
    public static final String KG_CHUNK_RELATION_EXTRACTION_USER = "prompts/kg/chunk-relation-extraction-user.txt";

    public static final String REVIEW_QUERY_ANALYZER_SYSTEM = "prompts/review/query-analyzer-system.txt";
    public static final String RAG_DOCUMENT_ENTITY_EXTRACTION_SYSTEM =
            "prompts/rag/document-entity-extraction-system.txt";
    public static final String RAG_EVALUATION_DOCUMENT_JUDGMENT_SYSTEM =
            "prompts/rag/evaluation/document-judgment-system.txt";
    public static final String RAG_EVALUATION_ANTIMICROBIAL_CLASSIFICATION_SYSTEM =
            "prompts/rag/evaluation/antimicrobial-classification-system.txt";
    public static final String RAG_EVALUATION_ANTIMICROBIAL_SUMMARY_SYSTEM =
            "prompts/rag/evaluation/antimicrobial-summary-system.txt";
    public static final String EVIDENCE_ANTIMICROBIAL_COMPOUND_TABLE_SYSTEM =
            "prompts/evidence/antimicrobial-compound-table-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_CLASSIFICATION_SYSTEM =
            "prompts/evidence/multi-profile-classification-system.txt";
    public static final String EVIDENCE_Q1_EXTRACTION_SYSTEM =
            "prompts/evidence/q1-extraction-system.txt";
    public static final String EVIDENCE_Q2_EXTRACTION_SYSTEM =
            "prompts/evidence/q2-extraction-system.txt";
    public static final String EVIDENCE_Q3_EXTRACTION_SYSTEM =
            "prompts/evidence/q3-extraction-system.txt";
    public static final String EVIDENCE_Q4_EXTRACTION_SYSTEM =
            "prompts/evidence/q4-extraction-system.txt";
    public static final String EVIDENCE_Q5_EXTRACTION_SYSTEM =
            "prompts/evidence/q5-extraction-system.txt";
    public static final String EVIDENCE_Q6_EXTRACTION_SYSTEM =
            "prompts/evidence/q6-extraction-system.txt";
    public static final String EVIDENCE_Q7_EXTRACTION_SYSTEM =
            "prompts/evidence/q7-extraction-system.txt";
    public static final String EVIDENCE_Q8_EXTRACTION_SYSTEM =
            "prompts/evidence/q8-extraction-system.txt";
    public static final String EVIDENCE_Q9_EXTRACTION_SYSTEM =
            "prompts/evidence/q9-extraction-system.txt";
    public static final String EVIDENCE_Q10_EXTRACTION_SYSTEM =
            "prompts/evidence/q10-extraction-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_VERIFY_SYSTEM =
            "prompts/evidence/multi-profile-verify-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_COVERAGE_SYSTEM =
            "prompts/evidence/multi-profile-coverage-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_RETRIEVAL_SYSTEM =
            "prompts/evidence/multi-profile-retrieval-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_TABLE_SELECT_SYSTEM =
            "prompts/evidence/multi-profile-table-select-system.txt";
    public static final String EVIDENCE_LINEARIZED_TABLE_RECOVERY_SYSTEM =
            "prompts/evidence/linearized-table-recovery-system.txt";
    public static final String REPORT_EVIDENCE_SYSTEM =
            "prompts/report/evidence-report-system.txt";
    public static final String REPORT_EVIDENCE_CITATION_REPAIR_SYSTEM =
            "prompts/report/evidence-report-citation-repair-system.txt";
    public static final String REPORT_FULL_DOCUMENT_BATCH_SYSTEM =
            "prompts/report/full-document-batch-system.txt";
    public static final String REPORT_SECTION_SYNTHESIS_SYSTEM =
            "prompts/report/section-synthesis-system.txt";

    public static String evidenceQuestionExtractionSystem(String questionId) {
        return switch (questionId) {
            case "Q1" -> EVIDENCE_Q1_EXTRACTION_SYSTEM;
            case "Q2" -> EVIDENCE_Q2_EXTRACTION_SYSTEM;
            case "Q3" -> EVIDENCE_Q3_EXTRACTION_SYSTEM;
            case "Q4" -> EVIDENCE_Q4_EXTRACTION_SYSTEM;
            case "Q5" -> EVIDENCE_Q5_EXTRACTION_SYSTEM;
            case "Q6" -> EVIDENCE_Q6_EXTRACTION_SYSTEM;
            case "Q7" -> EVIDENCE_Q7_EXTRACTION_SYSTEM;
            case "Q8" -> EVIDENCE_Q8_EXTRACTION_SYSTEM;
            case "Q9" -> EVIDENCE_Q9_EXTRACTION_SYSTEM;
            case "Q10" -> EVIDENCE_Q10_EXTRACTION_SYSTEM;
            default -> throw new IllegalArgumentException(
                    "Unknown evidence extraction question: " + questionId);
        };
    }

    private PromptCatalog() {
    }
}
