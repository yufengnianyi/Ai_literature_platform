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
}
