package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.ChunkRelationExtraction;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class StructuredOutputValidator {

    public List<ChunkEntityExtraction> validateEntities(RagChunk chunk, List<ChunkEntityExtraction> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        String haystack = normalize(chunk.text());
        Set<String> seen = new HashSet<>();
        for (ChunkEntityExtraction entity : entities) {
            require(entity.documentId() != null && entity.documentId().equals(chunk.documentId()), "entity document_id mismatch");
            require(nonBlank(entity.chunkId()) && entity.chunkId().equals(chunk.chunkId()), "entity chunk_id mismatch");
            require(nonBlank(entity.mentionText()), "entity mention_text is required");
            require(nonBlank(entity.canonicalName()), "entity canonical_name is required");
            require(entity.entityType() != null, "entity_type is required");
            require(nonBlank(entity.normalizedKey()), "normalized_key is required");
            require(nonBlank(entity.evidenceText()), "entity evidence_text is required");
            require(entity.confidence() >= 0.0 && entity.confidence() <= 1.0, "entity confidence must be between 0 and 1");
            require(haystack.contains(normalize(entity.evidenceText())), "entity evidence_text must come from the chunk");
            require(seen.add(entity.normalizedKey() + "|" + entity.entityType().name() + "|" + normalize(entity.evidenceText())),
                    "duplicate entity output for chunk");
        }
        return entities;
    }

    public List<ChunkRelationExtraction> validateRelations(RagChunk chunk,
                                                           List<ChunkRelationExtraction> relations,
                                                           List<ChunkEntityExtraction> entities) {
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }
        String haystack = normalize(chunk.text());
        Set<String> validKeys = entities.stream().map(ChunkEntityExtraction::normalizedKey).collect(java.util.stream.Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (ChunkRelationExtraction relation : relations) {
            require(relation.documentId() != null && relation.documentId().equals(chunk.documentId()), "relation document_id mismatch");
            require(nonBlank(relation.chunkId()) && relation.chunkId().equals(chunk.chunkId()), "relation chunk_id mismatch");
            require(nonBlank(relation.headNormalizedKey()), "relation head_normalized_key is required");
            require(nonBlank(relation.tailNormalizedKey()), "relation tail_normalized_key is required");
            require(relation.relationType() != null, "relation_type is required");
            require(nonBlank(relation.evidenceText()), "relation evidence_text is required");
            require(relation.confidence() >= 0.0 && relation.confidence() <= 1.0, "relation confidence must be between 0 and 1");
            require(validKeys.contains(relation.headNormalizedKey()), "relation head_normalized_key must reference extracted entities");
            require(validKeys.contains(relation.tailNormalizedKey()), "relation tail_normalized_key must reference extracted entities");
            require(!relation.headNormalizedKey().equals(relation.tailNormalizedKey()), "relation self loops are not allowed");
            require(haystack.contains(normalize(relation.evidenceText())), "relation evidence_text must come from the chunk");
            require(seen.add(relation.headNormalizedKey() + "|" + relation.relationType().name() + "|" + relation.tailNormalizedKey()
                            + "|" + normalize(relation.evidenceText())),
                    "duplicate relation output for chunk");
        }
        return relations;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
