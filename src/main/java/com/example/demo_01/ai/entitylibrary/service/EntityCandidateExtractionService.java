package com.example.demo_01.ai.entitylibrary.service;

import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EvidenceItem;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ExtractResponse;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryRow;
import com.example.demo_01.ai.entitylibrary.repository.EntityLibraryRepository;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntity;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityExtraction;
import com.example.demo_01.ai.rag.entity.service.RagDocumentEntityExtractionService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EntityCandidateExtractionService {

    @Resource
    private RagDocumentEntityExtractionService ragDocumentEntityExtractionService;

    @Resource
    private EntityLibraryRepository entityLibraryRepository;

    @Transactional
    public ExtractResponse extract(List<UUID> documentIds, String question) {
        List<UUID> safeIds = documentIds == null ? List.of() : documentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (safeIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "documentIds is required");
        }

        List<RagDocumentEntityExtraction> extractions =
                ragDocumentEntityExtractionService.extractBatch(safeIds, question);

        List<UUID> candidateIds = new ArrayList<>();
        for (RagDocumentEntityExtraction extraction : extractions) {
            if (extraction == null || extraction.entities() == null) {
                continue;
            }
            for (RagDocumentEntity entity : extraction.entities()) {
                if (entity == null) {
                    continue;
                }
                String canonicalName = firstNonBlank(entity.canonicalName(), entity.mentionText());
                if (canonicalName.isBlank()) {
                    continue;
                }
                String entityType = normalizeType(entity.entityType());
                String normalizedKey = normalizeKey(canonicalName);
                if (normalizedKey.isBlank()) {
                    continue;
                }

                List<EvidenceItem> evidence = buildEvidence(entity);
                String reason = evidence.stream()
                        .map(EvidenceItem::evidenceText)
                        .filter(text -> text != null && !text.isBlank())
                        .collect(Collectors.joining(" | "));

                Optional<EntityLibraryRow> matched =
                        entityLibraryRepository.findByKey(entityType, normalizedKey);
                UUID candidateId = UUID.randomUUID();
                entityLibraryRepository.insertCandidate(
                        candidateId,
                        entityType,
                        normalize(entity.mentionText()),
                        canonicalName.trim(),
                        normalizedKey,
                        distinct(entity.aliases()),
                        reason.isBlank() ? null : reason,
                        evidence,
                        clamp(entity.confidence()),
                        extraction.documentId(),
                        normalize(extraction.documentTitle()),
                        matched.map(EntityLibraryRow::entityId).orElse(null)
                );
                candidateIds.add(candidateId);
            }
        }

        return new ExtractResponse(safeIds.size(), candidateIds.size(), candidateIds);
    }

    private List<EvidenceItem> buildEvidence(RagDocumentEntity entity) {
        List<String> evidenceTexts = entity.evidenceTexts() == null ? List.of() : entity.evidenceTexts();
        List<String> chunkIds = entity.sourceChunkIds() == null ? List.of() : entity.sourceChunkIds();
        List<EvidenceItem> items = new ArrayList<>();
        for (int i = 0; i < evidenceTexts.size(); i++) {
            String text = evidenceTexts.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            String chunkId = i < chunkIds.size() ? chunkIds.get(i) : null;
            items.add(new EvidenceItem(chunkId, text.trim()));
        }
        if (items.isEmpty() && entity.mentionText() != null && !entity.mentionText().isBlank()) {
            items.add(new EvidenceItem(
                    chunkIds.isEmpty() ? null : chunkIds.get(0),
                    entity.mentionText().trim()));
        }
        return items;
    }

    private String normalizeKey(String canonicalName) {
        if (canonicalName == null) {
            return "";
        }
        return canonicalName.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String normalizeType(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "OTHER" : normalized.toUpperCase(Locale.ROOT);
    }

    private List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                set.add(value.trim());
            }
        }
        return List.copyOf(set);
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
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
}
