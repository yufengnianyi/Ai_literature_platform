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

    public static final String KG_CHUNK_ENTITY_EXTRACTION_SYSTEM = "prompts/kg/chunk-entity-extraction-system.txt";
    public static final String KG_CHUNK_ENTITY_EXTRACTION_USER = "prompts/kg/chunk-entity-extraction-user.txt";
    public static final String KG_CHUNK_RELATION_EXTRACTION_SYSTEM = "prompts/kg/chunk-relation-extraction-system.txt";
    public static final String KG_CHUNK_RELATION_EXTRACTION_USER = "prompts/kg/chunk-relation-extraction-user.txt";

    public static final String REVIEW_QUERY_ANALYZER_SYSTEM = "prompts/review/query-analyzer-system.txt";
    public static final String REVIEW_PAPER_EVIDENCE_TABLE_SYNTHESIS_SYSTEM =
            "prompts/review/paper-evidence-table-synthesis-system.txt";
    public static final String REVIEW_PAPER_EVIDENCE_TABLE_SYNTHESIS_ANTIMICROBIAL_SYSTEM =
            "prompts/review/paper-evidence-table-synthesis-antimicrobial-system.txt";
    public static final String REVIEW_CONCENTRATION_EXTRACTION_SYSTEM =
            "prompts/review/concentration-extraction-system.txt";
    public static final String REVIEW_REPORT_PAPER_CENTRIC_SYSTEM =
            "prompts/review/report-paper-centric-system.txt";
    public static final String REVIEW_REPORT_PAPER_BATCH_SUMMARY_SYSTEM =
            "prompts/review/report-paper-batch-summary-system.txt";
    public static final String REVIEW_REPORT_LOCALIZE_SUMMARY_SYSTEM =
            "prompts/review/report-localize-summary-system.txt";

    private PromptCatalog() {
    }
}
