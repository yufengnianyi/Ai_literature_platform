package com.example.demo_01.ai.entitylibrary.service;

import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryEntryView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryRow;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EvidenceItem;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewCandidateView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewDecisionRequest;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewStatus;
import com.example.demo_01.ai.entitylibrary.repository.EntityLibraryRepository;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class EntityReviewService {

    @Resource
    private EntityLibraryRepository entityLibraryRepository;

    public List<ReviewCandidateView> listCandidates(String status) {
        ReviewStatus reviewStatus = parseStatus(status, true);
        return entityLibraryRepository.listCandidates(reviewStatus);
    }

    @Transactional
    public ReviewCandidateView decide(UUID candidateId, ReviewDecisionRequest request) {
        if (candidateId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "candidateId is required");
        }
        ReviewCandidateView candidate = entityLibraryRepository.findCandidate(candidateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Candidate not found: " + candidateId));

        if (!ReviewStatus.PENDING.name().equals(candidate.reviewStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR,
                    "Candidate already reviewed: " + candidate.reviewStatus());
        }

        ReviewStatus decision = parseDecision(request == null ? null : request.decision());
        String reviewNote = request == null ? null : normalize(request.reviewNote());

        if (decision == ReviewStatus.REJECTED) {
            entityLibraryRepository.updateCandidateDecision(
                    candidateId, ReviewStatus.REJECTED, blankToNull(reviewNote), null);
            return entityLibraryRepository.findCandidate(candidateId).orElse(candidate);
        }

        UUID entityId = mergeIntoLibrary(candidate);
        entityLibraryRepository.updateCandidateDecision(
                candidateId, ReviewStatus.APPROVED, blankToNull(reviewNote), entityId);
        return entityLibraryRepository.findCandidate(candidateId).orElse(candidate);
    }

    public List<EntityLibraryEntryView> listEntities(String type, String query) {
        return entityLibraryRepository.listEntities(type, query, false);
    }

    public EntityLibraryEntryView getEntity(UUID entityId) {
        if (entityId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "entityId is required");
        }
        return entityLibraryRepository.findEntity(entityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Entity not found: " + entityId));
    }

    private UUID mergeIntoLibrary(ReviewCandidateView candidate) {
        String entityType = normalizeType(candidate.entityType());
        String normalizedKey = normalize(candidate.normalizedKey());
        if (entityType.isBlank() || normalizedKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "entityType and normalizedKey are required for approval");
        }

        Optional<EntityLibraryRow> locked = entityLibraryRepository.lockByKey(entityType, normalizedKey);
        UUID entityId;
        if (locked.isEmpty()) {
            UUID newId = UUID.randomUUID();
            entityLibraryRepository.insertEntity(
                    newId,
                    entityType,
                    normalizedKey,
                    firstNonBlank(candidate.canonicalName(), candidate.mentionText()),
                    candidate.aliases(),
                    1);
            // Another concurrent insert may have won via UNIQUE; resolve the actual row.
            EntityLibraryRow resolved = entityLibraryRepository.findByKey(entityType, normalizedKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.OPERATION_ERROR,
                            "Failed to persist entity: " + entityType + "/" + normalizedKey));
            entityId = resolved.entityId();
            if (!entityId.equals(newId)) {
                entityLibraryRepository.updateEntityOnMerge(
                        entityId, union(resolved.aliases(), candidate.aliases()));
            }
        } else {
            entityId = locked.get().entityId();
            entityLibraryRepository.updateEntityOnMerge(
                    entityId, union(locked.get().aliases(), candidate.aliases()));
        }

        List<EvidenceItem> evidenceItems = candidate.evidence() == null ? List.of() : candidate.evidence();
        if (evidenceItems.isEmpty() && candidate.reason() != null && !candidate.reason().isBlank()) {
            evidenceItems = List.of(new EvidenceItem(null, candidate.reason()));
        }
        for (EvidenceItem item : evidenceItems) {
            if (item == null || item.evidenceText() == null || item.evidenceText().isBlank()) {
                continue;
            }
            String evidenceText = item.evidenceText().trim();
            entityLibraryRepository.insertEvidenceIfAbsent(
                    entityId,
                    blankToNull(candidate.reason()),
                    evidenceText,
                    candidate.confidence(),
                    candidate.sourceDocumentId(),
                    blankToNull(candidate.sourceTitle()),
                    quoteHash(normalizeQuote(evidenceText))
            );
        }
        return entityId;
    }

    private ReviewStatus parseDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "decision is required");
        }
        String normalized = decision.trim().toUpperCase(Locale.ROOT);
        if ("APPROVED".equals(normalized) || "APPROVE".equals(normalized)) {
            return ReviewStatus.APPROVED;
        }
        if ("REJECTED".equals(normalized) || "REJECT".equals(normalized)) {
            return ReviewStatus.REJECTED;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR,
                "decision must be APPROVED or REJECTED");
    }

    private ReviewStatus parseStatus(String status, boolean allowNull) {
        if (status == null || status.isBlank()) {
            if (allowNull) {
                return null;
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "status is required");
        }
        try {
            return ReviewStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "status must be PENDING, APPROVED, or REJECTED");
        }
    }

    private List<String> union(List<String> left, List<String> right) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (left != null) {
            left.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(set::add);
        }
        if (right != null) {
            right.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(set::add);
        }
        return List.copyOf(set);
    }

    private String quoteHash(String normalizedQuote) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedQuote.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String normalizeQuote(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeType(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "OTHER" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
