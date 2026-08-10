package com.example.demo_01.ai.evidence.table;

import com.example.demo_01.ai.evidence.config.EvidenceConfigScope;
import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class LinearizedTableRecoveryService {

    private static final Pattern COMPOUND_CONC_PATTERN = Pattern.compile(
            "\\b(?:\\d+[a-z]|\\d+)\\s+(?:\\d{1,4}(?:\\.\\d+)?|\\d+\\s*u?m)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_LABEL_PATTERN = Pattern.compile("\\btable\\s+\\d+\\b",
            Pattern.CASE_INSENSITIVE);

    @Resource
    private EvidenceProperties properties;

    @Autowired(required = false)
    private EvidenceConfigScope configScope;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private ObjectMapper objectMapper;

    private EvidenceProperties config() {
        return configScope == null ? properties : configScope.current();
    }

    public RecoveryInspection inspect(EvidenceProfile profile,
                                      List<EvidenceChunk> baseChunks,
                                      List<ParsedTable> parsedTables,
                                      List<ParsedTable> selectedTables) {
        if (!"Q1".equals(profile.questionId())) {
            return RecoveryInspection.noop("PROFILE_NOT_SUPPORTED");
        }
        boolean noParsedTables = parsedTables == null || parsedTables.isEmpty();
        boolean noSelectedTables = !noParsedTables && (selectedTables == null || selectedTables.isEmpty());
        boolean selectedUnstructured = selectedTables != null && !selectedTables.isEmpty()
                && selectedTables.stream().allMatch(this::isUnstructured);
        if (!noParsedTables && !noSelectedTables && !selectedUnstructured) {
            return RecoveryInspection.noop("STRUCTURED_TABLE_AVAILABLE");
        }

        List<String> statuses = new ArrayList<>();
        if (noParsedTables) {
            statuses.add("TABLES_JSONL_EMPTY");
        } else if (noSelectedTables) {
            statuses.add("NO_RELEVANT_STRUCTURED_TABLE_SELECTED");
        } else {
            statuses.add("SELECTED_TABLE_HAS_NO_ROWS");
        }

        List<ChunkCandidate> candidates = candidateChunks(baseChunks);
        if (candidates.isEmpty()) {
            statuses.add("NO_LINEARIZED_TABLE_FOUND");
            return new RecoveryInspection(false, statuses, candidates, List.of(),
                    parsedTableSummary(parsedTables));
        }
        statuses.add("LINEARIZED_TABLE_FOUND");
        List<EvidenceChunk> supporting = supportingChunks(baseChunks, candidates);
        return new RecoveryInspection(true, statuses, candidates, supporting,
                parsedTableSummary(parsedTables));
    }

    public RecoveryResult recover(EvidenceProfile profile, RecoveryInspection inspection) {
        if (inspection == null || !inspection.shouldAttempt()) {
            return RecoveryResult.empty("NO_RECOVERABLE_TABLE", "low", List.of());
        }
        String system = PromptResources.load(PromptCatalog.EVIDENCE_LINEARIZED_TABLE_RECOVERY_SYSTEM);
        String user = recoveryInput(profile, inspection);
        String raw = responseText(chatClient.chatStandard(
                SystemMessage.from(system), UserMessage.from(user)));
        RecoveryEnvelope envelope;
        try {
            envelope = objectMapper.readValue(extractJson(raw), RecoveryEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse linearized table recovery JSON", e);
        }
        List<RecoveredTable> tables = normalizeTables(envelope.tables());
        String status = firstNonBlank(envelope.status(),
                tables.isEmpty() ? "NO_RECOVERABLE_TABLE" : "RECOVERED_TABLE_INJECTED");
        String confidence = firstNonBlank(envelope.confidence(), "low").toLowerCase(Locale.ROOT);
        List<String> warnings = envelope.warnings() == null ? List.of() : envelope.warnings();
        if (!tables.isEmpty() && ("low".equals(confidence) || status.toLowerCase(Locale.ROOT).contains("low"))) {
            status = "LOW_CONFIDENCE_REVIEW_REQUIRED";
        }
        return new RecoveryResult(!tables.isEmpty(), status, confidence, warnings, tables,
                inspection.candidateChunkIds());
    }

    private boolean isUnstructured(ParsedTable table) {
        return table == null || !table.structured() || table.rows() == null || table.rows().isEmpty();
    }

    private List<ChunkCandidate> candidateChunks(List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<ChunkCandidate> candidates = new ArrayList<>();
        for (EvidenceChunk chunk : chunks) {
            CandidateScore score = score(chunk);
            if (score.score() >= 4) {
                candidates.add(new ChunkCandidate(chunk, score.score(), score.signals()));
            }
        }
        candidates.sort(Comparator.comparingInt(ChunkCandidate::score).reversed());
        return candidates;
    }

    private CandidateScore score(EvidenceChunk chunk) {
        String text = value(chunk.text());
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> signals = new ArrayList<>();
        int score = 0;
        if (lower.contains("compound conc")) {
            score += 5;
            signals.add("COMPOUND_CONC_HEADER");
        }
        if (lower.contains("activity is preventive value")
                || lower.contains("inhibition")
                || lower.contains("antifungal activities")) {
            score += 2;
            signals.add("ACTIVITY_TEXT");
        }
        if (lower.contains("tlb:") || lower.contains("phytophthora infestans")) {
            score += 2;
            signals.add("OOMYCETE_COLUMN_OR_TARGET");
        }
        int labelCount = compoundLabelCount(text);
        if (labelCount >= 4) {
            score += Math.min(4, labelCount / 2);
            signals.add("COMPOUND_LABEL_RUN_" + labelCount);
        }
        if (TABLE_LABEL_PATTERN.matcher(value(chunk.sectionPath()) + " " + text).find()) {
            score += 1;
            signals.add("TABLE_LABEL");
        }
        return new CandidateScore(score, signals);
    }

    private int compoundLabelCount(String text) {
        Matcher matcher = COMPOUND_CONC_PATTERN.matcher(value(text));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private List<EvidenceChunk> supportingChunks(List<EvidenceChunk> chunks, List<ChunkCandidate> candidates) {
        Set<String> candidateIds = new LinkedHashSet<>();
        for (ChunkCandidate candidate : candidates) {
            candidateIds.add(value(candidate.chunk().chunkId()));
        }
        List<EvidenceChunk> supporting = new ArrayList<>();
        for (EvidenceChunk chunk : chunks) {
            if (candidateIds.contains(value(chunk.chunkId()))) {
                continue;
            }
            String haystack = (value(chunk.sectionPath()) + " " + value(chunk.text()))
                    .toLowerCase(Locale.ROOT);
            if (haystack.contains("table ")
                    || haystack.contains("caption")
                    || haystack.contains("activity")
                    || haystack.contains("assay")
                    || haystack.contains("experimental")
                    || haystack.contains("method")
                    || haystack.contains("phytophthora")) {
                supporting.add(chunk);
            }
        }
        return supporting;
    }

    private String recoveryInput(EvidenceProfile profile, RecoveryInspection inspection) {
        EvidenceProperties.Recovery recovery = config().getTable().getRecovery();
        StringBuilder builder = new StringBuilder();
        builder.append("Evidence profile:\n")
                .append("- questionId: ").append(profile.questionId()).append('\n')
                .append("- title: ").append(profile.title()).append('\n')
                .append("- scope: ").append(profile.scope()).append('\n')
                .append("- maxRecoveredTables: ").append(recovery.getMaxRecoveredTables()).append("\n\n")
                .append("Parsed table artifact summary:\n")
                .append(inspection.parsedTableSummary()).append("\n\n")
                .append("Detection statuses:\n")
                .append(String.join(", ", inspection.statuses())).append("\n\n")
                .append("Candidate flattened table chunks:\n");
        appendChunks(builder, inspection.candidates().stream()
                .map(ChunkCandidate::chunk)
                .toList(), recovery.getMaxChars() * 2 / 3);
        builder.append("\nSupporting caption/method chunks:\n");
        appendChunks(builder, inspection.supportingChunks(), recovery.getMaxChars() / 3);
        return truncate(builder.toString(), recovery.getMaxChars());
    }

    private void appendChunks(StringBuilder builder, List<EvidenceChunk> chunks, int maxChars) {
        int written = 0;
        for (EvidenceChunk chunk : chunks) {
            String rendered = "\n--- chunk_id=" + value(chunk.chunkId())
                    + "; section=" + value(chunk.sectionPath()) + " ---\n"
                    + value(chunk.text()) + '\n';
            if (written + rendered.length() > maxChars) {
                int remaining = Math.max(0, maxChars - written);
                if (remaining > 200) {
                    builder.append(rendered, 0, remaining).append("\n[truncated]\n");
                }
                break;
            }
            builder.append(rendered);
            written += rendered.length();
        }
    }

    private String parsedTableSummary(List<ParsedTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return "No parsed tables were available from tables.jsonl.";
        }
        StringBuilder builder = new StringBuilder();
        for (ParsedTable table : tables) {
            builder.append("- tableRef=").append(value(table.tableRef()))
                    .append("; label=").append(value(table.label()))
                    .append("; structured=").append(table.structured())
                    .append("; rows=").append(table.rows() == null ? 0 : table.rows().size())
                    .append("; caption=").append(preview(table.caption())).append('\n');
        }
        return builder.toString();
    }

    private List<RecoveredTable> normalizeTables(List<RecoveredTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        List<RecoveredTable> normalized = new ArrayList<>();
        for (RecoveredTable table : tables) {
            String markdown = value(table.markdown()).trim();
            if (!markdown.contains("|")) {
                continue;
            }
            normalized.add(new RecoveredTable(
                    firstNonBlank(table.label(), "Recovered Table"),
                    value(table.caption()).trim(),
                    markdown));
        }
        return normalized;
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

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String preview(String text) {
        return truncate(value(text).replaceAll("\\s+", " ").trim(), 200);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    private String value(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    public record RecoveryInspection(
            boolean shouldAttempt,
            List<String> statuses,
            List<ChunkCandidate> candidates,
            List<EvidenceChunk> supportingChunks,
            String parsedTableSummary
    ) {
        static RecoveryInspection noop(String status) {
            return new RecoveryInspection(false, List.of(status), List.of(), List.of(), "");
        }

        List<String> candidateChunkIds() {
            return candidates.stream()
                    .map(candidate -> candidate.chunk().chunkId())
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    public record ChunkCandidate(EvidenceChunk chunk, int score, List<String> signals) {
    }

    public record RecoveryResult(
            boolean recovered,
            String status,
            String confidence,
            List<String> warnings,
            List<RecoveredTable> tables,
            List<String> sourceChunkIds
    ) {
        static RecoveryResult empty(String status, String confidence, List<String> warnings) {
            return new RecoveryResult(false, status, confidence, warnings, List.of(), List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecoveryEnvelope(
            String status,
            String confidence,
            List<String> warnings,
            List<RecoveredTable> tables
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecoveredTable(
            String label,
            String caption,
            String markdown
    ) {
    }

    private record CandidateScore(int score, List<String> signals) {
    }
}
