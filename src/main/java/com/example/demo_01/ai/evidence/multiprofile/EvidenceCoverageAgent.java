package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EvidenceCoverageAgent {

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private EvidenceExtractionAgent extractionAgent;

    @Resource
    private MultiProfileOutputValidator outputValidator;

    @Resource
    private EvidenceAgentTelemetryService telemetryService;

    @Resource
    private EvidenceProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public List<ValidatedEvidenceRow> recover(UUID batchId,
                                              SourceDocument document,
                                              EvidenceProfile profile,
                                              List<EvidenceChunk> chunks,
                                              List<ValidatedEvidenceRow> existing) {
        if (!properties.getAgents().getCoverage().isEnabled()) {
            return existing == null ? List.of() : List.copyOf(existing);
        }
        List<ValidatedEvidenceRow> current = existing == null
                ? new ArrayList<>() : new ArrayList<>(existing);
        int before = current.size();
        List<CoverageCandidate> candidates = enumerateCandidates(document, profile, chunks);
        Set<String> existingKeys = new LinkedHashSet<>();
        for (ValidatedEvidenceRow row : current) {
            existingKeys.add(primaryKeyFingerprint(profile, row.cells()));
        }

        List<CoverageCandidate> missing = candidates.stream()
                .filter(candidate -> candidate != null
                        && !existingKeys.contains(primaryKeyFingerprint(profile, candidate.primaryKey())))
                .limit(properties.getAgents().getCoverage().getMaxCandidates())
                .toList();

        List<String> recoveredFingerprints = new ArrayList<>();
        int rounds = Math.max(0, properties.getAgents().getCoverage().getMaxRecoveryRounds());
        for (int round = 0; round < rounds && !missing.isEmpty(); round++) {
            List<EvidenceChunk> focused = focusChunks(chunks, missing);
            List<ValidatedEvidenceRow> recovered =
                    extractionAgent.extract(document, profile, focused);
            Map<String, ValidatedEvidenceRow> unique = new LinkedHashMap<>();
            for (ValidatedEvidenceRow row : current) {
                unique.put(row.fingerprint(), row);
            }
            for (ValidatedEvidenceRow row : recovered) {
                String primary = primaryKeyFingerprint(profile, row.cells());
                if (!existingKeys.contains(primary) && unique.putIfAbsent(row.fingerprint(), row) == null) {
                    recoveredFingerprints.add(row.fingerprint());
                    existingKeys.add(primary);
                }
            }
            current = new ArrayList<>(unique.values());
            missing = missing.stream()
                    .filter(candidate -> !existingKeys.contains(
                            primaryKeyFingerprint(profile, candidate.primaryKey())))
                    .toList();
        }

        telemetryService.recordCoverage(
                batchId, document.documentId(), profile.questionId(),
                candidates.size(), before, recoveredFingerprints.size(), current.size(),
                candidates, recoveredFingerprints);
        return List.copyOf(current);
    }

    private List<CoverageCandidate> enumerateCandidates(SourceDocument document,
                                                        EvidenceProfile profile,
                                                        List<EvidenceChunk> chunks) {
        String systemPrompt = PromptResources.load(
                PromptCatalog.EVIDENCE_MULTI_PROFILE_COVERAGE_SYSTEM);
        String userMessage = coverageInput(document, profile, chunks);
        try {
            String raw = responseText(chatClient.chatStandard(
                    SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
            CoverageOutput output = objectMapper.readValue(extractJson(raw), CoverageOutput.class);
            if (output.candidates() == null) {
                return List.of();
            }
            return output.candidates().stream()
                    .filter(Objects::nonNull)
                    .filter(item -> item.primaryKey() != null
                            && item.primaryKey().size() == profile.primaryFieldIndexes().size())
                    .toList();
        } catch (Exception e) {
            log.warn("Coverage enumeration failed for {}: {}", profile.questionId(), e.getMessage());
            return List.of();
        }
    }

    private List<EvidenceChunk> focusChunks(List<EvidenceChunk> chunks,
                                            List<CoverageCandidate> missing) {
        Set<String> wanted = new LinkedHashSet<>();
        for (CoverageCandidate candidate : missing) {
            if (candidate.chunkIds() != null) {
                wanted.addAll(candidate.chunkIds());
            }
        }
        if (wanted.isEmpty()) {
            return chunks;
        }
        List<EvidenceChunk> focused = chunks.stream()
                .filter(chunk -> wanted.contains(chunk.chunkId()))
                .toList();
        return focused.isEmpty() ? chunks : focused;
    }

    private String primaryKeyFingerprint(EvidenceProfile profile, List<String> cells) {
        if (cells == null) {
            return "";
        }
        List<String> keyCells = new ArrayList<>();
        if (cells.size() == profile.headers().size()) {
            for (Integer index : profile.primaryFieldIndexes()) {
                if (index != null && index >= 0 && index < cells.size()) {
                    keyCells.add(normalize(cells.get(index)));
                }
            }
        } else {
            for (String cell : cells) {
                keyCells.add(normalize(cell));
            }
        }
        return outputValidator.fingerprint(profile.questionId() + ":primary", keyCells);
    }

    private String coverageInput(SourceDocument document,
                                 EvidenceProfile profile,
                                 List<EvidenceChunk> chunks) {
        return """
                Evidence profile:
                - questionId: %s
                - title: %s
                - scope: %s
                - one row represents: %s
                - required primary fields: %s

                Document metadata:
                - document_id: %s
                - title: %s

                Supplied chunks:
                %s
                """.formatted(
                profile.questionId(), profile.title(), profile.scope(), profile.rowUnit(),
                profile.primaryFieldIndexes().stream().map(profile.headers()::get).toList(),
                document.documentId(), value(document.title()), renderChunks(chunks));
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

    private String normalize(String value) {
        return WHITESPACE.matcher(Objects.requireNonNullElse(value, ""))
                .replaceAll(" ").trim().toLowerCase(Locale.ROOT);
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
    public record CoverageOutput(List<CoverageCandidate> candidates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverageCandidate(List<String> primaryKey,
                                    String hint,
                                    List<String> chunkIds) {
    }
}
