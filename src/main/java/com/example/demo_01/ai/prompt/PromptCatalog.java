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
    public static final String EVIDENCE_MULTI_PROFILE_EXTRACTION_SYSTEM =
            "prompts/evidence/multi-profile-extraction-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_VERIFY_SYSTEM =
            "prompts/evidence/multi-profile-verify-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_COVERAGE_SYSTEM =
            "prompts/evidence/multi-profile-coverage-system.txt";
    public static final String EVIDENCE_MULTI_PROFILE_RETRIEVAL_SYSTEM =
            "prompts/evidence/multi-profile-retrieval-system.txt";
    public static final String REPORT_EVIDENCE_SYSTEM =
            "prompts/report/evidence-report-system.txt";
    public static final String REPORT_EVIDENCE_CITATION_REPAIR_SYSTEM =
            "prompts/report/evidence-report-citation-repair-system.txt";
    public static final String REPORT_FULL_DOCUMENT_BATCH_SYSTEM =
            "prompts/report/full-document-batch-system.txt";
    public static final String REPORT_SECTION_SYNTHESIS_SYSTEM =
            "prompts/report/section-synthesis-system.txt";

    private PromptCatalog() {
    }
}
