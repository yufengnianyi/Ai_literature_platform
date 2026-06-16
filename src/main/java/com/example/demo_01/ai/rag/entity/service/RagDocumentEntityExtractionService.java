package com.example.demo_01.ai.rag.entity.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.*;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RagDocumentEntityExtractionService {

    private static final int CHUNK_BATCH_SIZE = 12;
    private static final int MAX_EVIDENCE_TEXTS_PER_ENTITY = 5;

    @Resource
    private ReviewRepository reviewRepository;

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private ObjectMapper objectMapper;

    public RagDocumentEntityExtraction extractDocument(UUID documentId, String question) {
        if (documentId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "documentId is required");
        }
        List<RetrievedChunk> chunks = reviewRepository.findAllChunksByDocumentId(documentId);
        if (chunks == null || chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                    "No chunks found for document: " + documentId);
        }

        List<EntityExtractionEnvelope> outputs = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += CHUNK_BATCH_SIZE) {
            int end = Math.min(start + CHUNK_BATCH_SIZE, chunks.size());
            outputs.add(callExtractionModel(documentId, documentTitle(chunks), question, chunks.subList(start, end)));
        }
        EntityExtractionEnvelope merged = merge(outputs);
        return new RagDocumentEntityExtraction(
                documentId,
                documentTitle(chunks),
                normalize(question),
                chunks.size(),
                merged.entities(),
                merged.warnings()
        );
    }

    public List<RagDocumentEntityExtraction> extractBatch(List<UUID> documentIds, String question) {
        List<UUID> safeIds = documentIds == null ? List.of() : documentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (safeIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "documentIds is required");
        }
        return safeIds.stream()
                .map(documentId -> extractDocument(documentId, question))
                .toList();
    }

    EntityExtractionEnvelope callExtractionModel(UUID documentId,
                                                 String documentTitle,
                                                 String question,
                                                 List<RetrievedChunk> chunks) {
        String userMessage = """
                Question, optional salience hint:
                %s

                Document:
                %s (%s)

                Chunks:
                %s
                """.formatted(firstNonBlank(question, "(none)"),
                firstNonBlank(documentTitle, "Untitled document"),
                documentId,
                renderChunks(chunks));
        try {
            ChatResponse response = reasoningChatClient.chatStandard(
                    SystemMessage.from(PromptResources.load(PromptCatalog.RAG_DOCUMENT_ENTITY_EXTRACTION_SYSTEM)),
                    UserMessage.from(userMessage));
            AiMessage ai = response == null ? null : response.aiMessage();
            String raw = ai == null ? null : ai.text();
            return normalizeEnvelope(objectMapper.readValue(extractJson(raw), EntityExtractionEnvelope.class));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Document entity extraction failed for " + documentId + ": " + e.getMessage());
        }
    }

    EntityExtractionEnvelope normalizeEnvelope(EntityExtractionEnvelope envelope) {
        if (envelope == null) {
            return new EntityExtractionEnvelope(List.of(), List.of());
        }
        List<RagDocumentEntity> entities = envelope.entities() == null ? List.of() : envelope.entities().stream()
                .filter(Objects::nonNull)
                .map(this::normalizeEntity)
                .filter(entity -> !entity.canonicalName().isBlank() || !entity.mentionText().isBlank())
                .toList();
        return new EntityExtractionEnvelope(entities, distinct(envelope.warnings()));
    }

    EntityExtractionEnvelope merge(List<EntityExtractionEnvelope> envelopes) {
        Map<String, EntityAccumulator> byEntity = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (EntityExtractionEnvelope envelope : envelopes == null ? List.<EntityExtractionEnvelope>of() : envelopes) {
            EntityExtractionEnvelope normalized = normalizeEnvelope(envelope);
            warnings.addAll(normalized.warnings());
            for (RagDocumentEntity entity : normalized.entities()) {
                String key = entityKey(entity);
                byEntity.computeIfAbsent(key, ignored -> new EntityAccumulator(entity)).merge(entity);
            }
        }
        List<RagDocumentEntity> entities = byEntity.values().stream()
                .map(EntityAccumulator::toEntity)
                .sorted(Comparator.comparing(RagDocumentEntity::entityType)
                        .thenComparing(RagDocumentEntity::canonicalName))
                .toList();
        return new EntityExtractionEnvelope(entities, distinct(warnings));
    }

    String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{\"entities\":[],\"warnings\":[]}";
        }
        String trimmed = raw.trim();
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private RagDocumentEntity normalizeEntity(RagDocumentEntity entity) {
        return new RagDocumentEntity(
                normalize(entity.mentionText()),
                normalize(firstNonBlank(entity.canonicalName(), entity.mentionText())),
                normalizeType(entity.entityType()),
                distinct(entity.aliases()),
                distinct(entity.sourceChunkIds()),
                distinct(entity.evidenceTexts()).stream().limit(MAX_EVIDENCE_TEXTS_PER_ENTITY).toList(),
                clamp(entity.confidence())
        );
    }

    private String renderChunks(List<RetrievedChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunk chunk : chunks == null ? List.<RetrievedChunk>of() : chunks) {
            builder.append("\n--- chunk_id=").append(firstNonBlank(chunk.chunkId(), "-"))
                    .append("; section=").append(firstNonBlank(chunk.sectionPath(), "-"))
                    .append(" ---\n")
                    .append(firstNonBlank(chunk.text(), ""));
        }
        return builder.toString();
    }

    private String documentTitle(List<RetrievedChunk> chunks) {
        if (chunks == null) {
            return "";
        }
        return chunks.stream()
                .map(RetrievedChunk::documentTitle)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String entityKey(RagDocumentEntity entity) {
        return normalizeType(entity.entityType()) + "|"
                + firstNonBlank(entity.canonicalName(), entity.mentionText())
                .toLowerCase(Locale.ROOT);
    }

    private List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String normalizeType(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "OTHER" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntityExtractionEnvelope(
            List<RagDocumentEntity> entities,
            List<String> warnings
    ) {
    }

    private static final class EntityAccumulator {
        private String mentionText;
        private String canonicalName;
        private String entityType;
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Set<String> sourceChunkIds = new LinkedHashSet<>();
        private final Set<String> evidenceTexts = new LinkedHashSet<>();
        private double confidence;

        private EntityAccumulator(RagDocumentEntity entity) {
            this.mentionText = entity.mentionText();
            this.canonicalName = entity.canonicalName();
            this.entityType = entity.entityType();
        }

        private void merge(RagDocumentEntity entity) {
            if (canonicalName == null || canonicalName.isBlank()) {
                canonicalName = entity.canonicalName();
            }
            if (mentionText == null || mentionText.isBlank()) {
                mentionText = entity.mentionText();
            }
            if (entity.aliases() != null) {
                aliases.addAll(entity.aliases());
            }
            if (entity.sourceChunkIds() != null) {
                sourceChunkIds.addAll(entity.sourceChunkIds());
            }
            if (entity.evidenceTexts() != null) {
                evidenceTexts.addAll(entity.evidenceTexts());
            }
            confidence = Math.max(confidence, entity.confidence());
        }

        private RagDocumentEntity toEntity() {
            return new RagDocumentEntity(
                    mentionText,
                    canonicalName,
                    entityType,
                    List.copyOf(aliases),
                    List.copyOf(sourceChunkIds),
                    evidenceTexts.stream().limit(MAX_EVIDENCE_TEXTS_PER_ENTITY).collect(Collectors.toList()),
                    confidence
            );
        }
    }
}
