package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.service.CompoundEvidenceAggregator.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompoundSynthesisV3Test {

    @Test
    void fromSynthesizedRecordsShouldProduceCompoundRows() {
        SynthesizedCompoundRecord record = new SynthesizedCompoundRecord(
                "eugenol", "doc-1", "Eugenol Paper", CompoundRole.SUBJECT,
                "phenylpropanoid", "plant natural product",
                List.of(new ParadigmActivityBlock(
                        "MICRO_WELL_DILUTION",
                        List.of(new DoseResponse("200 μg/mL", "100% inhibition", "48 h")),
                        new KeyMetric("MIC", "200 μg/mL", "lowest concentration required"),
                        true, "fungicidal", "OA plates, 28°C",
                        List.of("P. nicotianae"), "complete inhibition",
                        List.of("chunk-1", "chunk-2"),
                        null, null)),
                "membrane disruption", "not reported",
                List.of(new ComparativeRelation("cinnamaldehyde", "50x more effective", "mycelial growth", null)),
                "test context", List.of("P. nicotianae"), 0.85, "DOI:test",
                List.of("chunk-1", "chunk-2"), List.of());

        List<CompoundActivityRow> rows = CompoundEvidenceAggregator.fromSynthesizedRecords(List.of(record));
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).compoundName().contains("eugenol"));
        assertTrue(rows.get(0).antimicrobialActivity().contains("MIC"));
    }

    @Test
    void doseResponseRowsShouldFlattenGradients() {
        SynthesizedCompoundRecord record = new SynthesizedCompoundRecord(
                "eugenol", "doc-1", "Test Paper", CompoundRole.SUBJECT,
                null, null,
                List.of(new ParadigmActivityBlock(
                        "MYCELIAL_GROWTH_ASSAY",
                        List.of(
                                new DoseResponse("25 μg/mL", "10% inhibition", "48 h"),
                                new DoseResponse("200 μg/mL", "100% inhibition", "48 h")),
                        null, true, null, null, List.of("P. nicotianae"), null, List.of(),
                        null, null)),
                null, null, List.of(), null, List.of(), 0.7, null, List.of(), List.of());

        List<DoseResponseRow> rows = CompoundEvidenceAggregator.doseResponseRows(List.of(record));
        assertEquals(2, rows.size());
        assertEquals("25 μg/mL", rows.get(0).concentration());
        assertEquals("200 μg/mL", rows.get(1).concentration());
    }

    @Test
    void comparativeRelationRowsShouldExtractComparisons() {
        SynthesizedCompoundRecord record = new SynthesizedCompoundRecord(
                "compound 21", "doc-1", "Test", CompoundRole.SUBJECT,
                null, null, List.of(), null, null,
                List.of(new ComparativeRelation("cinnamaldehyde", "50x more effective",
                        "mycelial growth", "21 @ 25 µM ≈ cinnamaldehyde @ 1 mM")),
                null, List.of(), 0.8, null, List.of(), List.of());

        List<ComparativeRelationRow> rows = CompoundEvidenceAggregator.comparativeRelationRows(List.of(record));
        assertEquals(1, rows.size());
        assertEquals("50x more effective", rows.get(0).relation());
        assertEquals("cinnamaldehyde", rows.get(0).referenceCompound());
    }

    @Test
    void legacyMigratorShouldParseMetricAndParadigm() {
        List<String> legacy = List.of(
                "MIC: 200 μg/mL (micro-well dilution, dose-dependent)",
                "EC50 = 50 µM via mycelial growth assay");

        List<AntimicrobialActivityItem> items = LegacyAntimicrobialActivityMigrator.migrate(legacy);
        assertEquals(2, items.size());

        assertNotNull(items.get(0).keyMetric());
        assertEquals("MIC", items.get(0).keyMetric().type());
        assertTrue(items.get(0).doseDependent());

        assertNotNull(items.get(1).keyMetric());
        assertEquals("EC50", items.get(1).keyMetric().type());
    }

    @Test
    void documentKnowledgeContextShouldSupportAliasResolutionMap() {
        DocumentKnowledgeContext ctx = new DocumentKnowledgeContext(
                java.util.UUID.randomUUID(), KnowledgeStatus.HIT,
                List.of(), List.of("eugenol"), List.of("P. nicotianae"),
                List.of(), List.of(), List.of(), List.of(),
                List.of("MIC of eugenol"), List.of(),
                Map.of("compound 1", "eugenol", "1", "eugenol"));

        assertEquals("eugenol", ctx.aliasResolutionMap().get("compound 1"));
        assertEquals("eugenol", ctx.aliasResolutionMap().get("1"));
    }

    @Test
    void documentKnowledgeContextBackwardCompatible() {
        DocumentKnowledgeContext ctx = new DocumentKnowledgeContext(
                java.util.UUID.randomUUID(), KnowledgeStatus.HIT,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        assertNotNull(ctx.aliasResolutionMap());
        assertTrue(ctx.aliasResolutionMap().isEmpty());
    }

    @Test
    void chunkAnchorShouldStoreMetadata() {
        ChunkAnchor anchor = new ChunkAnchor("chunk-42", AnchorType.QUANTITATIVE,
                "KEY_METRIC:MIC; DOSE_GRADIENT(5);", List.of("MIC", "dose-dependent"));
        assertEquals(AnchorType.QUANTITATIVE, anchor.type());
        assertEquals(2, anchor.matchedTokens().size());
    }

    @Test
    void synthesizedCompoundRecordShouldStoreAllFields() {
        SynthesizedCompoundRecord rec = new SynthesizedCompoundRecord(
                "eugenol", "doc-uuid", "Paper Title", CompoundRole.SUBJECT,
                "phenylpropanoid", "plant", List.of(), "membrane disruption",
                "not reported", List.of(), "context note",
                List.of("P. nicotianae"), 0.85, "ref-1",
                List.of("chunk-1"), List.of("MIC_OMITTED"));

        assertEquals(CompoundRole.SUBJECT, rec.role());
        assertEquals(1, rec.coverageWarnings().size());
        assertEquals("MIC_OMITTED", rec.coverageWarnings().get(0));
    }
}
