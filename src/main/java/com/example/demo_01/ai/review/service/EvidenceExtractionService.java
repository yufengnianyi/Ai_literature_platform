package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class EvidenceExtractionService {

    private static final List<String> TYPED_ENTITY_FIELDS = List.of(
            "species",
            "geneOrProtein",
            "pathwayOrProcess",
            "developmentalStage",
            "phenotype",
            "method",
            "moleculeOrMetabolite",
            "compoundStructureType",
            "compoundSource",
            "antimicrobialActivity",
            "assayMethod",
            "targetOrganism",
            "proposedTarget",
            "mechanism",
            "reference",
            "patentStatus",
            "compoundLocalAlias",
            "compoundCanonicalName",
            "compoundIdentifier",
            "compoundResolutionStatus",
            "cytotoxicitySafety"
    );

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReviewProperties reviewProperties;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    private final LlmBatchProcessor batchProcessor = new LlmBatchProcessor();

    public List<ExtractedEvidence> extract(String mainQuestion,
                                           List<String> subQuestions,
                                           List<RetrievedChunk> chunks) {
        return extract(mainQuestion, subQuestions, chunks, Map.of());
    }

    public List<ExtractedEvidence> extract(String mainQuestion,
                                           List<String> subQuestions,
                                           List<RetrievedChunk> chunks,
                                           Map<UUID, DocumentKnowledgeContext> knowledgeContexts) {
        log.info("Extracting evidence from {} chunks", chunks.size());
        List<ExtractedEvidence> allEvidence = batchProcessor.processInBatches(
                chunks,
                reviewProperties.getExtraction().getBatchSize(),
                batch -> extractBatch(mainQuestion, subQuestions, batch, knowledgeContexts),
                reviewTaskExecutor
        );
        log.info("Evidence extraction complete: {} evidence items from {} chunks",
                allEvidence.size(), chunks.size());
        return allEvidence;
    }

    private List<ExtractedEvidence> extractBatch(String mainQuestion,
                                                  List<String> subQuestions,
                                                  List<RetrievedChunk> batch,
                                                  Map<UUID, DocumentKnowledgeContext> knowledgeContexts) {
        StringBuilder userMsg = new StringBuilder();
        StringBuilder subQuestionsText = new StringBuilder();
        for (int i = 0; i < subQuestions.size(); i++) {
            subQuestionsText.append(i + 1).append(". ").append(subQuestions.get(i)).append("\n");
        }
        StringBuilder chunksText = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            RetrievedChunk c = batch.get(i);
            chunksText.append(PromptResources.format(
                    PromptCatalog.REVIEW_EVIDENCE_EXTRACTION_CHUNK,
                    i + 1,
                    c.chunkId(),
                    c.documentId(),
                    safe(c.documentTitle()),
                    formatKnowledgeContext(c.documentId(), knowledgeContexts),
                    c.text()));
        }
        userMsg.append(PromptResources.format(
                PromptCatalog.REVIEW_EVIDENCE_EXTRACTION_USER,
                mainQuestion,
                subQuestionsText,
                chunksText));

        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_EVIDENCE_EXTRACTION_SYSTEM)),
                    UserMessage.from(userMsg.toString())
            );
            AiMessage ai = response.aiMessage();
            String raw = (ai != null && ai.text() != null) ? ai.text() : "[]";
            List<ExtractedEvidence> parsed = parseEvidenceList(raw);
            return parsed.stream().map(this::normalizeTypedEvidence).toList();
        } catch (Exception e) {
            log.warn("Evidence extraction batch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ExtractedEvidence> parseEvidenceList(String raw) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(extractJson(raw));
        normalizeEvidenceJson(root);
        return objectMapper.convertValue(root, new TypeReference<List<ExtractedEvidence>>() {});
    }

    private void normalizeEvidenceJson(JsonNode root) {
        if (!root.isArray()) {
            return;
        }
        for (JsonNode item : root) {
            if (item instanceof ObjectNode evidence) {
                JsonNode typed = evidence.get("typedEntities");
                if (typed == null || typed.isNull()) {
                    continue;
                }
                if (typed instanceof ObjectNode typedObject) {
                    normalizeTypedEntitiesJson(typedObject);
                } else {
                    evidence.set("typedEntities", objectMapper.createObjectNode());
                }
            }
        }
    }

    private void normalizeTypedEntitiesJson(ObjectNode typed) {
        for (String field : TYPED_ENTITY_FIELDS) {
            JsonNode value = typed.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            typed.set(field, normalizeStringListNode(value));
        }
    }

    private ArrayNode normalizeStringListNode(JsonNode value) {
        ArrayNode out = objectMapper.createArrayNode();
        if (value.isArray()) {
            for (JsonNode item : value) {
                appendStringValue(out, item);
            }
        } else {
            appendStringValue(out, value);
        }
        return out;
    }

    private void appendStringValue(ArrayNode out, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        text = text == null ? "" : text.trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }

    private ExtractedEvidence normalizeTypedEvidence(ExtractedEvidence evidence) {
        TypedEntities typed = evidence.typedEntities();
        List<String> flattened = flattenTypedEntities(typed);
        if (flattened.isEmpty() && evidence.entities() != null) {
            flattened = evidence.entities();
        }
        return new ExtractedEvidence(
                evidence.chunkId(),
                evidence.documentId(),
                evidence.documentTitle(),
                evidence.claim(),
                evidence.finding(),
                evidence.methodology(),
                typed,
                flattened,
                evidence.evidenceType(),
                evidence.confidence(),
                evidence.originalText(),
                evidence.subQuestion()
        );
    }

    private List<String> flattenTypedEntities(TypedEntities typed) {
        if (typed == null) {
            return List.of();
        }
        return java.util.stream.Stream.of(
                        typed.species(),
                        typed.geneOrProtein(),
                        typed.pathwayOrProcess(),
                        typed.developmentalStage(),
                        typed.phenotype(),
                        typed.method(),
                        typed.moleculeOrMetabolite(),
                        typed.compoundStructureType(),
                        typed.compoundSource(),
                        typed.antimicrobialActivity(),
                        typed.assayMethod(),
                        typed.targetOrganism(),
                        typed.proposedTarget(),
                        typed.mechanism(),
                        typed.reference(),
                        typed.patentStatus(),
                        typed.compoundLocalAlias(),
                        typed.compoundCanonicalName(),
                        typed.compoundIdentifier(),
                        typed.compoundResolutionStatus(),
                        typed.cytotoxicitySafety())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }

    private String formatKnowledgeContext(UUID documentId,
                                          Map<UUID, DocumentKnowledgeContext> knowledgeContexts) {
        if (documentId == null || knowledgeContexts == null || knowledgeContexts.isEmpty()) {
            return "";
        }
        DocumentKnowledgeContext context = knowledgeContexts.get(documentId);
        if (context == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("Known document knowledge context:\n");
        out.append("- knowledgeStatus: ").append(context.knowledgeStatus()).append("\n");
        appendList(out, "- knownCompounds", context.knownCompounds());
        appendList(out, "- species", context.species());
        appendList(out, "- genesOrProteins", context.genesOrProteins());
        appendList(out, "- pathwaysOrProcesses", context.pathwaysOrProcesses());
        appendList(out, "- developmentalStages", context.developmentalStages());
        appendList(out, "- methods", context.methods());
        if (context.compoundAliases() != null && !context.compoundAliases().isEmpty()) {
            out.append("- compoundAliasMap:\n");
            for (DocumentCompoundAlias alias : context.compoundAliases()) {
                out.append("  * ").append(alias.localAlias())
                        .append(" -> ")
                        .append(alias.resolvedName() == null || alias.resolvedName().isBlank()
                                ? "unresolved local compound label in this document"
                                : alias.resolvedName())
                        .append(" [").append(alias.resolutionStatus()).append("]");
                if (alias.normalizedCompoundId() != null) {
                    out.append(" id=").append(alias.normalizedCompoundId());
                }
                out.append("\n");
            }
        }
        out.append("\nChunk text:\n");
        return out.toString();
    }

    private void appendList(StringBuilder out, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            out.append(label).append(": ").append(String.join("; ", values)).append("\n");
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "[]";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('[');
            int last = trimmed.lastIndexOf(']');
            if (first >= 0 && last > first) return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private String safe(String s) { return s == null ? "" : s; }
}
