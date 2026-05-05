package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.kg.KgProperties;
import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.EntityType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ChunkEntityExtractionService {

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private StructuredOutputValidator validator;

    @Resource
    private KgProperties properties;

    public List<ChunkEntityExtraction> extract(RagChunk chunk) {
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= properties.getGraphBuilder().getMaxRetries(); attempt++) {
            try {
                String response = prompt(chunk);
                EntityEnvelope envelope = parse(response, EntityEnvelope.class);
                List<ChunkEntityExtraction> entities = new ArrayList<>();
                if (envelope.entities() != null) {
                    for (EntityCandidate entity : envelope.entities()) {
                        entities.add(new ChunkEntityExtraction(
                                chunk.documentId(),
                                chunk.chunkId(),
                                safe(entity.mentionText()),
                                safe(entity.canonicalName()),
                                entity.entityType(),
                                normalizeKey(entity.normalizedKey(), entity.canonicalName()),
                                entity.aliases() == null ? List.of() : entity.aliases().stream().filter(value -> value != null && !value.isBlank()).toList(),
                                safe(entity.evidenceText()),
                                entity.confidence() == null ? 0.0 : entity.confidence()
                        ));
                    }
                }
                return validator.validateEntities(chunk, entities).stream()
                        .filter(entity -> entity.confidence() >= properties.getEntityConfidenceThreshold())
                        .toList();
            } catch (Exception ex) {
                lastFailure = ex;
            }
        }
        throw new IllegalStateException("Entity extraction failed for chunk " + chunk.chunkId(), lastFailure);
    }

    private String prompt(RagChunk chunk) {
        ChatResponse response = chatModel.chat(
                SystemMessage.from(PromptResources.load(PromptCatalog.KG_CHUNK_ENTITY_EXTRACTION_SYSTEM)),
                UserMessage.from(PromptResources.format(PromptCatalog.KG_CHUNK_ENTITY_EXTRACTION_USER,
                        properties.getSchemaVersion(),
                        safe(chunk.title()),
                        safe(chunk.sectionPath()),
                        chunk.chunkId(),
                        chunk.text()))
        );
        AiMessage aiMessage = response.aiMessage();
        return aiMessage == null ? "{\"entities\":[]}" : aiMessage.text();
    }

    private <T> T parse(String raw, Class<T> type) {
        try {
            return objectMapper.readValue(extractJson(raw), type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse entity extraction JSON", e);
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{\"entities\":[]}";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstBrace = trimmed.indexOf('{');
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1);
            }
        }
        return trimmed;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeKey(String normalizedKey, String canonicalName) {
        String source = normalizedKey != null && !normalizedKey.isBlank() ? normalizedKey : canonicalName;
        return source == null ? "" : source.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EntityEnvelope(List<EntityCandidate> entities) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EntityCandidate(
            @JsonProperty("mention_text") String mentionText,
            @JsonProperty("canonical_name") String canonicalName,
            @JsonProperty("entity_type") EntityType entityType,
            @JsonProperty("normalized_key") String normalizedKey,
            List<String> aliases,
            @JsonProperty("evidence_text") String evidenceText,
            Double confidence
    ) {
    }
}
