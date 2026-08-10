package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceConfigScope;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class EvidenceExtractionAgent {

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private Q1PromptOnlyMarkdownParser promptOnlyMarkdownParser;

    @Resource
    private EvidenceProperties properties;

    @Autowired(required = false)
    private EvidenceConfigScope configScope;

    @Resource
    private ObjectMapper objectMapper;

    /** Honours a per-run configuration override when an extraction run pinned one. */
    private EvidenceProperties config() {
        return configScope == null ? properties : configScope.current();
    }

    public List<ValidatedEvidenceRow> extract(SourceDocument document,
                                              EvidenceProfile profile,
                                              List<EvidenceChunk> chunks) {
        if (isQ1PromptOnlyMarkdown(profile)) {
            return extractQ1PromptOnlyMarkdown(document, profile, chunks);
        }
        String systemPrompt = PromptResources.load(
                PromptCatalog.EVIDENCE_MULTI_PROFILE_EXTRACTION_SYSTEM);
        String baseUserMessage = extractionInput(document, profile, chunks);
        Exception lastError = null;
        for (int attempt = 1; attempt <= config().getMaxAttempts(); attempt++) {
            String userMessage = markdownRetryMessage(baseUserMessage, lastError, profile);
            try {
                String raw = responseText(chatClient.chatCore(
                        SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
                return promptOnlyMarkdownParser.parse(raw, profile);
            } catch (Exception e) {
                lastError = e;
                log.warn("Evidence profile {} attempt {}/{} failed for document {}: {}",
                        profile.questionId(), attempt, config().getMaxAttempts(),
                        document.documentId(), message(e));
            }
        }
        throw new IllegalStateException("Evidence profile " + profile.questionId()
                + " failed after " + config().getMaxAttempts() + " attempts", lastError);
    }

    private List<ValidatedEvidenceRow> extractQ1PromptOnlyMarkdown(SourceDocument document,
                                                                   EvidenceProfile profile,
                                                                   List<EvidenceChunk> chunks) {
        String systemPrompt = PromptResources.load(
                PromptCatalog.EVIDENCE_Q1_PROMPT_ONLY_MARKDOWN_SYSTEM);
        String baseUserMessage = q1PromptOnlyInput(document, chunks);
        Exception lastError = null;
        for (int attempt = 1; attempt <= config().getMaxAttempts(); attempt++) {
            String userMessage = q1MarkdownRetryMessage(baseUserMessage, lastError);
            try {
                String raw = responseText(chatClient.chatCore(
                        SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
                return promptOnlyMarkdownParser.parse(raw, profile);
            } catch (Exception e) {
                lastError = e;
                log.warn("Q1 prompt-only Markdown attempt {}/{} failed for document {}: {}",
                        attempt, config().getMaxAttempts(), document.documentId(), message(e));
            }
        }
        throw new IllegalStateException("Q1 prompt-only Markdown extraction failed after "
                + config().getMaxAttempts() + " attempts", lastError);
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

    private String q1PromptOnlyInput(SourceDocument document, List<EvidenceChunk> chunks) {
        return """
                Task: Extract Q1 antimicrobial compound evidence against oomycetes from this paper.

                %s

                Supplied chunks:
                %s
                """.formatted(documentMetadata(document), renderChunks(chunks));
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

    private String markdownRetryMessage(String base, Exception error, EvidenceProfile profile) {
        if (error == null) {
            return base;
        }
        return base + "\n\nPrevious output failed parsing: " + message(error)
                + """

                Return the complete corrected Markdown table only. Use exactly this header row:
                | %s |
                Include one separator row. Do not add prose, JSON, code fences, N/A, 未报道, or 未知.
                If no evidence qualifies, return the required header and separator rows with no data rows.
                """.formatted(String.join(" | ", expectedMarkdownHeaders(profile)));
    }

    private String q1MarkdownRetryMessage(String base, Exception error) {
        if (error == null) {
            return base;
        }
        return base + "\n\nPrevious output failed parsing: " + message(error)
                + """

                Return the complete corrected Markdown table only. Use exactly the required
                Chinese 16-column header row and one separator row. Do not add prose, JSON,
                code fences, N/A, 未报道, or 未知. If no evidence qualifies, return the same
                required header and separator rows with no data rows.
                """;
    }

    private boolean isQ1PromptOnlyMarkdown(EvidenceProfile profile) {
        return "Q1".equals(profile.questionId())
                && config().getQ1() != null
                && config().getQ1().getPromptOnlyMarkdown() != null
                && config().getQ1().getPromptOnlyMarkdown().isEnabled();
    }

    private List<String> expectedMarkdownHeaders(EvidenceProfile profile) {
        return profile.headers();
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
