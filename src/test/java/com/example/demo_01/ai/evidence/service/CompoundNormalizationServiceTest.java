package com.example.demo_01.ai.evidence.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompoundNormalizationServiceTest {

    private final CompoundNormalizationService service =
            new CompoundNormalizationService(new MarkdownEvidenceTableParser());

    @Test
    void dedupKeyStaysWithinDatabaseLimitForLongExtractKeys() {
        UUID documentId = UUID.randomUUID();
        String longDescription = "A".repeat(400);
        CompoundEvidenceRow row = new CompoundEvidenceRow(
                "plant extract",
                null,
                "extract",
                "plant source",
                longDescription,
                "Phytophthora infestans",
                null, null, null, null, null, null, null, null, null, null);

        String dedupKey = service.dedupKey(documentId, row, NameKind.NATURAL_EXTRACT);

        assertTrue(dedupKey.length() <= 256);
        assertTrue(dedupKey.startsWith("extract:"));
    }

    @Test
    void dedupKeyPreservesShortKeys() {
        UUID documentId = UUID.randomUUID();
        CompoundEvidenceRow row = new CompoundEvidenceRow(
                "fluazinam",
                "fluazinam",
                null,
                null,
                null,
                "Phytophthora infestans",
                null, null, null, null, null, null, null, null, null, null);

        assertEquals(
                "compound:fluazinam",
                service.dedupKey(documentId, row, NameKind.PURE_COMPOUND));
    }
}
