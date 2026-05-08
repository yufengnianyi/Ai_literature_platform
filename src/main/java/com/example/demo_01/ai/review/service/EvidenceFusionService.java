package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
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
public class EvidenceFusionService {

    private static final int SINGLE_PASS_THRESHOLD = 15;

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private QueryExpansionService queryExpansionService;

    private final LlmBatchProcessor batchProcessor = new LlmBatchProcessor();

    public List<FusedEvidenceGroup> fuse(List<String> subQuestions,
                                         List<ExtractedEvidence> allEvidence) {
        List<ExtractedEvidence> normalizedEvidence = normalizeEntities(allEvidence);

        Map<String, List<ExtractedEvidence>> grouped = normalizedEvidence.stream()
                .collect(Collectors.groupingBy(
                        e -> e.subQuestion() != null ? e.subQuestion() : "general",
                        LinkedHashMap::new, Collectors.toList()));

        List<FusedEvidenceGroup> groups = new ArrayList<>();
        for (String sq : subQuestions) {
            List<ExtractedEvidence> groupEvidence = grouped.getOrDefault(sq, List.of());
            if (groupEvidence.isEmpty()) {
                for (Map.Entry<String, List<ExtractedEvidence>> entry : grouped.entrySet()) {
                    if (entry.getKey().toLowerCase().contains(sq.toLowerCase().substring(0, Math.min(20, sq.length())))) {
                        groupEvidence = entry.getValue();
                        break;
                    }
                }
            }
            if (groupEvidence.isEmpty()) {
                groups.add(new FusedEvidenceGroup(sq, "No evidence found for this sub-question.",
                        List.of(), 0, 0, List.of()));
                continue;
            }
            groups.add(fuseGroup(sq, groupEvidence));
        }

        log.info("Evidence fusion complete: {} groups", groups.size());
        return groups;
    }

    private FusedEvidenceGroup fuseGroup(String subQuestion, List<ExtractedEvidence> evidence) {
        if (evidence.size() <= SINGLE_PASS_THRESHOLD) {
            return fuseGroupSinglePass(subQuestion, evidence);
        }
        List<String> intermediateSummaries = batchProcessor.processInBatches(
                evidence, SINGLE_PASS_THRESHOLD,
                batch -> List.of(summarizeBatch(subQuestion, batch)),
                null
        );
        String combined = String.join("\n\n", intermediateSummaries);
        return fuseFromSummary(subQuestion, evidence, combined);
    }

    private FusedEvidenceGroup fuseGroupSinglePass(String subQuestion, List<ExtractedEvidence> evidence) {
        String evidenceText = formatEvidenceForLlm(evidence);
        try {
            ChatResponse response = reasoningChatClient.chatCore(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_EVIDENCE_FUSION_SYSTEM)),
                    UserMessage.from(PromptResources.format(PromptCatalog.REVIEW_EVIDENCE_FUSION_SINGLE_USER, subQuestion, evidenceText))
            );
            AiMessage ai = response.aiMessage();
            String raw = (ai != null && ai.text() != null) ? ai.text() : "{}";
            FusionResult result = objectMapper.readValue(extractJson(raw), FusionResult.class);
            return toFusedGroup(subQuestion, evidence, result);
        } catch (Exception e) {
            log.warn("Fusion LLM call failed for sub-question '{}': {}", subQuestion, e.getMessage());
            return fallbackGroup(subQuestion, evidence);
        }
    }

    private FusedEvidenceGroup fuseFromSummary(String subQuestion, List<ExtractedEvidence> evidence, String summaries) {
        try {
            ChatResponse response = reasoningChatClient.chatCore(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_EVIDENCE_FUSION_SYSTEM)),
                    UserMessage.from(PromptResources.format(PromptCatalog.REVIEW_EVIDENCE_FUSION_SUMMARY_USER, subQuestion, summaries))
            );
            AiMessage ai = response.aiMessage();
            String raw = (ai != null && ai.text() != null) ? ai.text() : "{}";
            FusionResult result = objectMapper.readValue(extractJson(raw), FusionResult.class);
            return toFusedGroup(subQuestion, evidence, result);
        } catch (Exception e) {
            log.warn("Fusion reduce step failed: {}", e.getMessage());
            return fallbackGroup(subQuestion, evidence);
        }
    }

    private String summarizeBatch(String subQuestion, List<ExtractedEvidence> batch) {
        String evidenceText = formatEvidenceForLlm(batch);
        try {
            ChatResponse response = reasoningChatClient.chatStandard(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_EVIDENCE_FUSION_BATCH_SUMMARY_SYSTEM)),
                    UserMessage.from(PromptResources.format(PromptCatalog.REVIEW_EVIDENCE_FUSION_BATCH_SUMMARY_USER, subQuestion, evidenceText))
            );
            AiMessage ai = response.aiMessage();
            return (ai != null && ai.text() != null) ? ai.text() : "";
        } catch (Exception e) {
            log.warn("Batch summarization failed: {}", e.getMessage());
            return batch.stream().map(ExtractedEvidence::claim).collect(Collectors.joining("; "));
        }
    }

    private List<ExtractedEvidence> normalizeEntities(List<ExtractedEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .map(this::normalizeEvidence)
                .toList();
    }

    private ExtractedEvidence normalizeEvidence(ExtractedEvidence evidence) {
        if (evidence.entities() == null && evidence.typedEntities() == null) {
            return evidence;
        }
        TypedEntities normalizedTyped = normalizeTypedEntities(evidence.typedEntities());
        List<String> normalized = flattenTypedEntities(normalizedTyped);
        if (normalized.isEmpty() && evidence.entities() != null) {
            normalized = evidence.entities().stream()
                    .map(queryExpansionService::findCanonical)
                    .toList();
        }
        return new ExtractedEvidence(
                evidence.chunkId(), evidence.documentId(), evidence.documentTitle(),
                evidence.claim(), evidence.finding(), evidence.methodology(),
                normalizedTyped,
                normalized, evidence.evidenceType(), evidence.confidence(),
                evidence.originalText(), evidence.subQuestion());
    }

    private TypedEntities normalizeTypedEntities(TypedEntities typedEntities) {
        if (typedEntities == null) {
            return null;
        }
        return new TypedEntities(
                normalizeList(typedEntities.species()),
                normalizeList(typedEntities.geneOrProtein()),
                normalizeList(typedEntities.pathwayOrProcess()),
                normalizeList(typedEntities.developmentalStage()),
                normalizeList(typedEntities.phenotype()),
                normalizeList(typedEntities.method()),
                cleanList(typedEntities.moleculeOrMetabolite()),
                cleanList(typedEntities.compoundStructureType()),
                cleanList(typedEntities.compoundSource()),
                cleanList(typedEntities.antimicrobialActivity()),
                cleanList(typedEntities.assayMethod()),
                cleanList(typedEntities.targetOrganism()),
                cleanList(typedEntities.proposedTarget()),
                cleanList(typedEntities.mechanism()),
                cleanList(typedEntities.reference()),
                cleanList(typedEntities.patentStatus()),
                cleanList(typedEntities.compoundLocalAlias()),
                cleanList(typedEntities.compoundCanonicalName()),
                cleanList(typedEntities.compoundIdentifier()),
                cleanList(typedEntities.compoundResolutionStatus()),
                cleanList(typedEntities.cytotoxicitySafety())
        );
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(queryExpansionService::findCanonical)
                .distinct()
                .toList();
    }

    private List<String> cleanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> flattenTypedEntities(TypedEntities typedEntities) {
        if (typedEntities == null) {
            return List.of();
        }
        return java.util.stream.Stream.of(
                        typedEntities.species(),
                        typedEntities.geneOrProtein(),
                        typedEntities.pathwayOrProcess(),
                        typedEntities.developmentalStage(),
                        typedEntities.phenotype(),
                        typedEntities.method(),
                        typedEntities.moleculeOrMetabolite(),
                        typedEntities.compoundStructureType(),
                        typedEntities.compoundSource(),
                        typedEntities.antimicrobialActivity(),
                        typedEntities.assayMethod(),
                        typedEntities.targetOrganism(),
                        typedEntities.proposedTarget(),
                        typedEntities.mechanism(),
                        typedEntities.reference(),
                        typedEntities.patentStatus(),
                        typedEntities.compoundLocalAlias(),
                        typedEntities.compoundCanonicalName(),
                        typedEntities.compoundIdentifier(),
                        typedEntities.compoundResolutionStatus(),
                        typedEntities.cytotoxicitySafety())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    private String formatEvidenceForLlm(List<ExtractedEvidence> evidence) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            ExtractedEvidence e = evidence.get(i);
            sb.append("Evidence ").append(i + 1)
                    .append(" [source=").append(safe(e.documentTitle())).append("]: ")
                    .append("Claim: ").append(safe(e.claim()))
                    .append(" | Finding: ").append(safe(e.finding()))
                    .append(" | Method: ").append(safe(e.methodology()))
                    .append(" | Compounds: ").append(formatCompounds(e.typedEntities()))
                    .append(" | Confidence: ").append(e.confidence())
                    .append("\n");
        }
        return sb.toString();
    }

    private String formatCompounds(TypedEntities typed) {
        if (typed == null) {
            return "";
        }
        return String.join("; ", java.util.stream.Stream.of(
                        typed.compoundCanonicalName(),
                        typed.compoundIdentifier(),
                        typed.moleculeOrMetabolite(),
                        typed.compoundLocalAlias())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
    }

    private FusedEvidenceGroup toFusedGroup(String subQuestion, List<ExtractedEvidence> evidence, FusionResult result) {
        List<EvidenceCluster> clusters = new ArrayList<>();
        if (result.clusters() != null) {
            for (FusionCluster fc : result.clusters()) {
                clusters.add(new EvidenceCluster(
                        fc.claimSummary(),
                        fc.consistency() != null ? Consistency.valueOf(fc.consistency()) : Consistency.INSUFFICIENT,
                        List.of(),
                        fc.sourceDocuments() != null ? fc.sourceDocuments() : List.of()
                ));
            }
        }
        int supporting = (int) clusters.stream().filter(c -> c.consistency() == Consistency.CONSISTENT).count();
        int conflicting = (int) clusters.stream().filter(c -> c.consistency() == Consistency.CONFLICTING).count();
        return new FusedEvidenceGroup(subQuestion,
                result.groupSummary() != null ? result.groupSummary() : "",
                clusters, supporting, conflicting,
                result.consistencyNotes() != null ? result.consistencyNotes() : List.of());
    }

    private FusedEvidenceGroup fallbackGroup(String subQuestion, List<ExtractedEvidence> evidence) {
        List<String> docs = evidence.stream()
                .map(ExtractedEvidence::documentTitle).filter(Objects::nonNull).distinct().toList();
        return new FusedEvidenceGroup(subQuestion,
                "Evidence summary pending (LLM fusion failed).",
                List.of(new EvidenceCluster("See individual evidence items",
                        Consistency.INSUFFICIENT, List.of(), docs)),
                0, 0, List.of("Automated fusion was not possible"));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FusionResult(
            String groupSummary,
            List<FusionCluster> clusters,
            List<String> consistencyNotes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FusionCluster(
            String claimSummary,
            String consistency,
            List<String> sourceDocuments
    ) {}
}
