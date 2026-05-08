package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class CompoundEvidenceSynthesizer {

    private static final int MAX_SYNTH_EVIDENCE_FRAGMENTS = 8;

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReviewProperties reviewProperties;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    public List<SynthesizedCompoundRecord> synthesize(
            List<ExtractedEvidence> allEvidence,
            Map<UUID, DocumentKnowledgeContext> knowledgeContexts) {

        if (!reviewProperties.getSynthesis().isEnableCompoundSynthesizer()) {
            return List.of();
        }

        Map<String, List<ExtractedEvidence>> groups = groupByDocumentAndCompound(allEvidence, knowledgeContexts);
        log.info("Compound synthesis: {} groups from {} evidence items", groups.size(), allEvidence.size());

        int workers = Math.min(8, Math.max(1, reviewProperties.getAsyncThreads() * 4));
        Semaphore concurrency = new Semaphore(workers);
        List<Map.Entry<String, List<ExtractedEvidence>>> ordered = new ArrayList<>(groups.entrySet());
        List<CompletableFuture<SynthesizedCompoundRecord>> futures = new ArrayList<>();
        for (Map.Entry<String, List<ExtractedEvidence>> entry : ordered) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                concurrency.acquireUninterruptibly();
                try {
                    return synthesizeOneGroup(entry.getKey(), capEvidenceForSynthesis(entry.getValue()), knowledgeContexts);
                } catch (Exception e) {
                    log.warn("Synthesis failed for group {}: {}", entry.getKey(), e.getMessage());
                    return fallbackRecord(entry.getKey(), entry.getValue());
                } finally {
                    concurrency.release();
                }
            }, reviewTaskExecutor));
        }
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
    }

    public SynthesizedCompoundRecord synthesizeWithHints(
            String groupKey, List<ExtractedEvidence> evidence,
            Map<UUID, DocumentKnowledgeContext> knowledgeContexts,
            List<String> missingFieldHints) {
        try {
            List<ExtractedEvidence> capped = capEvidenceForSynthesis(evidence);
            String systemPrompt = PromptResources.load("review/compound-synthesis-system");
            String userPrompt = buildUserPrompt(groupKey, capped, knowledgeContexts, missingFieldHints);

            ChatResponse response = chatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));

            AiMessage ai = response.aiMessage();
            String raw = ai != null && ai.text() != null ? ai.text() : "{}";
            return objectMapper.readValue(extractJson(raw), SynthesizedCompoundRecord.class);
        } catch (Exception e) {
            log.warn("Synthesis with hints failed for group {}: {}", groupKey, e.getMessage());
            return fallbackRecord(groupKey, evidence);
        }
    }

    private Map<String, List<ExtractedEvidence>> groupByDocumentAndCompound(
            List<ExtractedEvidence> allEvidence,
            Map<UUID, DocumentKnowledgeContext> knowledgeContexts) {

        Map<String, List<ExtractedEvidence>> groups = new LinkedHashMap<>();
        for (ExtractedEvidence ev : allEvidence) {
            String compoundKey = resolveCompoundKey(ev, knowledgeContexts);
            if (compoundKey == null || compoundKey.isBlank()) continue;
            String groupKey = (ev.documentId() != null ? ev.documentId() : "unknown") + "::" + compoundKey;
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(ev);
        }
        return groups;
    }

    private String resolveCompoundKey(ExtractedEvidence ev, Map<UUID, DocumentKnowledgeContext> knowledgeContexts) {
        TypedEntities typed = ev.typedEntities();
        if (typed == null) return null;

        List<String> canonicalNames = typed.compoundCanonicalName();
        if (canonicalNames != null && !canonicalNames.isEmpty()) {
            return canonicalNames.get(0).toLowerCase(Locale.ROOT).trim();
        }

        if (ev.documentId() != null && knowledgeContexts != null) {
            try {
                UUID docUuid = UUID.fromString(ev.documentId());
                DocumentKnowledgeContext ctx = knowledgeContexts.get(docUuid);
                if (ctx != null && ctx.aliasResolutionMap() != null) {
                    List<String> aliases = typed.compoundLocalAlias();
                    if (aliases != null) {
                        for (String alias : aliases) {
                            String resolved = ctx.aliasResolutionMap().get(alias.toLowerCase(Locale.ROOT).trim());
                            if (resolved != null) return resolved.toLowerCase(Locale.ROOT);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        List<String> identifiers = typed.compoundIdentifier();
        if (identifiers != null && !identifiers.isEmpty()) {
            return identifiers.get(0).toLowerCase(Locale.ROOT).trim();
        }

        List<String> molecules = typed.moleculeOrMetabolite();
        if (molecules != null && !molecules.isEmpty()) {
            return molecules.get(0).toLowerCase(Locale.ROOT).trim();
        }

        List<String> localAliases = typed.compoundLocalAlias();
        if (localAliases != null && !localAliases.isEmpty()) {
            return "local:" + (ev.documentId() != null ? ev.documentId() : "unknown") + ":" + localAliases.get(0);
        }

        return null;
    }

    private List<ExtractedEvidence> capEvidenceForSynthesis(List<ExtractedEvidence> evidence) {
        if (evidence == null || evidence.size() <= MAX_SYNTH_EVIDENCE_FRAGMENTS) {
            return evidence == null ? List.of() : evidence;
        }
        return evidence.stream()
                .sorted(Comparator.comparingDouble(ExtractedEvidence::confidence).reversed())
                .limit(MAX_SYNTH_EVIDENCE_FRAGMENTS)
                .toList();
    }

    private SynthesizedCompoundRecord synthesizeOneGroup(
            String groupKey, List<ExtractedEvidence> evidence,
            Map<UUID, DocumentKnowledgeContext> knowledgeContexts) throws Exception {

        String systemPrompt = PromptResources.load("review/compound-synthesis-system");
        String userPrompt = buildUserPrompt(groupKey, evidence, knowledgeContexts, null);

        ChatResponse response = chatModel.chat(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt));

        AiMessage ai = response.aiMessage();
        String raw = ai != null && ai.text() != null ? ai.text() : "{}";
        SynthesizedCompoundRecord record = objectMapper.readValue(extractJson(raw), SynthesizedCompoundRecord.class);

        List<String> chunkIds = evidence.stream()
                .map(ExtractedEvidence::chunkId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        return new SynthesizedCompoundRecord(
                record.compoundName(),
                evidence.get(0).documentId(),
                evidence.get(0).documentTitle(),
                record.role(),
                record.structureType(),
                record.source(),
                record.paradigmActivities() != null ? record.paradigmActivities() : List.of(),
                record.mechanismSummary(),
                record.safetyProfile(),
                record.comparisons() != null ? record.comparisons() : List.of(),
                record.contextNote(),
                record.targetOrganisms() != null ? record.targetOrganisms() : List.of(),
                record.confidence(),
                record.reference(),
                chunkIds,
                record.coverageWarnings() != null ? record.coverageWarnings() : List.of()
        );
    }

    private String buildUserPrompt(String groupKey, List<ExtractedEvidence> evidence,
                                    Map<UUID, DocumentKnowledgeContext> knowledgeContexts,
                                    List<String> missingFieldHints) {
        StringBuilder sb = new StringBuilder();
        String[] parts = groupKey.split("::", 2);
        String compoundName = parts.length > 1 ? parts[1] : groupKey;
        String documentTitle = evidence.isEmpty() ? "" : safe(evidence.get(0).documentTitle());

        sb.append("Compound: ").append(compoundName).append("\n");
        sb.append("Document: ").append(documentTitle).append("\n\n");

        sb.append("Evidence fragments (").append(evidence.size()).append("):\n");
        for (int i = 0; i < evidence.size(); i++) {
            ExtractedEvidence ev = evidence.get(i);
            sb.append("--- Fragment ").append(i + 1).append(" [chunkId=").append(safe(ev.chunkId())).append("] ---\n");
            sb.append("Claim: ").append(safe(ev.claim())).append("\n");
            sb.append("Finding: ").append(safe(ev.finding())).append("\n");
            sb.append("Methodology: ").append(safe(ev.methodology())).append("\n");
            if (ev.typedEntities() != null) {
                TypedEntities t = ev.typedEntities();
                appendList(sb, "antimicrobialActivity", t.antimicrobialActivity());
                appendList(sb, "targetOrganism", t.targetOrganism());
                appendList(sb, "mechanism", t.mechanism());
                appendList(sb, "assayMethod", t.assayMethod());
                appendList(sb, "cytotoxicitySafety", t.cytotoxicitySafety());
                appendList(sb, "compoundStructureType", t.compoundStructureType());
                appendList(sb, "compoundSource", t.compoundSource());
            }
            sb.append("OriginalText: ").append(safe(ev.originalText())).append("\n\n");
        }

        if (missingFieldHints != null && !missingFieldHints.isEmpty()) {
            sb.append("\nMissing fields hint (please pay special attention):\n");
            for (String hint : missingFieldHints) {
                sb.append("- ").append(hint).append("\n");
            }
        }

        return sb.toString();
    }

    private SynthesizedCompoundRecord fallbackRecord(String groupKey, List<ExtractedEvidence> evidence) {
        String[] parts = groupKey.split("::", 2);
        String compoundName = parts.length > 1 ? parts[1] : groupKey;
        String documentId = evidence.isEmpty() ? null : evidence.get(0).documentId();
        String documentTitle = evidence.isEmpty() ? null : evidence.get(0).documentTitle();
        List<String> chunkIds = evidence.stream()
                .map(ExtractedEvidence::chunkId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new SynthesizedCompoundRecord(
                compoundName, documentId, documentTitle,
                null, null, null, List.of(), null, null, List.of(),
                null, List.of(), 0.3, null, chunkIds,
                List.of("LLM_SYNTHESIS_FAILED"));
    }

    private void appendList(StringBuilder sb, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            sb.append(label).append(": ").append(String.join("; ", values)).append("\n");
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('{');
            int last = trimmed.lastIndexOf('}');
            if (first >= 0 && last > first) return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private String safe(String s) { return s == null ? "" : s; }
}
