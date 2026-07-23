package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class EvidenceExtractionAgent {

    private static final String SCHEMA_APPENDIX = """

            Constrained output contract:
            - Return exactly one JSON object with shape {"rows":[...]}.
            - Each row must contain "cells" (array of strings, exact header count) and "anchors".
            - Each anchor must contain "chunkId" and "exactQuote".
            - Do not wrap the JSON in Markdown fences.
            - If no evidence qualifies, return {"rows":[]}.
            """;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private MultiProfileOutputValidator outputValidator;

    @Resource
    private EvidenceProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public List<ValidatedEvidenceRow> extract(SourceDocument document,
                                              EvidenceProfile profile,
                                              List<EvidenceChunk> chunks) {
        String systemPrompt = PromptResources.load(
                PromptCatalog.EVIDENCE_MULTI_PROFILE_EXTRACTION_SYSTEM);
        if (properties.getAgents().getConstrainedDecoding().isEnabled()) {
            systemPrompt = systemPrompt + SCHEMA_APPENDIX;
        }
        String baseUserMessage = extractionInput(document, profile, chunks);
        Exception lastError = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            String userMessage = retryMessage(baseUserMessage, lastError);
            try {
                String raw = responseText(chatClient.chatCore(
                        SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
                if (properties.getAgents().getConstrainedDecoding().isEnabled()) {
                    assertJsonEnvelope(raw);
                }
                return outputValidator.parseAndValidateEvidence(raw, profile, chunks);
            } catch (Exception e) {
                lastError = e;
                log.warn("Evidence profile {} attempt {}/{} failed for document {}: {}",
                        profile.questionId(), attempt, properties.getMaxAttempts(),
                        document.documentId(), message(e));
            }
        }
        throw new IllegalStateException("Evidence profile " + profile.questionId()
                + " failed after " + properties.getMaxAttempts() + " attempts", lastError);
    }

    private void assertJsonEnvelope(String raw) {
        String json = extractJson(raw);
        try {
            var node = objectMapper.readTree(json);
            if (!node.isObject() || !node.has("rows") || !node.get("rows").isArray()) {
                throw new IllegalArgumentException(
                        "Constrained decoding requires a JSON object with a rows array");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Constrained decoding rejected non-JSON output", e);
        }
    }

    private String extractionInput(SourceDocument document,
                                   EvidenceProfile profile,
                                   List<EvidenceChunk> chunks) {
        return """
                Evidence profile:
                - questionId: %s
                - title: %s
                - scope: %s
                - one row represents: %s
                - split rules: %s
                - field guidance: %s
                - headers in exact order: %s
                - required primary fields: %s

                %s

                Supplied chunks:
                %s
                """.formatted(
                profile.questionId(), profile.title(), profile.scope(), profile.rowUnit(),
                profile.splitRules(), profile.guidance(), toJson(profile.headers()),
                profile.primaryFieldIndexes().stream()
                        .map(profile.headers()::get).toList(),
                documentMetadata(document), renderChunks(chunks));
    }

    private String documentMetadata(SourceDocument document) {
        return """
                Document metadata:
                - document_id: %s
                - title: %s
                - authors: %s
                - publication_year: %s
                - journal: %s
                - doi: %s
                """.formatted(
                document.documentId(), value(document.title()),
                String.join(", ", document.authors() == null ? List.of() : document.authors()),
                document.publicationYear() == null ? "" : document.publicationYear(),
                value(document.journal()), value(document.doi()));
    }

    private String renderChunks(List<EvidenceChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (EvidenceChunk chunk : chunks) {
            builder.append("\n--- chunk_id=").append(value(chunk.chunkId()))
                    .append("; section=").append(value(chunk.sectionPath()))
                    .append(" ---\n").append(value(chunk.text()));
        }
        return builder.toString();
    }

    private String retryMessage(String base, Exception error) {
        if (error == null) {
            return base;
        }
        return base + "\n\nPrevious output failed validation: " + message(error)
                + """

                Return the complete corrected JSON object. Rebuild the failing row rather than
                repeating it. Every cells array must have exactly the same number of items as the
                supplied headers. Copy every exactQuote as one continuous, character-for-character
                passage from the cited chunk; do not paraphrase, join passages, or repair its text.
                Delete any row that cannot satisfy these rules. Returning {"rows":[]} is valid.
                """;
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Model returned no JSON");
        }
        String trimmed = raw.trim();
        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("Model output does not contain a JSON object");
        }
        return trimmed.substring(objectStart, objectEnd + 1);
    }

    private String responseText(dev.langchain4j.model.chat.response.ChatResponse response) {
        if (response == null || response.aiMessage() == null
                || response.aiMessage().text() == null) {
            throw new IllegalArgumentException("Model returned no text");
        }
        return response.aiMessage().text().trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize prompt JSON", e);
        }
    }

    private String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String value = error.getMessage();
        return value == null || value.isBlank()
                ? error.getClass().getSimpleName() : value;
    }

    private String value(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
