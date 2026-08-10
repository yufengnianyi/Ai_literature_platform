package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NormalizedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ClassificationStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedAnchor;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.evidence.service.CompoundNormalizationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MultiProfileEvidencePersistenceService {

    @Resource
    private MultiProfileEvidenceRepository multiProfileRepository;

    @Resource
    private EvidenceRepository evidenceRepository;

    @Resource
    private CompoundNormalizationService normalizationService;

    @Transactional
    public void replaceEvidence(UUID batchId,
                                UUID documentId,
                                String questionId,
                                ClassificationStatus classificationStatus,
                                List<ValidatedEvidenceRow> rows,
                                String sourceHash,
                                String promptHash,
                                String modelName) {
        replaceEvidence(batchId, null, documentId, questionId, classificationStatus,
                rows, sourceHash, promptHash, modelName);
    }

    @Transactional
    public void replaceEvidence(UUID batchId,
                                UUID extractionRunId,
                                UUID documentId,
                                String questionId,
                                ClassificationStatus classificationStatus,
                                List<ValidatedEvidenceRow> rows,
                                String sourceHash,
                                String promptHash,
                                String modelName) {
        multiProfileRepository.replaceEvidence(
                batchId, extractionRunId, documentId, questionId,
                MultiProfileEvidenceModels.PROFILE_VERSION, classificationStatus, rows);
        if (!"Q1".equals(questionId)) {
            return;
        }

        UUID runId = evidenceRepository.insertRun(
                null, documentId, sourceHash, promptHash, modelName);
        evidenceRepository.markRunRunning(runId);
        List<CompoundEvidenceRow> compoundRows = rows.stream()
                .map(row -> CompoundEvidenceRow.fromCells(row.cells()))
                .toList();
        List<NormalizedEvidenceRow> normalized =
                normalizationService.normalize(documentId, compoundRows);
        List<UUID> evidenceIds = rows.stream().map(ValidatedEvidenceRow::recordId).toList();
        List<List<ValidatedAnchor>> anchors =
                rows.stream().map(ValidatedEvidenceRow::anchors).toList();
        evidenceRepository.replaceDocumentEvidence(
                runId, documentId, normalized, evidenceIds, anchors);
        evidenceRepository.completeRun(
                runId,
                rows.isEmpty()
                        ? com.example.demo_01.ai.evidence.model.EvidenceModels.ExtractionStatus.NO_EVIDENCE
                        : com.example.demo_01.ai.evidence.model.EvidenceModels.ExtractionStatus.COMPLETED,
                rows.size(),
                null);
    }
}
