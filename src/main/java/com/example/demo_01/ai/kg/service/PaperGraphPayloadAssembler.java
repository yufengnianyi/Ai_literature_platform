package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.kg.KgProperties;
import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.ChunkRelationExtraction;
import com.example.demo_01.ai.kg.model.KgModels.EntityPayload;
import com.example.demo_01.ai.kg.model.KgModels.PaperGraphPayload;
import com.example.demo_01.ai.kg.model.KgModels.PassagePayload;
import com.example.demo_01.ai.kg.model.KgModels.RelationPayload;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PaperGraphPayloadAssembler {

    public PaperGraphPayload assemble(UUID documentId,
                                      String canonicalKey,
                                      RagDocumentMetadata metadata,
                                      List<RagChunk> chunks,
                                      List<ChunkEntityExtraction> entities,
                                      List<ChunkRelationExtraction> relations,
                                      KgProperties properties) {
        List<PassagePayload> passages = chunks.stream()
                .map(chunk -> new PassagePayload(chunk.chunkId(), chunk.chunkIndex(), chunk.sectionPath(), chunk.text()))
                .toList();

        Map<String, EntityAccumulator> entityMap = new LinkedHashMap<>();
        for (ChunkEntityExtraction entity : entities) {
            String key = entity.normalizedKey() + "|" + entity.entityType().name();
            entityMap.computeIfAbsent(key, ignored -> new EntityAccumulator(entity))
                    .merge(entity);
        }

        Map<String, RelationAccumulator> relationMap = new LinkedHashMap<>();
        for (ChunkRelationExtraction relation : relations) {
            String key = relation.headNormalizedKey() + "|" + relation.relationType().name() + "|" + relation.tailNormalizedKey();
            relationMap.computeIfAbsent(key, ignored -> new RelationAccumulator(relation))
                    .merge(relation);
        }

        return new PaperGraphPayload(
                documentId,
                canonicalKey,
                metadata == null ? null : metadata.doiNormalized(),
                metadata == null ? null : metadata.title(),
                metadata == null ? null : metadata.publicationYear(),
                passages,
                entityMap.values().stream().map(EntityAccumulator::toPayload).toList(),
                relationMap.values().stream().map(RelationAccumulator::toPayload).toList(),
                properties.getSchemaVersion()
        );
    }

    private static final class EntityAccumulator {
        private final String normalizedKey;
        private final com.example.demo_01.ai.kg.model.KgModels.EntityType entityType;
        private String canonicalName;
        private double confidence;
        private final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        private final LinkedHashSet<String> chunkIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> evidenceTexts = new LinkedHashSet<>();

        private EntityAccumulator(ChunkEntityExtraction entity) {
            this.normalizedKey = entity.normalizedKey();
            this.entityType = entity.entityType();
            this.canonicalName = entity.canonicalName();
            this.confidence = entity.confidence();
            merge(entity);
        }

        private void merge(ChunkEntityExtraction entity) {
            if (entity.canonicalName() != null && entity.canonicalName().length() > this.canonicalName.length()) {
                this.canonicalName = entity.canonicalName();
            }
            this.confidence = Math.max(this.confidence, entity.confidence());
            this.aliases.addAll(entity.aliases());
            this.chunkIds.add(entity.chunkId());
            this.evidenceTexts.add(entity.evidenceText());
        }

        private EntityPayload toPayload() {
            return new EntityPayload(
                    normalizedKey,
                    canonicalName,
                    entityType,
                    List.copyOf(aliases),
                    List.copyOf(chunkIds),
                    List.copyOf(evidenceTexts),
                    confidence
            );
        }
    }

    private static final class RelationAccumulator {
        private final String headNormalizedKey;
        private final com.example.demo_01.ai.kg.model.KgModels.RelationType relationType;
        private final String tailNormalizedKey;
        private double confidence;
        private final LinkedHashSet<String> chunkIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> evidenceTexts = new LinkedHashSet<>();

        private RelationAccumulator(ChunkRelationExtraction relation) {
            this.headNormalizedKey = relation.headNormalizedKey();
            this.relationType = relation.relationType();
            this.tailNormalizedKey = relation.tailNormalizedKey();
            this.confidence = relation.confidence();
            merge(relation);
        }

        private void merge(ChunkRelationExtraction relation) {
            this.confidence = Math.max(this.confidence, relation.confidence());
            this.chunkIds.add(relation.chunkId());
            this.evidenceTexts.add(relation.evidenceText());
        }

        private RelationPayload toPayload() {
            return new RelationPayload(
                    headNormalizedKey,
                    relationType,
                    tailNormalizedKey,
                    List.copyOf(chunkIds),
                    List.copyOf(evidenceTexts),
                    confidence
            );
        }
    }
}
