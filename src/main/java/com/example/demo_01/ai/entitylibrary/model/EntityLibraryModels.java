package com.example.demo_01.ai.entitylibrary.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EntityLibraryModels {

    private EntityLibraryModels() {
    }

    public enum ReviewStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public record ExtractRequest(
            List<UUID> documentIds,
            String question
    ) {
    }

    public record ExtractResponse(
            int documentCount,
            int candidateCount,
            List<UUID> candidateIds
    ) {
    }

    public record ReviewDecisionRequest(
            String decision,
            String reviewNote
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceItem(
            String chunkId,
            String evidenceText
    ) {
    }

    public record ReviewCandidateView(
            UUID candidateId,
            String entityType,
            String mentionText,
            String canonicalName,
            String normalizedKey,
            List<String> aliases,
            String reason,
            List<EvidenceItem> evidence,
            double confidence,
            UUID sourceDocumentId,
            String sourceTitle,
            String reviewStatus,
            String reviewNote,
            Instant reviewedAt,
            UUID matchedEntityId,
            Instant createdAt
    ) {
    }

    public record EntityEvidenceView(
            long evidenceId,
            String reason,
            String evidenceText,
            double confidence,
            UUID sourceDocumentId,
            String sourceTitle,
            Instant createdAt
    ) {
    }

    public record EntityLibraryEntryView(
            UUID entityId,
            String entityType,
            String normalizedKey,
            String canonicalName,
            List<String> aliases,
            String definition,
            String status,
            int sourceCount,
            Instant createdAt,
            Instant updatedAt,
            List<EntityEvidenceView> evidence
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityLibraryRow(
            UUID entityId,
            String entityType,
            String normalizedKey,
            String canonicalName,
            List<String> aliases,
            String definition,
            String status,
            int sourceCount
    ) {
    }
}
