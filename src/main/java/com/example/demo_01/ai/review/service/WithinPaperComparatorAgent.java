package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WithinPaperComparatorAgent {

    private static final String SYSTEM_PROMPT = """
            You compare compounds within the SAME paper based on their existing synthesis profiles.
            Given profiles (with keyMetric, doseGradient, paradigmActivities), produce for each compound:
            1. A conclusionPhrase (≤30 words English) summarizing its relative efficacy within this paper.
            2. Any missing comparative relations that can be derived from the data.
            
            Return JSON only:
            {"results": [{"compoundName": "...", "conclusionPhrase": "...", "additionalComparisons": [{"referenceCompound": "...", "relation": "...", "basis": "...", "derivedEquivalence": "..."}]}]}
            
            Rules:
            - Base conclusions ONLY on existing keyMetric/doseGradient facts. Do NOT invent new evidence.
            - conclusionPhrase examples: "most potent of six tested", "≈ reference at 40× lower dose", "weak activity, limited by cytotoxicity"
            - Reciprocal relations: if A is "50× more effective than B", then B should get "50× less effective than A".
            - Do not include markdown fences or text outside JSON.
            """;

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private ObjectMapper objectMapper;

    public List<SynthesizedCompoundRecord> compare(List<SynthesizedCompoundRecord> profiles) {
        if (profiles == null || profiles.size() < 2) {
            return profiles != null ? profiles : List.of();
        }

        String summary = profiles.stream()
                .map(this::summarize)
                .collect(Collectors.joining("\n\n"));

        try {
            ChatResponse response = reasoningChatClient.chatStandard(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from("Paper compound profiles:\n\n" + summary));
            AiMessage ai = response.aiMessage();
            String raw = ai != null && ai.text() != null ? ai.text() : "{}";
            ComparatorOutput output = objectMapper.readValue(extractJson(raw), ComparatorOutput.class);

            if (output.results == null || output.results.isEmpty()) {
                return profiles;
            }

            Map<String, CompoundResult> byName = new HashMap<>();
            for (CompoundResult r : output.results) {
                if (r.compoundName != null) {
                    byName.put(r.compoundName.toLowerCase(Locale.ROOT), r);
                }
            }

            List<SynthesizedCompoundRecord> enhanced = new ArrayList<>();
            for (SynthesizedCompoundRecord rec : profiles) {
                CompoundResult cr = rec.compoundName() != null
                        ? byName.get(rec.compoundName().toLowerCase(Locale.ROOT)) : null;
                if (cr == null) {
                    enhanced.add(rec);
                    continue;
                }

                List<ComparativeRelation> comparisons = new ArrayList<>(
                        rec.comparisons() != null ? rec.comparisons() : List.of());
                if (cr.additionalComparisons != null) {
                    for (ComparisonEntry ce : cr.additionalComparisons) {
                        boolean exists = comparisons.stream()
                                .anyMatch(c -> c.referenceCompound() != null
                                        && c.referenceCompound().equalsIgnoreCase(ce.referenceCompound));
                        if (!exists) {
                            comparisons.add(new ComparativeRelation(
                                    ce.referenceCompound, ce.relation, ce.basis, ce.derivedEquivalence));
                        }
                    }
                }

                enhanced.add(new SynthesizedCompoundRecord(
                        rec.compoundName(), rec.documentId(), rec.documentTitle(),
                        rec.role(), rec.structureType(), rec.source(),
                        rec.paradigmActivities(), rec.mechanismSummary(), rec.safetyProfile(),
                        comparisons, cr.conclusionPhrase != null ? cr.conclusionPhrase : rec.contextNote(),
                        rec.targetOrganisms(), rec.confidence(), rec.reference(),
                        rec.evidenceChunkIds(), rec.coverageWarnings()));
            }
            log.info("WithinPaperComparator enhanced {} profiles", enhanced.size());
            return enhanced;
        } catch (Exception e) {
            log.warn("WithinPaperComparator failed: {}", e.getMessage());
            return profiles;
        }
    }

    private String summarize(SynthesizedCompoundRecord rec) {
        StringBuilder sb = new StringBuilder();
        sb.append("Compound: ").append(rec.compoundName()).append(" [").append(rec.role()).append("]\n");
        if (rec.paradigmActivities() != null) {
            for (ParadigmActivityBlock pab : rec.paradigmActivities()) {
                sb.append("  ").append(pab.paradigm());
                if (pab.keyMetric() != null && pab.keyMetric().type() != null) {
                    sb.append(" ").append(pab.keyMetric().type()).append("=").append(pab.keyMetric().value());
                }
                if (pab.doseGradient() != null && !pab.doseGradient().isEmpty()) {
                    sb.append(" doses: ");
                    pab.doseGradient().stream().limit(3).forEach(dr ->
                            sb.append(dr.concentration()).append("→").append(dr.effect()).append("; "));
                }
                sb.append("\n");
            }
        }
        if (rec.comparisons() != null && !rec.comparisons().isEmpty()) {
            for (ComparativeRelation cr : rec.comparisons()) {
                sb.append("  vs ").append(cr.referenceCompound()).append(": ").append(cr.relation()).append("\n");
            }
        }
        return sb.toString();
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ComparatorOutput {
        public List<CompoundResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CompoundResult {
        public String compoundName;
        public String conclusionPhrase;
        public List<ComparisonEntry> additionalComparisons;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ComparisonEntry {
        public String referenceCompound;
        public String relation;
        public String basis;
        public String derivedEquivalence;
    }
}
