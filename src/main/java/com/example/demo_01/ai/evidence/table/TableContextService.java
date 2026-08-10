package com.example.demo_01.ai.evidence.table;

import com.example.demo_01.ai.evidence.config.EvidenceConfigScope;
import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceAgentTelemetryService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase B orchestrator: on demand, turns the tables a profile needs into anchorable evidence
 * chunks.
 *
 * <p>Flow: locate the document TEI from chunk provenance &rarr; load all tables (phase A, lazily
 * materialized) &rarr; pick the relevant ones (LLM, keyword fallback) &rarr; resolve in-table
 * codes against context (phase D) &rarr; emit synthetic {@code content_type=table_body} chunks.
 * The returned list is the base chunks plus the injected table chunks; callers pass it through
 * the whole per-question pipeline so anchors stay valid through verify/coverage (phase C).</p>
 */
@Slf4j
@Service
public class TableContextService {

    private static final String[] VALUE_KEYWORDS = {
            "activity", "activities", "inhibit", "ic50", "ic 50", "ec50", "ec 50", "mic",
            "efficacy", "toxicity", "antifungal", "antimicrobial", "antioomycete",
            "抑制", "毒力", "活性", "半数", "有效"
    };

    @Resource
    private EvidenceProperties properties;

    @Autowired(required = false)
    private EvidenceConfigScope configScope;

    @Resource
    private TableJsonlStore tableJsonlStore;

    @Resource
    private TableLegendResolver legendResolver;

    @Resource
    private LinearizedTableRecoveryService tableRecoveryService;

    @Autowired(required = false)
    private EvidenceAgentTelemetryService telemetryService;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private ObjectMapper objectMapper;

    /** Honours a per-run configuration override when an extraction run pinned one. */
    private EvidenceProperties config() {
        return configScope == null ? properties : configScope.current();
    }

    /**
     * Return {@code baseChunks} augmented with the profile's relevant table bodies. When the
     * feature is disabled, the TEI cannot be located, or the document has no tables, the input is
     * returned unchanged.
     */
    public List<EvidenceChunk> augment(EvidenceProfile profile, List<EvidenceChunk> baseChunks) {
        return augment(null, null, profile, baseChunks);
    }

    /**
     * Return {@code baseChunks} augmented with parsed or recovered table bodies. When recovered
     * table fallback runs, telemetry is attached to the supplied extraction scope.
     */
    public List<EvidenceChunk> augment(UUID scopeId,
                                       UUID documentId,
                                       EvidenceProfile profile,
                                       List<EvidenceChunk> baseChunks) {
        if (!isEnabledFor(profile) || baseChunks == null || baseChunks.isEmpty()) {
            return baseChunks;
        }
        String sourceTei = firstSourceTei(baseChunks);
        if (sourceTei == null) {
            return baseChunks;
        }
        List<ParsedTable> tables = tableJsonlStore.load(sourceTei);
        List<ParsedTable> selected = tables.isEmpty() ? List.of() : select(profile, tables);
        String docPrefix = documentPrefix(baseChunks);
        Map<String, EvidenceChunk> merged = new LinkedHashMap<>();
        for (EvidenceChunk chunk : baseChunks) {
            if (chunk.chunkId() != null) {
                merged.put(chunk.chunkId(), chunk);
            }
        }
        int emitted = 0;
        for (ParsedTable table : selected) {
            if (emitted >= config().getTable().getMaxTables()) {
                break;
            }
            EvidenceChunk tableChunk = toChunk(docPrefix, table, baseChunks, sourceTei);
            merged.putIfAbsent(tableChunk.chunkId(), tableChunk);
            emitted++;
        }

        int recovered = maybeRecoverTables(
                scopeId, documentId, profile, baseChunks, tables, selected, docPrefix, sourceTei, merged);
        if (emitted == 0 && recovered == 0) {
            return baseChunks;
        }
        return List.copyOf(merged.values());
    }

    private EvidenceChunk toChunk(String docPrefix,
                                  ParsedTable table,
                                  List<EvidenceChunk> contextChunks,
                                  String sourceTei) {
        TableLegendResolver.TableLegend legend = legendResolver.resolve(table, contextChunks);
        StringBuilder builder = new StringBuilder(table.markdown()).append(legend.legendText());
        // Embed the verbatim definition sentences so the codes stay resolvable AND anchorable
        // even when batching separates the table from the body chunk that defines them.
        if (!legend.supportingQuotes().isEmpty()) {
            builder.append("\nContext (verbatim from the paper, use as anchor for resolved codes):");
            for (String quote : legend.supportingQuotes()) {
                builder.append("\n- ").append(quote);
            }
        }
        String body = builder.toString();
        int max = config().getTable().getMaxTableChars();
        if (body.length() > max) {
            body = body.substring(0, max);
        }
        String label = table.label() == null || table.label().isBlank()
                ? table.tableRef() : "Table " + table.label();
        return new EvidenceChunk(
                docPrefix + ":table:" + table.tableRef(),
                "Table > " + label,
                null, null, null,
                body,
                "table_body",
                sourceTei);
    }

    private int maybeRecoverTables(UUID scopeId,
                                   UUID documentId,
                                   EvidenceProfile profile,
                                   List<EvidenceChunk> baseChunks,
                                   List<ParsedTable> parsedTables,
                                   List<ParsedTable> selectedTables,
                                   String docPrefix,
                                   String sourceTei,
                                   Map<String, EvidenceChunk> merged) {
        if (!isRecoveryEnabled()) {
            return 0;
        }
        LinearizedTableRecoveryService.RecoveryInspection inspection =
                tableRecoveryService.inspect(profile, baseChunks, parsedTables, selectedTables);
        if (!inspection.shouldAttempt()) {
            return 0;
        }

        Map<String, Object> detail = telemetryDetail(
                "statuses", inspection.statuses(),
                "candidateChunkIds", inspection.candidateChunkIds(),
                "candidateSignals", inspection.candidates().stream()
                        .map(candidate -> Map.of(
                                "chunkId", value(candidate.chunk().chunkId()),
                                "score", candidate.score(),
                                "signals", candidate.signals()))
                        .toList(),
                "parsedTableSummary", inspection.parsedTableSummary());
        try {
            LinearizedTableRecoveryService.RecoveryResult result = recoverWithTelemetry(
                    scopeId, documentId, profile, inspection, detail, docPrefix);
            int emitted = injectRecoveredTables(docPrefix, sourceTei, merged, result);
            return emitted;
        } catch (RuntimeException e) {
            log.warn("Linearized table recovery failed for document {} profile {}: {}",
                    documentId, profile.questionId(), e.getMessage());
            return 0;
        }
    }

    private LinearizedTableRecoveryService.RecoveryResult recoverWithTelemetry(
            UUID scopeId,
            UUID documentId,
            EvidenceProfile profile,
            LinearizedTableRecoveryService.RecoveryInspection inspection,
            Map<String, Object> detail,
            String docPrefix) {
        if (telemetryService == null || scopeId == null || documentId == null) {
            LinearizedTableRecoveryService.RecoveryResult result =
                    tableRecoveryService.recover(profile, inspection);
            putRecoveryDetail(detail, result);
            putInjectedChunkIds(detail, docPrefix, result);
            return result;
        }
        return telemetryService.timed(
                scopeId, documentId, profile.questionId(), "table-recovery",
                1, 1, 0, detail,
                () -> {
                    LinearizedTableRecoveryService.RecoveryResult result =
                            tableRecoveryService.recover(profile, inspection);
                    putRecoveryDetail(detail, result);
                    putInjectedChunkIds(detail, docPrefix, result);
                    return result;
                });
    }

    private int injectRecoveredTables(String docPrefix,
                                      String sourceTei,
                                      Map<String, EvidenceChunk> merged,
                                      LinearizedTableRecoveryService.RecoveryResult result) {
        if (result == null || !result.recovered()) {
            return 0;
        }
        int emitted = 0;
        int max = config().getTable().getRecovery().getMaxRecoveredTables();
        for (LinearizedTableRecoveryService.RecoveredTable table : result.tables()) {
            if (emitted >= max) {
                break;
            }
            EvidenceChunk chunk = toRecoveredChunk(docPrefix, sourceTei, emitted + 1, result, table);
            merged.putIfAbsent(chunk.chunkId(), chunk);
            emitted++;
        }
        return emitted;
    }

    private EvidenceChunk toRecoveredChunk(String docPrefix,
                                           String sourceTei,
                                           int index,
                                           LinearizedTableRecoveryService.RecoveryResult result,
                                           LinearizedTableRecoveryService.RecoveredTable table) {
        String label = table.label() == null || table.label().isBlank()
                ? "Recovered Table " + index : table.label();
        String body = """
                Recovered table body from linearized paper text because the structured table artifact was missing or incomplete.
                Recovery status: %s
                Recovery confidence: %s
                Source chunks: %s
                Warnings: %s

                Caption: %s

                %s
                """.formatted(
                value(result.status()),
                value(result.confidence()),
                String.join(", ", result.sourceChunkIds()),
                String.join("; ", result.warnings() == null ? List.of() : result.warnings()),
                value(table.caption()),
                value(table.markdown()));
        int maxChars = config().getTable().getMaxTableChars();
        if (body.length() > maxChars) {
            body = body.substring(0, maxChars) + "\n[truncated]";
        }
        return new EvidenceChunk(
                docPrefix + ":table-recovery:" + index + ":" + sanitize(label),
                "Recovered Table > " + label,
                null, null, null,
                body,
                "table_body_recovered",
                sourceTei);
    }

    private void putRecoveryDetail(Map<String, Object> detail,
                                   LinearizedTableRecoveryService.RecoveryResult result) {
        detail.put("status", result.status());
        detail.put("confidence", result.confidence());
        detail.put("warnings", result.warnings());
        detail.put("sourceChunkIds", result.sourceChunkIds());
        detail.put("recoveredTableCount", result.tables().size());
        detail.put("recoveredTables", result.tables().stream()
                .map(table -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("label", table.label());
                    item.put("caption", table.caption());
                    item.put("markdown", table.markdown());
                    return item;
                })
                .toList());
    }

    private void putInjectedChunkIds(Map<String, Object> detail,
                                     String docPrefix,
                                     LinearizedTableRecoveryService.RecoveryResult result) {
        int emitted = result == null || !result.recovered()
                ? 0
                : Math.min(result.tables().size(),
                config().getTable().getRecovery().getMaxRecoveredTables());
        detail.put("injectedChunkIds", recoveredChunkIds(docPrefix, result, emitted));
    }

    private List<String> recoveredChunkIds(String docPrefix,
                                           LinearizedTableRecoveryService.RecoveryResult result,
                                           int emitted) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < emitted; i++) {
            LinearizedTableRecoveryService.RecoveredTable table = result.tables().get(i);
            String label = table.label() == null || table.label().isBlank()
                    ? "Recovered Table " + (i + 1) : table.label();
            ids.add(docPrefix + ":table-recovery:" + (i + 1) + ":" + sanitize(label));
        }
        return ids;
    }

    private boolean isRecoveryEnabled() {
        return config().getTable().getRecovery() != null
                && config().getTable().getRecovery().isEnabled();
    }

    private boolean isEnabledFor(EvidenceProfile profile) {
        if (!config().getTable().isEnabled() || profile == null) {
            return false;
        }
        List<String> enabledQuestionIds = config().getTable().getEnabledQuestionIds();
        if (enabledQuestionIds == null || enabledQuestionIds.isEmpty()) {
            return false;
        }
        return enabledQuestionIds.stream()
                .filter(Objects::nonNull)
                .map(id -> id.trim().toUpperCase(Locale.ROOT))
                .anyMatch(id -> id.equals(profile.questionId().toUpperCase(Locale.ROOT)));
    }

    private Map<String, Object> telemetryDetail(Object... keyValues) {
        if (telemetryService != null) {
            return telemetryService.detail(keyValues);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    // --- table selection -------------------------------------------------------

    private List<ParsedTable> select(EvidenceProfile profile, List<ParsedTable> tables) {
        if (config().getTable().isLlmSelect()) {
            try {
                List<ParsedTable> chosen = llmSelect(profile, tables);
                if (!chosen.isEmpty()) {
                    return chosen;
                }
            } catch (Exception e) {
                log.warn("LLM table selection failed for {}; falling back to keywords: {}",
                        profile.questionId(), e.getMessage());
            }
        }
        return keywordSelect(profile, tables);
    }

    private List<ParsedTable> llmSelect(EvidenceProfile profile, List<ParsedTable> tables)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, ParsedTable> byRef = new LinkedHashMap<>();
        StringBuilder catalog = new StringBuilder();
        for (ParsedTable table : tables) {
            byRef.put(table.tableRef(), table);
            catalog.append("\n- tableRef=").append(table.tableRef())
                    .append("; caption=").append(preview(table.caption()));
        }
        String system = PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_TABLE_SELECT_SYSTEM);
        String user = """
                Evidence profile:
                - questionId: %s
                - title: %s
                - scope: %s

                Table caption catalog:
                %s
                """.formatted(profile.questionId(), profile.title(), profile.scope(), catalog);
        String raw = responseText(chatClient.chatStandard(
                SystemMessage.from(system), UserMessage.from(user)));
        TableSelection selection = objectMapper.readValue(extractJson(raw), TableSelection.class);
        List<ParsedTable> chosen = new ArrayList<>();
        if (selection.tableRefs() != null) {
            for (String ref : selection.tableRefs()) {
                ParsedTable table = byRef.get(ref);
                if (table != null) {
                    chosen.add(table);
                }
            }
        }
        return chosen;
    }

    private List<ParsedTable> keywordSelect(EvidenceProfile profile, List<ParsedTable> tables) {
        String profileText = (value(profile.title()) + " " + value(profile.scope()) + " "
                + value(profile.guidance())).toLowerCase(Locale.ROOT);
        List<ParsedTable> chosen = new ArrayList<>();
        for (ParsedTable table : tables) {
            String caption = value(table.caption()).toLowerCase(Locale.ROOT);
            if (caption.isBlank()) {
                continue;
            }
            boolean valueHit = false;
            for (String keyword : VALUE_KEYWORDS) {
                if (caption.contains(keyword)) {
                    valueHit = true;
                    break;
                }
            }
            boolean profileHit = false;
            for (String token : profileText.split("\\W+")) {
                if (token.length() >= 4 && caption.contains(token)) {
                    profileHit = true;
                    break;
                }
            }
            if (valueHit || profileHit) {
                chosen.add(table);
            }
        }
        return chosen;
    }

    // --- helpers ---------------------------------------------------------------

    private String firstSourceTei(List<EvidenceChunk> chunks) {
        for (EvidenceChunk chunk : chunks) {
            if (chunk.sourceTei() != null && !chunk.sourceTei().isBlank()) {
                return chunk.sourceTei();
            }
        }
        return null;
    }

    private String documentPrefix(List<EvidenceChunk> chunks) {
        for (EvidenceChunk chunk : chunks) {
            String id = chunk.chunkId();
            if (id != null && id.contains(":")) {
                return id.substring(0, id.indexOf(':'));
            }
        }
        return "doc";
    }

    private String preview(String text) {
        String value = value(text).replaceAll("\\s+", " ").trim();
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    private String sanitize(String text) {
        String sanitized = value(text).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return sanitized.isBlank() ? "table" : sanitized;
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
    public record TableSelection(List<String> tableRefs, String reason) {
    }
}
