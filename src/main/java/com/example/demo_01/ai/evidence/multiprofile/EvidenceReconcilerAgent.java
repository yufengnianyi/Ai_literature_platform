package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryRow;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EvidenceItem;
import com.example.demo_01.ai.entitylibrary.repository.EntityLibraryRepository;
import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedAnchor;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Links extracted mentions onto the curated entity library and optionally enqueues unknowns.
 * Extends the Q1-only CompoundNormalizationService idea to all profiles via entity_library.
 */
@Slf4j
@Service
public class EvidenceReconcilerAgent {

    @Resource
    private MultiProfileOutputValidator outputValidator;

    @Resource
    private EntityLibraryRepository entityLibraryRepository;

    @Resource
    private EvidenceProperties properties;

    public List<ValidatedEvidenceRow> reconcile(SourceDocument document,
                                                EvidenceProfile profile,
                                                List<ValidatedEvidenceRow> rows) {
        if (!properties.getAgents().getReconciler().isEntityLinkingEnabled()
                || rows == null || rows.isEmpty()) {
            return rows == null ? List.of() : List.copyOf(rows);
        }

        List<ValidatedEvidenceRow> linked = new ArrayList<>();
        for (ValidatedEvidenceRow row : rows) {
            linked.add(linkRow(document, profile, row));
        }
        return List.copyOf(linked);
    }

    private ValidatedEvidenceRow linkRow(SourceDocument document,
                                         EvidenceProfile profile,
                                         ValidatedEvidenceRow row) {
        List<String> cells = new ArrayList<>(row.cells());
        boolean changed = false;
        for (Integer index : profile.primaryFieldIndexes()) {
            if (index == null || index < 0 || index >= cells.size()) {
                continue;
            }
            String mention = cells.get(index);
            if (mention == null || mention.isBlank()) {
                continue;
            }
            String entityType = entityTypeFor(profile.questionId(), index);
            String normalizedKey = normalizeKey(mention);
            Optional<EntityLibraryRow> match =
                    entityLibraryRepository.findByKey(entityType, normalizedKey);
            if (match.isPresent()) {
                String canonical = match.get().canonicalName();
                if (canonical != null && !canonical.isBlank() && !canonical.equals(mention)) {
                    cells.set(index, canonical);
                    changed = true;
                }
            } else if (properties.getAgents().getReconciler().isEnqueueUnknownAsCandidates()) {
                enqueueCandidate(document, profile, row, entityType, mention, normalizedKey);
            }
        }
        if (!changed) {
            return row;
        }
        String fingerprint = outputValidator.fingerprint(profile.questionId(), cells);
        return new ValidatedEvidenceRow(
                row.recordId(), List.copyOf(cells), fingerprint, row.anchors(),
                row.validationStatus(), row.verificationNote());
    }

    private void enqueueCandidate(SourceDocument document,
                                  EvidenceProfile profile,
                                  ValidatedEvidenceRow row,
                                  String entityType,
                                  String mention,
                                  String normalizedKey) {
        try {
            List<EvidenceItem> evidence = new ArrayList<>();
            for (ValidatedAnchor anchor : row.anchors()) {
                evidence.add(new EvidenceItem(anchor.chunkId(), anchor.exactQuote()));
            }
            if (evidence.isEmpty()) {
                evidence = List.of(new EvidenceItem(null, mention));
            }
            Optional<EntityLibraryRow> matched =
                    entityLibraryRepository.findByKey(entityType, normalizedKey);
            entityLibraryRepository.insertCandidate(
                    UUID.randomUUID(),
                    entityType,
                    mention,
                    mention.trim(),
                    normalizedKey,
                    List.of(),
                    "Auto-enqueued from evidence profile " + profile.questionId(),
                    evidence,
                    0.5,
                    document.documentId(),
                    document.title(),
                    matched.map(EntityLibraryRow::entityId).orElse(null));
        } catch (Exception e) {
            log.debug("Skip entity candidate enqueue for {}: {}", mention, e.getMessage());
        }
    }

    private String entityTypeFor(String questionId, int primaryIndex) {
        return switch (questionId) {
            case "Q1" -> primaryIndex == 0 ? "COMPOUND" : primaryIndex == 5 ? "PATHOGEN" : "ASSAY";
            case "Q4" -> primaryIndex == 0 ? "FUNGICIDE" : "PATHOGEN";
            case "Q7" -> primaryIndex == 1 ? "BIOCONTROL" : primaryIndex == 3 ? "PATHOGEN" : "CONTROL_TYPE";
            case "Q2" -> "EFFECTOR";
            case "Q3" -> primaryIndex == 1 ? "RESISTANCE_GENE" : primaryIndex == 3 ? "PLANT" : "RESISTANCE_TYPE";
            case "Q5", "Q6", "Q8", "Q9", "Q10" -> "OOMYCETE_ENTITY";
            default -> "OTHER";
        };
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
}
