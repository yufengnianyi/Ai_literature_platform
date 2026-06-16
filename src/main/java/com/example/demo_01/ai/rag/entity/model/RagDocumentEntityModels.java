package com.example.demo_01.ai.rag.entity.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

public final class RagDocumentEntityModels {

    private RagDocumentEntityModels() {
    }

    public record RagDocumentEntityExtractionRequest(
            String question
    ) {
    }

    public record RagDocumentEntityBatchExtractionRequest(
            List<UUID> documentIds,
            String question
    ) {
    }

    public record RagDocumentEntityExtraction(
            UUID documentId,
            String documentTitle,
            String question,
            int chunkCount,
            List<RagDocumentEntity> entities,
            List<String> warnings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RagDocumentEntity(
            String mentionText,
            String canonicalName,
            String entityType,
            List<String> aliases,
            List<String> sourceChunkIds,
            List<String> evidenceTexts,
            double confidence
    ) {
    }
}
