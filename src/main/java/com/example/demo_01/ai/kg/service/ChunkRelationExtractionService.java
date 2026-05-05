package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.kg.KgProperties;
import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.ChunkRelationExtraction;
import com.example.demo_01.ai.kg.model.KgModels.RelationType;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

@Service
public class ChunkRelationExtractionService {

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private StructuredOutputValidator validator;

    @Resource
    private KgProperties properties;

    public List<ChunkRelationExtraction> extract(RagChunk chunk, List<ChunkEntityExtraction> entities) {
        if (entities == null || entities.size() < 2) {
            return List.of();
        }
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= properties.getGraphBuilder().getMaxRetries(); attempt++) {
            try {
                String response = prompt(chunk, entities);
                RelationEnvelope envelope = parse(response, RelationEnvelope.class);
                List<ChunkRelationExtraction> relations = new ArrayList<>();
                if (envelope.relations() != null) {
                    for (RelationCandidate relation : envelope.relations()) {
                        relations.add(new ChunkRelationExtraction(
                                chunk.documentId(),
                                chunk.chunkId(),
                                safe(relation.headNormalizedKey()),
                                relation.relationType(),
                                safe(relation.tailNormalizedKey()),
                                safe(relation.evidenceText()),
                                relation.confidence() == null ? 0.0 : relation.confidence()
                        ));
                    }
                }
                return validator.validateRelations(chunk, relations, entities).stream()
                        .filter(relation -> relation.confidence() >= properties.getRelationConfidenceThreshold())
                        .toList();
            } catch (Exception ex) {
                lastFailure = ex;
            }
        }
        throw new IllegalStateException("Relation extraction failed for chunk " + chunk.chunkId(), lastFailure);
    }

    private String prompt(RagChunk chunk, List<ChunkEntityExtraction> entities) {
        String entityJson;
        try {
            entityJson = objectMapper.writeValueAsString(entities);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize relation extraction entities", e);
        }
        ChatResponse response = chatModel.chat(
                SystemMessage.from(PromptResources.load(PromptCatalog.KG_CHUNK_RELATION_EXTRACTION_SYSTEM)),
                UserMessage.from(PromptResources.format(PromptCatalog.KG_CHUNK_RELATION_EXTRACTION_USER,
                        properties.getSchemaVersion(),
                        chunk.chunkId(),
                        safe(chunk.sectionPath()),
                        entityJson,
                        chunk.text()))
        );
        AiMessage aiMessage = response.aiMessage();
        return aiMessage == null ? "{\"relations\":[]}" : aiMessage.text();
    }

    private <T> T parse(String raw, Class<T> type) {
        try {
            return objectMapper.readValue(extractJson(raw), type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse relation extraction JSON", e);
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{\"relations\":[]}";
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RelationEnvelope(List<RelationCandidate> relations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RelationCandidate(
            @JsonProperty("head_normalized_key") String headNormalizedKey,
            @JsonProperty("relation_type") RelationType relationType,
            @JsonProperty("tail_normalized_key") String tailNormalizedKey,
            @JsonProperty("evidence_text") String evidenceText,
            Double confidence
    ) {
    }
}
