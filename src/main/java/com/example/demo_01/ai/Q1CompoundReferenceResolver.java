package com.example.demo_01.ai;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.rag.RagChatProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves LOCAL_LABEL / NATURAL_EXTRACT compound names into subject-bearing
 * display names by inspecting sibling evidence rows from the same document and
 * calling a lightweight LLM once per document (bounded, concurrent, soft-fail).
 */
@Service
public class Q1CompoundReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(Q1CompoundReferenceResolver.class);

    /**
     * Bare in-text labels ("3b", "12", "7h") with no leading word at all — the persisted
     * {@code name_kind} column (computed once at ingestion time) can miss these, so this
     * heuristic re-checks them at read time without requiring a data backfill.
     */
    private static final Pattern BARE_LABEL_PATTERN = Pattern.compile("^[0-9]{1,3}[a-zA-Z]{0,2}$");
    /** Short, standard-name-less original names are usually table labels rather than real names. */
    private static final int SHORT_AMBIGUOUS_NAME_MAX_LEN = 6;
    /** Overly long systematic (IUPAC-style) names are sent through so a common abbreviation can be substituted. */
    private static final int LONG_NAME_SHORTEN_THRESHOLD = 32;
    private static final Pattern TERM_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9\\-]{2,}|[0-9]{1,3}[a-zA-Z]{0,2}");

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private EvidenceRepository evidenceRepository;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RagChatProperties ragChatProperties;

    @Resource
    @Qualifier("q1CompoundResolutionExecutor")
    private TaskExecutor resolutionExecutor;

    /**
     * @return map of evidenceId → resolved display name for rows that were successfully rewritten
     */
    public Map<UUID, String> resolve(List<CompoundEvidenceRecord> selectedRows) {
        if (selectedRows == null || selectedRows.isEmpty()) {
            return Map.of();
        }

        RagChatProperties.Q1Evidence properties = ragChatProperties.getQ1Evidence();
        int maxCalls = properties.getMaxResolutionCallsPerTurn();
        if (maxCalls <= 0) {
            return Map.of();
        }

        Map<UUID, List<CompoundEvidenceRecord>> targetsByDocument = new LinkedHashMap<>();
        for (CompoundEvidenceRecord evidence : selectedRows) {
            if (!needsResolution(evidence)) {
                continue;
            }
            targetsByDocument
                    .computeIfAbsent(evidence.documentId(), ignored -> new ArrayList<>())
                    .add(evidence);
        }
        if (targetsByDocument.isEmpty()) {
            return Map.of();
        }

        List<Map.Entry<UUID, List<CompoundEvidenceRecord>>> documents =
                new ArrayList<>(targetsByDocument.entrySet());
        if (documents.size() > maxCalls) {
            documents = documents.subList(0, maxCalls);
        }

        long timeoutMs = properties.getResolutionTimeoutMs();
        List<CompletableFuture<Map<UUID, String>>> futures = new ArrayList<>(documents.size());
        for (Map.Entry<UUID, List<CompoundEvidenceRecord>> entry : documents) {
            UUID documentId = entry.getKey();
            List<CompoundEvidenceRecord> targets = List.copyOf(entry.getValue());
            futures.add(CompletableFuture.supplyAsync(
                    () -> resolveDocument(documentId, targets),
                    resolutionExecutor));
        }

        Map<UUID, String> resolved = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            UUID documentId = documents.get(i).getKey();
            try {
                Map<UUID, String> partial = futures.get(i).get(timeoutMs, TimeUnit.MILLISECONDS);
                if (partial != null) {
                    resolved.putAll(partial);
                }
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                log.warn("Q1 compound reference resolution timed out for document {}", documentId);
            } catch (Exception e) {
                log.warn("Q1 compound reference resolution failed for document {}: {}",
                        documentId, e.getMessage());
            }
        }
        return Map.copyOf(resolved);
    }

    private boolean needsResolution(CompoundEvidenceRecord evidence) {
        NameKind kind = evidence.nameKind();
        if (kind == NameKind.LOCAL_LABEL || kind == NameKind.NATURAL_EXTRACT) {
            return true;
        }
        CompoundEvidenceRow row = evidence.row();
        String standard = value(row.compoundStandardName());
        if (standard.isBlank()) {
            String original = value(row.compoundOriginalName());
            if (!original.isBlank()
                    && (BARE_LABEL_PATTERN.matcher(original).matches()
                            || original.length() <= SHORT_AMBIGUOUS_NAME_MAX_LEN)) {
                return true;
            }
        }
        return displayName(evidence).length() > LONG_NAME_SHORTEN_THRESHOLD;
    }

    private Map<UUID, String> resolveDocument(UUID documentId, List<CompoundEvidenceRecord> targets) {
        try {
            List<CompoundEvidenceRecord> siblings = evidenceRepository.findByDocumentId(documentId);
            if (siblings.isEmpty()) {
                return Map.of();
            }

            String systemPrompt = PromptResources.load(
                    PromptCatalog.AI_Q1_COMPOUND_REFERENCE_RESOLUTION_SYSTEM);
            String chunkContext = originalDocumentContext(documentId, targets);
            String userMessage = buildUserMessage(documentId, targets, siblings, chunkContext);
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userMessage));
            String raw = responseText(response);
            ResolutionEnvelope envelope = objectMapper.readValue(
                    extractJson(raw), ResolutionEnvelope.class);
            if (envelope.resolutions() == null || envelope.resolutions().isEmpty()) {
                return Map.of();
            }

            Map<UUID, String> originalById = new LinkedHashMap<>();
            for (CompoundEvidenceRecord target : targets) {
                originalById.put(target.evidenceId(), displayName(target));
            }

            Map<UUID, String> resolved = new LinkedHashMap<>();
            for (ResolutionItem item : envelope.resolutions()) {
                if (item == null || item.evidenceId() == null || item.evidenceId().isBlank()) {
                    continue;
                }
                UUID evidenceId;
                try {
                    evidenceId = UUID.fromString(item.evidenceId().strip());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!originalById.containsKey(evidenceId)) {
                    continue;
                }
                String resolvedName = item.resolvedName() == null ? "" : item.resolvedName().strip();
                if (resolvedName.isBlank()) {
                    continue;
                }
                String original = originalById.get(evidenceId);
                if (resolvedName.equalsIgnoreCase(original)) {
                    continue;
                }
                resolved.put(evidenceId, resolvedName);
            }
            return resolved;
        } catch (Exception e) {
            log.warn("Q1 compound reference resolution failed for document {}: {}",
                    documentId, e.getMessage());
            return Map.of();
        }
    }

    private String buildUserMessage(UUID documentId,
                                    List<CompoundEvidenceRecord> targets,
                                    List<CompoundEvidenceRecord> siblings,
                                    String chunkContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("document_id: ").append(documentId).append("\n\n");
        builder.append("Targets to resolve (must appear in output):\n");
        for (CompoundEvidenceRecord target : targets) {
            CompoundEvidenceRow row = target.row();
            builder.append("- evidenceId=").append(target.evidenceId())
                    .append("; nameKind=").append(target.nameKind())
                    .append("; compoundOriginalName=").append(value(row.compoundOriginalName()))
                    .append("; compoundStandardName=").append(value(row.compoundStandardName()))
                    .append("; sourceDescription=").append(value(row.sourceDescription()))
                    .append("; structureType=").append(value(row.structureType()))
                    .append('\n');
        }
        builder.append("\nFull evidence table for this document:\n");
        for (CompoundEvidenceRecord sibling : siblings) {
            CompoundEvidenceRow row = sibling.row();
            builder.append("- evidenceId=").append(sibling.evidenceId())
                    .append("; nameKind=").append(sibling.nameKind())
                    .append("; compoundOriginalName=").append(value(row.compoundOriginalName()))
                    .append("; compoundStandardName=").append(value(row.compoundStandardName()))
                    .append("; sourceCategory=").append(value(row.sourceCategory()))
                    .append("; sourceDescription=").append(value(row.sourceDescription()))
                    .append("; oomycete=").append(value(row.oomyceteScientificName()))
                    .append("; activity=").append(value(row.activityData()))
                    .append('\n');
        }
        if (!chunkContext.isBlank()) {
            builder.append("\nOriginal paper excerpts (for extra context only; the structured ")
                    .append("evidence table above remains authoritative for any conflict):\n")
                    .append(chunkContext)
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * Falls back to the source paper's full parsed text (not just the structured evidence
     * table) so labels/derivatives/extracts whose parent or source subject was only ever
     * described in prose (methods/materials sections, not extracted into its own table row)
     * can still be resolved, e.g. "3b" -&gt; "薄荷 (Mentha) 叶片乙醇提取物 3b".
     */
    private String originalDocumentContext(UUID documentId, List<CompoundEvidenceRecord> targets) {
        int maxChunks = ragChatProperties.getQ1Evidence().getMaxResolutionChunksPerDocument();
        int maxChars = ragChatProperties.getQ1Evidence().getResolutionChunkContextChars();
        if (maxChunks <= 0 || maxChars <= 0) {
            return "";
        }
        List<EvidenceChunk> chunks;
        try {
            chunks = evidenceRepository.findDocumentChunks(documentId);
        } catch (RuntimeException e) {
            log.warn("Failed to load original document text for document {}: {}", documentId, e.getMessage());
            return "";
        }
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        Set<String> terms = extractTerms(targets);
        List<EvidenceChunk> ranked = chunks.stream()
                .filter(chunk -> chunk.text() != null && !chunk.text().isBlank())
                .sorted(Comparator.comparingDouble((EvidenceChunk chunk) -> chunkScore(chunk, terms)).reversed())
                .limit(maxChunks)
                .toList();

        StringBuilder builder = new StringBuilder();
        int remaining = maxChars;
        for (EvidenceChunk chunk : ranked) {
            if (remaining <= 0) {
                break;
            }
            String text = chunk.text().strip();
            if (text.length() > remaining) {
                text = text.substring(0, remaining);
            }
            builder.append("- [").append(value(chunk.sectionPath())).append("] ").append(text).append('\n');
            remaining -= text.length();
        }
        return builder.toString().strip();
    }

    private Set<String> extractTerms(List<CompoundEvidenceRecord> targets) {
        Set<String> terms = new LinkedHashSet<>();
        for (CompoundEvidenceRecord target : targets) {
            CompoundEvidenceRow row = target.row();
            addTerms(terms, row.compoundOriginalName());
            addTerms(terms, row.compoundStandardName());
            addTerms(terms, row.sourceDescription());
            addTerms(terms, row.sourceCategory());
            addTerms(terms, row.oomyceteScientificName());
        }
        return terms;
    }

    private void addTerms(Set<String> terms, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = TERM_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            terms.add(matcher.group());
        }
    }

    private double chunkScore(EvidenceChunk chunk, Set<String> terms) {
        String text = value(chunk.text()).toLowerCase(Locale.ROOT);
        double score = sectionPriority(chunk.sectionPath());
        for (String term : terms) {
            if (text.contains(term)) {
                score += term.length() >= 5 ? 3 : 1.5;
            }
        }
        return score;
    }

    private double sectionPriority(String sectionPath) {
        String path = value(sectionPath).toLowerCase(Locale.ROOT);
        if (path.contains("method") || path.contains("material") || path.contains("实验") || path.contains("方法") || path.contains("材料")) {
            return 3;
        }
        if (path.contains("result") || path.contains("结果")) {
            return 1.5;
        }
        if (path.contains("abstract") || path.contains("摘要")) {
            return 1;
        }
        if (path.contains("reference") || path.contains("acknowledg") || path.contains("参考文献")) {
            return -2;
        }
        return 0;
    }

    private String displayName(CompoundEvidenceRecord evidence) {
        CompoundEvidenceRow row = evidence.row();
        String standard = value(row.compoundStandardName());
        if (!standard.isBlank()) {
            return standard;
        }
        String original = value(row.compoundOriginalName());
        return original.isBlank() ? "unknown" : original;
    }

    private String responseText(ChatResponse response) {
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            throw new IllegalArgumentException("Model returned no text");
        }
        return response.aiMessage().text().trim();
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('{');
            int last = trimmed.lastIndexOf('}');
            if (first >= 0 && last > first) {
                return trimmed.substring(first, last + 1);
            }
        }
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private String value(String value) {
        return value == null ? "" : value.strip();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResolutionEnvelope(List<ResolutionItem> resolutions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResolutionItem(String evidenceId, String resolvedName) {
    }
}
