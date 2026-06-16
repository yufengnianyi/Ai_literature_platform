package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ValidationStatus;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportAggregationServiceTest {

    private final ReportAggregationService service = new ReportAggregationService();

    @Test
    void shouldAggregateDistinctCompoundsByDedupKey() {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        List<RankedEvidence> evidence = List.of(
                ranked(documentA, "eugenol", "4-allyl-2-methoxyphenol", "compound:eugenol"),
                ranked(documentB, "eugenol", "4-allyl-2-methoxyphenol", "compound:eugenol"),
                ranked(documentA, "compound 1a", "", "local:" + documentA));

        var result = service.aggregate(evidence);

        assertEquals(3, result.overview().evidenceRowCount());
        assertEquals(2, result.overview().distinctCompoundCount());
        assertEquals(2, result.overview().documentCount());
        assertEquals(1, result.overview().localLabelCount());
        var eugenol = result.compounds().stream()
                .filter(compound -> "compound:eugenol".equals(compound.dedupKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, eugenol.documentCount());
        assertEquals(2, eugenol.evidenceRowCount());
    }

    @Test
    void shouldComposeDeterministicMarkdownWithoutReviewLanguage() {
        ReportCompositionService compositionService = new ReportCompositionService();
        var aggregation = service.aggregate(List.of(
                ranked(UUID.randomUUID(), "Metalaxyl", "Metalaxyl", "compound:metalaxyl")));

        String markdown = compositionService.compose("抑菌化合物有哪些？", aggregation);

        assertTrue(markdown.contains("## 范围与数据概览"));
        assertTrue(markdown.contains("## 活性概览"));
        assertTrue(markdown.contains("Metalaxyl"));
        assertTrue(!markdown.contains("待审核"));
    }

    private RankedEvidence ranked(UUID documentId,
                                  String original,
                                  String standard,
                                  String dedupKey) {
        UUID evidenceId = UUID.randomUUID();
        NameKind nameKind = dedupKey.startsWith("local:")
                ? NameKind.LOCAL_LABEL
                : NameKind.PURE_COMPOUND;
        CompoundEvidenceRecord record = new CompoundEvidenceRecord(
                evidenceId,
                UUID.randomUUID(),
                documentId,
                "Paper " + documentId,
                1,
                new CompoundEvidenceRow(
                        original, standard, "small molecule", "化学合成", "",
                        "Phytophthora infestans", "MIC", "8 ug/mL",
                        "", "抑制核酸合成", "", "", "", "", "", ""),
                "fingerprint-" + evidenceId,
                nameKind,
                dedupKey,
                null,
                ValidationStatus.VALID,
                List.of(),
                ReviewStatus.PENDING,
                null,
                true,
                List.of(),
                Instant.now(),
                Instant.now());
        return new RankedEvidence(record, 1.0, 1, null);
    }
}
