package com.example.demo_01.ai.rag;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Retrieval-augmented generation settings for the interactive chat endpoint (/ai).
 *
 * <p>These parameters only control how retrieved context is injected into a live
 * conversation. The underlying hybrid retriever is configured separately via
 * {@link RagRetrievalProperties} ({@code app.ai.rag.retrieval}).</p>
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.rag.chat")
public class RagChatProperties {

    /** Master switch. When false, the chat endpoint behaves as a plain LLM chat. */
    private boolean enabled = true;

    /** Maximum number of retrieved chunks injected into the prompt context. */
    @Min(1)
    private int maxContextChunks = 5;

    /** Maximum characters kept per chunk when building the context block. */
    @Min(1)
    private int maxExcerptChars = 600;

    /** Hard cap on the total characters of the assembled context block. */
    @Min(1)
    private int maxContextChars = 6000;

    @NestedConfigurationProperty
    private Rerank rerank = new Rerank();

    @NestedConfigurationProperty
    private Q1Evidence q1Evidence = new Q1Evidence();

    /**
     * Cross-encoder rerank applied to the fused candidate chunks before they are
     * injected into the prompt. Reuses the DashScope rerank endpoint / API key
     * configured under {@code app.ai.rag.evaluation.rerank}.
     */
    @Data
    public static class Rerank {

        /** When true, retrieved chunks are reordered by the Qwen rerank model. */
        private boolean enabled = true;

        /** Qwen-series rerank model name (DashScope text-rerank service). */
        private String model = "qwen3-vl-rerank";

        /** Drop chunks whose rerank relevance score is below this threshold. */
        @Min(0)
        private double minScore = 0.2;

        /** Maximum characters of each chunk fed into the rerank request. */
        @Min(1)
        private int inputMaxChars = 2000;
    }

    @Data
    public static class Q1Evidence {

        /** When true, chat answers can use the curated Q1 compound evidence table. */
        private boolean enabled = true;

        /**
         * Hard cap on total Q1 evidence rows injected into the prompt after diversity
         * selection (across all compounds).
         */
        @Min(1)
        private int maxRows = 90;

        /** Target number of distinct compounds covered by the diversity selector. */
        @Min(1)
        private int maxDistinctCompounds = 50;

        /** Maximum rows contributed by any single compound during diversity selection. */
        @Min(1)
        private int maxRowsPerCompound = 3;

        /**
         * Maximum number of distinct compounds that may be pulled from any single source
         * document, so one large SAR-style paper cannot dominate the answer.
         */
        @Min(1)
        private int maxCompoundsPerDocument = 5;

        /** Maximum characters kept for each rendered evidence row. */
        @Min(1)
        private int maxRowChars = 1400;

        /** Maximum characters of Q1 evidence context injected into the prompt. */
        @Min(1)
        private int maxContextChars = 45000;

        /**
         * Maximum number of per-document LLM calls used to resolve LOCAL_LABEL /
         * NATURAL_EXTRACT compound names in a single chat turn.
         */
        @Min(0)
        private int maxResolutionCallsPerTurn = 10;

        /** Timeout in milliseconds for each compound-reference resolution LLM call. */
        @Min(1)
        private long resolutionTimeoutMs = 8000;

        /**
         * Maximum number of original-document full-text chunks (the source paper's parsed
         * Markdown/text, not the structured evidence table) fed into each resolution call as
         * extra context when the structured sibling rows alone are not enough to identify a
         * compound's parent/source subject.
         */
        @Min(0)
        private int maxResolutionChunksPerDocument = 6;

        /** Hard cap on characters of original-document text injected into a resolution call. */
        @Min(0)
        private int resolutionChunkContextChars = 3000;
    }
}
