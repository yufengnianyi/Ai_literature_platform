package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceConfigScope;
import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidationStatus;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class EvidenceVerifierAgent {

    @Resource
    private ReviewReasoningChatClient chatClient;

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

    public List<ValidatedEvidenceRow> verify(EvidenceProfile profile,
                                             List<ValidatedEvidenceRow> rows,
                                             List<EvidenceChunk> chunks) {
        if (!config().getAgents().getVerifier().isEnabled() || rows == null || rows.isEmpty()) {
            return rows == null ? List.of() : List.copyOf(rows);
        }

        String systemPrompt = PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_VERIFY_SYSTEM);
        String userMessage = verifyInput(profile, rows, chunks);
        try {
            String raw = responseText(chatClient.chatStandard(
                    SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
            VerifyOutput output = objectMapper.readValue(extractJson(raw), VerifyOutput.class);
            return apply(rows, output);
        } catch (Exception e) {
            log.warn("Verifier failed for profile {}; keeping original rows: {}",
                    profile.questionId(), e.getMessage());
            return List.copyOf(rows);
        }
    }

    private List<ValidatedEvidenceRow> apply(List<ValidatedEvidenceRow> rows, VerifyOutput output) {
        List<ValidatedEvidenceRow> verified = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ValidatedEvidenceRow row = rows.get(i);
            VerifyRowVerdict verdict = findVerdict(output, i + 1);
            if (verdict == null || verdict.isAccepted()) {
                verified.add(row.withValidation(ValidationStatus.VALID,
                        verdict == null ? null : verdict.reason()));
                continue;
            }
            ValidatedEvidenceRow invalid = row.withValidation(
                    ValidationStatus.INVALID, value(verdict.reason()));
            if (!config().getAgents().getVerifier().isDropInvalidRows()) {
                verified.add(invalid);
            }
        }
        return List.copyOf(verified);
    }

    private VerifyRowVerdict findVerdict(VerifyOutput output, int rowIndex) {
        if (output == null || output.rows() == null) {
            return null;
        }
        return output.rows().stream()
                .filter(item -> item != null && Objects.equals(item.rowIndex(), rowIndex))
                .findFirst()
                .orElse(null);
    }

    private String verifyInput(EvidenceProfile profile,
                               List<ValidatedEvidenceRow> rows,
                               List<EvidenceChunk> chunks) {
        StringBuilder rowsText = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            ValidatedEvidenceRow row = rows.get(i);
            rowsText.append("\n# rowIndex=").append(i + 1)
                    .append("\nheaders=").append(profile.headers())
                    .append("\ncells=").append(row.cells())
                    .append("\nanchors=").append(row.anchors());
        }
        StringBuilder chunksText = new StringBuilder();
        for (EvidenceChunk chunk : chunks) {
            chunksText.append("\n--- chunk_id=").append(value(chunk.chunkId()))
                    .append("; section=").append(value(chunk.sectionPath()))
                    .append(" ---\n").append(value(chunk.text()));
        }
        return """
                Evidence profile:
                - questionId: %s
                - title: %s
                - required primary fields: %s

                Rows to verify:
                %s

                Supplied chunks:
                %s
                """.formatted(
                profile.questionId(),
                profile.title(),
                profile.primaryFieldIndexes().stream().map(profile.headers()::get).toList(),
                rowsText,
                chunksText);
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Model returned no JSON");
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Model output does not contain a JSON object");
        }
        return trimmed.substring(start, end + 1);
    }

    private String responseText(dev.langchain4j.model.chat.response.ChatResponse response) {
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            throw new IllegalArgumentException("Model returned no text");
        }
        return response.aiMessage().text().trim();
    }

    private String value(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyOutput(List<VerifyRowVerdict> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyRowVerdict(Integer rowIndex,
                                   Boolean valid,
                                   String reason,
                                   List<String> unsupportedFields) {
        boolean isAccepted() {
            return valid == null || Boolean.TRUE.equals(valid);
        }
    }
}
