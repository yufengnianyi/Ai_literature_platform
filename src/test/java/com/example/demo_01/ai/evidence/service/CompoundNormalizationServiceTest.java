package com.example.demo_01.ai.evidence.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NormalizedEvidenceRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompoundNormalizationServiceTest {

    private final CompoundNormalizationService service =
            new CompoundNormalizationService(new MarkdownEvidenceTableParser());

    @Test
    void shouldClassifyLocalLabelWithoutStandardName() {
        CompoundEvidenceRow row = row("compound 1a", "", "化学合成", "", "");
        NormalizedEvidenceRow normalized = service.normalizeRow(UUID.randomUUID(), row);

        assertEquals(NameKind.LOCAL_LABEL, normalized.nameKind());
        assertTrue(normalized.dedupKey().startsWith("local:"));
    }

    @Test
    void shouldClassifyNaturalExtractFromPlantSource() {
        CompoundEvidenceRow row = row(
                "aqueous extract of Artemisia verlotorum",
                "",
                "植物提取物（水提物）",
                "植物天然产物",
                "Artemisia verlotorum, aerial parts");
        NormalizedEvidenceRow normalized = service.normalizeRow(UUID.randomUUID(), row);

        assertEquals(NameKind.NATURAL_EXTRACT, normalized.nameKind());
        assertTrue(normalized.dedupKey().startsWith("extract:"));
    }

    @Test
    void shouldClassifyPureCompoundWithStandardName() {
        CompoundEvidenceRow row = row(
                "eugenol",
                "4-allyl-2-methoxyphenol",
                "苯丙素类",
                "植物天然产物",
                "丁香，花蕾");
        NormalizedEvidenceRow normalized = service.normalizeRow(UUID.randomUUID(), row);

        assertEquals(NameKind.PURE_COMPOUND, normalized.nameKind());
        assertEquals("compound:4-allyl-2-methoxyphenol", normalized.dedupKey());
    }

    @Test
    void shouldNotMergeDifferentLocalLabelsAcrossDocuments() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        CompoundEvidenceRow row = row("compound 7", "", "化学合成", "", "");

        String keyA = service.normalizeRow(docA, row).dedupKey();
        String keyB = service.normalizeRow(docB, row).dedupKey();

        assertTrue(!keyA.equals(keyB));
    }

    private CompoundEvidenceRow row(String original,
                                    String standard,
                                    String structureType,
                                    String sourceCategory,
                                    String sourceDescription) {
        List<String> cells = new java.util.ArrayList<>(java.util.Collections.nCopies(16, ""));
        cells.set(0, original);
        cells.set(1, standard);
        cells.set(2, structureType);
        cells.set(3, sourceCategory);
        cells.set(4, sourceDescription);
        return CompoundEvidenceRow.fromCells(cells);
    }
}
