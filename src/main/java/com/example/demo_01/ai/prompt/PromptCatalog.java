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

    public static final String REVIEW_DOCUMENT_KNOWLEDGE_ENRICHMENT_SYSTEM =
            "prompts/review/document-knowledge-enrichment-system.txt";
    public static final String REVIEW_DOCUMENT_KNOWLEDGE_ENRICHMENT_USER =
            "prompts/review/document-knowledge-enrichment-user.txt";
    public static final String REVIEW_DOCUMENT_KNOWLEDGE_ENRICHMENT_CHUNK =
            "prompts/review/document-knowledge-enrichment-chunk.txt";
    public static final String REVIEW_DOCUMENT_PROMOTION_SYSTEM = "prompts/review/document-promotion-system.txt";
    public static final String REVIEW_DOCUMENT_PROMOTION_USER = "prompts/review/document-promotion-user.txt";
    public static final String REVIEW_DOCUMENT_PROMOTION_DOCUMENT = "prompts/review/document-promotion-document.txt";
    public static final String REVIEW_EVIDENCE_EXTRACTION_SYSTEM = "prompts/review/evidence-extraction-system.txt";
    public static final String REVIEW_EVIDENCE_EXTRACTION_USER = "prompts/review/evidence-extraction-user.txt";
    public static final String REVIEW_EVIDENCE_EXTRACTION_CHUNK = "prompts/review/evidence-extraction-chunk.txt";
    public static final String REVIEW_EVIDENCE_FUSION_SYSTEM = "prompts/review/evidence-fusion-system.txt";
    public static final String REVIEW_EVIDENCE_FUSION_SINGLE_USER =
            "prompts/review/evidence-fusion-single-user.txt";
    public static final String REVIEW_EVIDENCE_FUSION_SUMMARY_USER =
            "prompts/review/evidence-fusion-summary-user.txt";
    public static final String REVIEW_EVIDENCE_FUSION_BATCH_SUMMARY_SYSTEM =
            "prompts/review/evidence-fusion-batch-summary-system.txt";
    public static final String REVIEW_EVIDENCE_FUSION_BATCH_SUMMARY_USER =
            "prompts/review/evidence-fusion-batch-summary-user.txt";
    public static final String REVIEW_QUERY_ANALYZER_SYSTEM = "prompts/review/query-analyzer-system.txt";
    public static final String REVIEW_RERANKER_SYSTEM = "prompts/review/reranker-system.txt";
    public static final String REVIEW_RERANKER_USER = "prompts/review/reranker-user.txt";
    public static final String REVIEW_RERANKER_CHUNK = "prompts/review/reranker-chunk.txt";
    public static final String REVIEW_COMPOUND_SYNTHESIS_SYSTEM =
            "prompts/review/compound-synthesis-system.txt";
    public static final String REVIEW_COMPOUND_SYNTHESIS_USER =
            "prompts/review/compound-synthesis-user.txt";

    private PromptCatalog() {
    }
}
