package com.example.demo_01.ai.kg.model;

import java.util.List;
import java.util.UUID;

public final class KgModels {

    private KgModels() {
    }

    public enum KgExtractionStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public enum GraphBuilderSyncStatus {
        PENDING,
        SYNCED,
        FAILED,
        SKIPPED
    }

    public enum EntityType {
        PAPER,
        SPECIES,
        GENE_OR_PROTEIN,
        RLK_FAMILY,
        DOMAIN_OR_MOTIF,
        TRAIT_OR_PHENOTYPE,
        STRESS_OR_PATHOGEN,
        METHOD,
        DATABASE_OR_DATASET
    }

    public enum RelationType {
        BELONGS_TO_FAMILY,
        HAS_DOMAIN,
        ORTHOLOG_OF,
        PARALOG_OF,
        DUPLICATED_BY,
        INVOLVED_IN_PROCESS,
        ASSOCIATED_WITH_TRAIT,
        RESPONDS_TO,
        MENTIONED_IN,
        SUPPORTED_BY
    }

    public record ChunkEntityExtraction(
            UUID documentId,
            String chunkId,
            String mentionText,
            String canonicalName,
            EntityType entityType,
            String normalizedKey,
            List<String> aliases,
            String evidenceText,
            double confidence
    ) {
    }

    public record ChunkRelationExtraction(
            UUID documentId,
            String chunkId,
            String headNormalizedKey,
            RelationType relationType,
            String tailNormalizedKey,
            String evidenceText,
            double confidence
    ) {
    }

    public record PassagePayload(
            String chunkId,
            int chunkIndex,
            String sectionPath,
            String text
    ) {
    }

    public record EntityPayload(
            String normalizedKey,
            String canonicalName,
            EntityType entityType,
            List<String> aliases,
            List<String> chunkIds,
            List<String> evidenceTexts,
            double confidence
    ) {
    }

    public record RelationPayload(
            String headNormalizedKey,
            RelationType relationType,
            String tailNormalizedKey,
            List<String> chunkIds,
            List<String> evidenceTexts,
            double confidence
    ) {
    }

    public record PaperGraphPayload(
            UUID documentId,
            String canonicalKey,
            String doi,
            String title,
            Integer publicationYear,
            List<PassagePayload> passages,
            List<EntityPayload> entities,
            List<RelationPayload> relations,
            String schemaVersion
    ) {
    }

    public record GraphBuilderSyncResult(
            GraphBuilderSyncStatus status,
            String requestBody,
            String responseBody,
            String errorMessage
    ) {
    }

    public record KgExtractionJobView(
            UUID jobId,
            UUID documentId,
            KgExtractionStatus status,
            String errorCode,
            String errorMessage,
            Integer entityCount,
            Integer relationCount,
            String payloadPath,
            GraphBuilderSyncStatus graphBuilderStatus
    ) {
    }
}
