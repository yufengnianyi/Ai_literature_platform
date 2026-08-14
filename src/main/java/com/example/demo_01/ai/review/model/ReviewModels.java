package com.example.demo_01.ai.review.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * Shared retrieval / query-analysis types used by report generation and RAG evaluation.
 * Legacy review-task pipeline models were removed with V20 and the review_task tables.
 */
public final class ReviewModels {

    private ReviewModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryAnalysis(
            String mainQuestion,
            List<String> subQuestions,
            List<String> keyEntities,
            List<String> keyConcepts,
            String languageCode,
            String displayMainQuestion,
            List<String> displaySubQuestions
    ) {
        public QueryAnalysis(String mainQuestion,
                             List<String> subQuestions,
                             List<String> keyEntities,
                             List<String> keyConcepts) {
            this(mainQuestion, subQuestions, keyEntities, keyConcepts,
                    null, null, null);
        }
    }

    public record RetrievedChunk(
            String chunkId,
            UUID documentId,
            String documentTitle,
            String text,
            String sectionPath,
            double score,
            String source
    ) {
    }
}
