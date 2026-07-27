package com.example.demo_01.ai.evidence.table;

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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    @Resource
    private TableJsonlStore tableJsonlStore;

    @Resource
    private TableLegendResolver legendResolver;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * Return {@code baseChunks} augmented with the profile's relevant table bodies. When the
     * feature is disabled, the TEI cannot be located, or the document has no tables, the input is
     * returned unchanged.
     */
    public List<EvidenceChunk> augment(EvidenceProfile profile, List<EvidenceChunk> baseChunks) {
        if (!properties.getTable().isEnabled() || baseChunks == null || baseChunks.isEmpty()) {
            return baseChunks;
        }
        String sourceTei = firstSourceTei(baseChunks);
        if (sourceTei == null) {
            return baseChunks;
        }
        List<ParsedTable> tables = tableJsonlStore.load(sourceTei);
        if (tables.isEmpty()) {
            return baseChunks;
        }
        List<ParsedTable> selected = select(profile, tables);
        if (selected.isEmpty()) {
            return baseChunks;
        }
        String docPrefix = documentPrefix(baseChunks);
        Map<String, EvidenceChunk> merged = new LinkedHashMap<>();
        for (EvidenceChunk chunk : baseChunks) {
            if (chunk.chunkId() != null) {
                merged.put(chunk.chunkId(), chunk);
            }
        }
        int emitted = 0;
        for (ParsedTable table : selected) {
            if (emitted >= properties.getTable().getMaxTables()) {
                break;
            }
            EvidenceChunk tableChunk = toChunk(docPrefix, table, baseChunks, sourceTei);
            merged.putIfAbsent(tableChunk.chunkId(), tableChunk);
            emitted++;
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
        int max = properties.getTable().getMaxTableChars();
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

    // --- table selection -------------------------------------------------------

    private List<ParsedTable> select(EvidenceProfile profile, List<ParsedTable> tables) {
        if (properties.getTable().isLlmSelect()) {
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
