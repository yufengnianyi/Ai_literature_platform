package com.example.demo_01.ai.evidence.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EvidenceModels {

    public static final String PROFILE_ID = "antimicrobial_compound_v1";
    public static final List<String> HEADERS = List.of(
            "化合物原文名称", "化合物标准名称", "结构类型", "来源类别", "来源具体描述",
            "测试卵菌拉丁名", "实验方法", "活性数据", "阳性对照", "作用靶标/机制",
            "靶标验证方法", "细胞毒性", "抗性/交叉抗性", "协同增效", "参考文献", "专利信息"
    );

    private EvidenceModels() {
    }

    public enum ExtractionStatus {
        QUEUED, RUNNING, COMPLETED, NO_EVIDENCE, FAILED
    }

    public enum ValidationStatus {
        VALID, INVALID
    }

    public enum ReviewStatus {
        PENDING, APPROVED, REJECTED
    }

    public enum NameKind {
        PURE_COMPOUND, NATURAL_EXTRACT, LOCAL_LABEL
    }

    public record CompoundEvidenceRow(
            String compoundOriginalName,
            String compoundStandardName,
            String structureType,
            String sourceCategory,
            String sourceDescription,
            String oomyceteScientificName,
            String assayMethod,
            String activityData,
            String positiveControl,
            String targetOrMechanism,
            String targetValidationMethod,
            String cytotoxicity,
            String resistanceCrossResistance,
            String synergy,
            String referenceText,
            String patentInformation
    ) {
        public static CompoundEvidenceRow fromCells(List<String> cells) {
            if (cells == null || cells.size() != HEADERS.size()) {
                throw new IllegalArgumentException("Evidence row must contain exactly 16 cells");
            }
            return new CompoundEvidenceRow(
                    cells.get(0), cells.get(1), cells.get(2), cells.get(3),
                    cells.get(4), cells.get(5), cells.get(6), cells.get(7),
                    cells.get(8), cells.get(9), cells.get(10), cells.get(11),
                    cells.get(12), cells.get(13), cells.get(14), cells.get(15)
            );
        }

        public List<String> cells() {
            return List.of(
                    value(compoundOriginalName), value(compoundStandardName), value(structureType),
                    value(sourceCategory), value(sourceDescription), value(oomyceteScientificName),
                    value(assayMethod), value(activityData), value(positiveControl),
                    value(targetOrMechanism), value(targetValidationMethod), value(cytotoxicity),
                    value(resistanceCrossResistance), value(synergy), value(referenceText),
                    value(patentInformation)
            );
        }

        private static String value(String value) {
            return value == null ? "" : value;
        }
    }

    public record EvidenceChunk(
            String chunkId,
            String sectionPath,
            Integer paragraphIndex,
            Integer sentenceStart,
            Integer sentenceEnd,
            String text,
            String contentType,
            String sourceTei
    ) {
        /**
         * Backward-compatible constructor for chunks that do not carry content-type / TEI
         * provenance (e.g. synthetic or test chunks). Prefer the canonical constructor when the
         * source metadata is available so on-demand table loading can locate the TEI.
         */
        public EvidenceChunk(String chunkId, String sectionPath, Integer paragraphIndex,
                             Integer sentenceStart, Integer sentenceEnd, String text) {
            this(chunkId, sectionPath, paragraphIndex, sentenceStart, sentenceEnd, text, null, null);
        }
    }

    public record CompoundEvidenceRecord(
            UUID evidenceId,
            UUID runId,
            UUID documentId,
            String documentTitle,
            int rowIndex,
            CompoundEvidenceRow row,
            String rowFingerprint,
            NameKind nameKind,
            String dedupKey,
            Double modelConfidence,
            ValidationStatus validationStatus,
            List<String> validationWarnings,
            ReviewStatus reviewStatus,
            String reviewNote,
            boolean current,
            List<EvidenceAnchorRecord> anchors,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record NormalizedEvidenceRow(
            CompoundEvidenceRow row,
            String rowFingerprint,
            NameKind nameKind,
            String dedupKey
    ) {
    }

    public record EvidenceAnchorRecord(
            Long anchorId,
            UUID evidenceId,
            String chunkId,
            String sectionPath,
            Integer paragraphIndex,
            Integer sentenceStart,
            Integer sentenceEnd,
            Integer pageStart,
            Integer pageEnd,
            String exactQuote
    ) {
    }

}
