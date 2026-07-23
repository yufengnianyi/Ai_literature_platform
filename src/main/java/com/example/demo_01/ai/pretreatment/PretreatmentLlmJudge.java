package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmJudgment;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmLabel;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.RepresentativeChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class PretreatmentLlmJudge {

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private ObjectMapper objectMapper;

    public LlmJudgment judge(Path promptPath,
                             RagDocumentMetadata metadata,
                             List<RepresentativeChunk> chunks,
                             int maxAttempts) {
        String systemPrompt = readPrompt(promptPath);
        String baseUserMessage = userMessage(metadata, chunks);
        return judgeWithMessage(systemPrompt, baseUserMessage, maxAttempts);
    }

    public LlmJudgment judgeAbstract(Path promptPath,
                                     RagDocumentMetadata metadata,
                                     int maxAttempts) {
        String systemPrompt = readPrompt(promptPath);
        String baseUserMessage = abstractUserMessage(metadata);
        return judgeWithMessage(systemPrompt, baseUserMessage, maxAttempts);
    }

    private LlmJudgment judgeWithMessage(String systemPrompt, String baseUserMessage, int maxAttempts) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                String userMessage = attempt == 1
                        ? baseUserMessage
                        : baseUserMessage + "\n\nRetry: previous output was invalid JSON: "
                        + (lastError == null ? "unknown" : lastError.getMessage())
                        + "\nReturn only one strict JSON object.";
                ChatResponse response = reasoningChatClient.chatStandard(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userMessage));
                AiMessage ai = response.aiMessage();
                return normalize(objectMapper.readValue(extractJson(ai == null ? null : ai.text()), LlmJudgment.class));
            } catch (Exception ex) {
                lastError = ex;
                if (attempt >= Math.max(1, maxAttempts) || isNonRetryable(ex)) {
                    throw new IllegalStateException("PreTreatment LLM judgment failed: " + ex.getMessage(), ex);
                }
            }
        }
        throw new IllegalStateException("PreTreatment LLM judgment failed", lastError);
    }

    LlmJudgment parse(String raw) throws IOException {
        return normalize(objectMapper.readValue(extractJson(raw), LlmJudgment.class));
    }

    private String readPrompt(Path promptPath) {
        try {
            return Files.readString(promptPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read PreTreatment prompt: " + promptPath, e);
        }
    }

    private String userMessage(RagDocumentMetadata metadata, List<RepresentativeChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        builder.append("Metadata:\n");
        builder.append("Title: ").append(value(metadata == null ? null : metadata.title())).append('\n');
        builder.append("Journal: ").append(value(metadata == null ? null : metadata.journal())).append('\n');
        builder.append("DOI: ").append(value(metadata == null ? null : metadata.doiNormalized())).append('\n');
        builder.append("Abstract: ").append(value(metadata == null ? null : metadata.abstractText())).append("\n\n");
        builder.append("Chunks:\n");
        for (RepresentativeChunk chunk : chunks == null ? List.<RepresentativeChunk>of() : chunks) {
            builder.append("--- chunk_id=").append(chunk.chunkId())
                    .append("; section=").append(value(chunk.sectionPath()))
                    .append(" ---\n")
                    .append(value(chunk.text()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String abstractUserMessage(RagDocumentMetadata metadata) {
        StringBuilder builder = new StringBuilder();
        builder.append("Metadata:\n");
        builder.append("Title: ").append(value(metadata == null ? null : metadata.title())).append('\n');
        builder.append("Journal: ").append(value(metadata == null ? null : metadata.journal())).append('\n');
        builder.append("DOI: ").append(value(metadata == null ? null : metadata.doiNormalized())).append('\n');
        builder.append("Abstract: ").append(value(metadata == null ? null : metadata.abstractText())).append('\n');
        return builder.toString();
    }

    private LlmJudgment normalize(LlmJudgment output) {
        if (output == null) {
            return LlmJudgment.notRun("Empty LLM output");
        }
        LlmLabel label = output.label() == null ? LlmLabel.NOT_RUN : output.label();
        return new LlmJudgment(
                label,
                output.taxa() == null ? List.of() : output.taxa(),
                output.researchFocus() == null ? "" : output.researchFocus(),
                output.evidenceChunkIds() == null ? List.of() : output.evidenceChunkIds(),
                output.reason() == null ? "" : output.reason()
        );
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private boolean isNonRetryable(Exception error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("arrearage") || lower.contains("invalid api-key") || lower.contains("quota")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
