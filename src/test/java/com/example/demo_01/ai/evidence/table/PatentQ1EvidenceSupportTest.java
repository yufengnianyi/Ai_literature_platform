package com.example.demo_01.ai.evidence.table;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PatentQ1EvidenceSupportTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final TableSerializer serializer = new TableSerializer();
    final PatentQ1EvidenceSupport support = new PatentQ1EvidenceSupport(mapper, serializer);
    final EvidenceProfileRegistry registry = new EvidenceProfileRegistry();

    @Test
    void noTeiLoadsCurrentNativeTablesWithoutSelectorOrOldEmptyCache() throws Exception {
        TableContextService service = new TableContextService();
        EvidenceProperties properties = new EvidenceProperties();
        properties.getTable().setEnabled(true);
        properties.getTable().setEnabledQuestionIds(List.of("Q1"));
        properties.getTable().setMaxTableChars(1);
        properties.getTable().setMaxTables(0);
        TableJsonlStore legacy = mock(TableJsonlStore.class);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "tableJsonlStore", legacy);
        ReflectionTestUtils.setField(service, "patentQ1EvidenceSupport", support);
        ReflectionTestUtils.setField(service, "legendResolver", new TableLegendResolver());
        List<EvidenceChunk> base = List.of(method());
        assertThat(service.augment(null, UUID.randomUUID(), registry.require("Q1"), base, root)).isSameAs(base);
        write(List.of(List.of("Agent A", "0.35", "")));
        var augmented = service.augment(null, UUID.randomUUID(), registry.require("Q1"), base, root);
        assertThat(augmented).hasSize(2);
        assertThat(augmented.getLast().sourceTei()).isEmpty();
        assertThat(augmented.getLast().text()).contains("[patent_page=4]", "[table_ref=T1]", "[source_kind=VISION]",
                "[evidence_role=RESULT]", "| Agent A | 0.35 |  |");
        assertThat(augmented.getFirst().text()).contains("[evidence_role=METHOD]").endsWith(method().text());
        verifyNoInteractions(legacy);
        write(List.of(List.of("Agent B", "0.49", "")));
        assertThat(support.load(registry.require("Q1"), root).orElseThrow().tables().getFirst().rows().getFirst())
                .containsExactly("Agent B", "0.49", "");
    }

    @Test
    void textOnlyAndLegacyOverloadsAreUnchanged() throws Exception {
        write(List.of(List.of("Agent A", "0.35", "")));
        TableContextService service = new TableContextService();
        ReflectionTestUtils.setField(service, "properties", new EvidenceProperties());
        List<EvidenceChunk> text = List.of(method());
        assertThat(service.augment(registry.require("Q1"), text)).isSameAs(text);
        assertThat(support.load(registry.require("Q2"), root)).isEmpty();
    }

    @Test
    void rejectsRatioValueMisassignmentMissingAndExtraRows() throws Exception {
        write(List.of(List.of("Agent A:Agent B (3:1)", "0.35", "131.4"),
                List.of("Agent A:Agent B (1:3)", "0.49", "119.2")));
        var input = support.load(registry.require("Q1"), root).orElseThrow();
        var chunks = support.augment(input, List.of(method()), "doc", new TableLegendResolver());
        List<ValidatedEvidenceRow> swapped = List.of(row("Agent A:Agent B (1:3)", "EC50=0.35 mg/L", "CTC=131.4"),
                row("Agent A:Agent B (3:1)", "EC50=0.49 mg/L", "CTC=119.2"));
        var result = support.assess(input, chunks, swapped);
        assertThat(result.mismatches()).hasSize(2);
        assertThat(result.comparisons()).allSatisfy(c -> {
            assertThat(c.flags().get("ec50Accurate")).isFalse();
            assertThat(c.flags().get("ctcAccurate")).isFalse();
        });
        assertThat(support.assess(input, chunks, List.of()).comparisons())
                .allSatisfy(c -> assertThat(c.flags().get("rowPresent")).isFalse());
        assertThat(support.assess(input, chunks, List.of(row("Unknown", "EC50=9", ""))).extraRecordIds()).hasSize(1);
    }

    @Test
    void preservesNineOriginalRowsAsInvalidForMissingLatinWithExactAnchorsAndAudit() throws Exception {
        var fixture = mapper.readTree(getClass().getResourceAsStream("/patent/CN106857590B-table1-gold.json"));
        List<List<String>> nativeRows = new ArrayList<>();
        List<ValidatedEvidenceRow> output = new ArrayList<>();
        for (var item : fixture.path("rows")) {
            String name = item.path("treatment").asText();
            if (!item.path("ratio").isNull()) name += " (" + item.path("ratio").asText() + ")";
            String ctc = item.path("ctc").isNull() ? "" : item.path("ctc").asText();
            nativeRows.add(List.of(name, item.path("ec50").asText(), ctc));
            output.add(row(name, "EC50=" + item.path("ec50").asText() + " mg/L; 72h; 2d; "
                    + fixture.path("pathogenOriginal").asText(), ctc.isBlank() ? "" : "CTC=" + ctc));
        }
        write(nativeRows);
        var input = support.load(registry.require("Q1"), root).orElseThrow();
        var chunks = support.augment(input, List.of(method()), "doc", new TableLegendResolver());
        assertThat(support.assess(input, chunks, output).mismatches()).isEmpty();
        var guarded = support.guard(input, chunks, output);
        assertThat(guarded).hasSize(9).allSatisfy(row -> {
            assertThat(row.validationStatus()).isEqualTo(ValidationStatus.INVALID);
            assertThat(row.verificationNote()).contains("Missing required tested oomycete Latin name");
            assertThat(row.cells().get(5)).isEmpty();
            assertThat(row.cells().get(8)).isEmpty();
            assertThat(row.anchors()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(anchor ->
                    assertThat(chunks.stream().filter(c -> c.chunkId().equals(anchor.chunkId())).findFirst().orElseThrow().text())
                            .contains(anchor.exactQuote()));
            assertThat(row.anchors()).anySatisfy(anchor -> assertThat(anchor.exactQuote()).isEqualTo(method().text()));
        });
        assertThat(guarded.stream().map(ValidatedEvidenceRow::cells).toList())
                .isEqualTo(output.stream().map(ValidatedEvidenceRow::cells).toList());
        Path audit = support.writeAudit(input, UUID.randomUUID(), chunks, guarded, "post-validation", 0, null);
        var saved = mapper.readTree(audit.toFile());
        assertThat(saved.path("latest").path("coverageAccurate").asBoolean()).isTrue();
        assertThat(saved.path("latest").path("comparisons")).hasSize(9);
        assertThat(saved.path("latest").path("comparisons").get(0).path("recordId").asText())
                .isEqualTo(guarded.getFirst().recordId().toString());
        assertThat(saved.path("latest").path("comparisons").get(0).path("provenance").path("pageNumber").asInt()).isEqualTo(4);
    }

    @Test
    void backgroundClaimsAndExternallyInventedSpeciesCannotBeAccepted() throws Exception {
        write(List.of(List.of("Agent A", "0.35", "")));
        var input = support.load(registry.require("Q1"), root).orElseThrow();
        var output = row("Agent A", "EC50=0.35", "");
        var background = new EvidenceChunk("doc:page:1", "patent/background/claims", 1, null, null,
                "Agent A controls disease and inhibits a target.", "body", "");
        assertThat(support.guard(input, List.of(background), List.of(output)).getFirst().validationStatus())
                .isEqualTo(ValidationStatus.INVALID);
        var cells = new ArrayList<>(output.cells());
        cells.set(5, "Phytophthora infestans");
        cells.set(8, "negative CK water");
        cells.set(9, "inhibits a target");
        output = new ValidatedEvidenceRow(output.recordId(), cells, output.fingerprint(), List.of(), ValidationStatus.VALID, null);
        var chunks = support.augment(input, List.of(method(), background), "doc", new TableLegendResolver());
        var guarded = support.guard(input, chunks, List.of(output)).getFirst();
        assertThat(guarded.validationStatus()).isEqualTo(ValidationStatus.INVALID);
        assertThat(guarded.verificationNote()).contains("Latin name is not explicit", "Positive control", "Unsupported demonstrated field");
    }

    @Test
    void missingTimeConflictAndBackgroundMechanismRequireCorrection() throws Exception {
        write(List.of(List.of("Agent A", "0.35", "")));
        var input = support.load(registry.require("Q1"), root).orElseThrow();
        var background = new EvidenceChunk("background", "patent/background", 1, null, null,
                "inhibits a target", "body", "");
        var chunks = support.augment(input, List.of(method(), background), "doc", new TableLegendResolver());
        assertThat(support.extractionChunks(chunks)).noneSatisfy(chunk ->
                assertThat(chunk.text()).contains("inhibits a target"));
        assertThat(chunks.getLast().text()).contains("derived_source_notice=TIME_CONFLICT", "2d", "72h");
        var original = row("Agent A", "EC50=0.35", "");
        var cells = new ArrayList<>(original.cells());
        cells.set(7, "EC50=0.35 mg/L; 72h; 2d");
        cells.set(9, "inhibits a target");
        var bad = new ValidatedEvidenceRow(original.recordId(), cells, original.fingerprint(), List.of(), ValidationStatus.VALID, null);
        var flags = support.assess(input, chunks, List.of(bad)).comparisons().getFirst().flags();
        assertThat(flags.get("timeConflictExplicit")).isFalse();
        assertThat(flags.get("demonstratedFieldsSupported")).isFalse();
    }

    @Test
    void synergyThresholdIsNotASecondMeasuredCtc() throws Exception {
        write(List.of(List.of("A:B 3:1", "0.35", "131.4")));
        var input = support.load(registry.require("Q1"), root).orElseThrow();
        var chunks = support.augment(input, List.of(method()), "doc", new TableLegendResolver());
        assertThat(support.assess(input, chunks,
                List.of(row("A:B 3:1", "EC50=0.35 mg/L", "CTC=131.4; synergy (CTC\u2265120)"))).mismatches()).isEmpty();
        assertThat(support.assess(input, chunks,
                List.of(row("A:B 3:1", "EC50=0.35 mg/L", "CTC=131.4; CTC=132.8"))).mismatches()).isNotEmpty();
    }

    @Test
    void originalTargetCannotBeLostOrTakenFromBackgroundOnAMixedPage() throws Exception {
        write(List.of(List.of("Agent A", "0.35", "")));
        var input = support.load(registry.require("Q1"), root).orElseThrow();
        var page = new EvidenceChunk("doc:p4", "patent/method/test-definition", 4, null, null,
                "[patent_page=4][evidence_role=BACKGROUND]\nBackground benefit\n"
                        + "[evidence_role=METHOD]\n\u8bd5\u9a8c\u76ee\u6807\u7269\u4e3a\u9a8c\u8bc1\u75c5\u5bb3\u3002 Assay details",
                "body", "");
        var chunks = support.augment(input, List.of(page), "doc", new TableLegendResolver());
        assertThat(chunks).hasSize(3);
        var missing = support.assess(input, chunks, List.of(row("Agent A", "EC50=0.35 mg/L", "")));
        assertThat(missing.comparisons().getFirst().flags().get("originalTargetPreserved")).isFalse();
        var correct = support.assess(input, chunks,
                List.of(row("Agent A", "EC50=0.35 mg/L; \u9a8c\u8bc1\u75c5\u5bb3", "")));
        assertThat(correct.mismatches()).isEmpty();
        assertThat(correct.anchors().values()).allSatisfy(anchors -> assertThat(anchors)
                .noneSatisfy(anchor -> assertThat(anchor.exactQuote()).contains("Background benefit")));
    }

    @Test
    void malformedOrUnverifiedNativeArtifactsFailClosed() throws Exception {
        write(List.of(List.of("Agent A", "0.35", "")));
        var manifest = mapper.readTree(root.resolve("patent-table-manifest.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("tables").get(0)).put("readStatus", "REVIEW_REQUIRED");
        mapper.writeValue(root.resolve("patent-table-manifest.json").toFile(), manifest);
        assertThatThrownBy(() -> support.load(registry.require("Q1"), root)).hasMessageContaining("not VERIFIED");
    }

    private void write(List<List<String>> rows) throws Exception {
        mapper.writeValue(root.resolve("patent-table-manifest.json").toFile(), Map.of(
                "kind", "PATENT_Q1", "publicationNumber", "test-publication", "pdfSha256", "a".repeat(64),
                "complete", true, "tables", List.of(Map.of("tableRef", "T1", "pageNumber", 4,
                        "sourceKind", "VISION", "readStatus", "VERIFIED", "imagePath", "page-4.png",
                        "region", Map.of("x", 0, "y", 0, "width", 100, "height", 100)))));
        var table = new ParsedTable("T1", "1", "Native assay", List.of("Treatment", "72h EC50 (mg/L)", "CTC"),
                rows, List.of(), "stale pre-rendered text must not replace native rows", true, "");
        Files.writeString(root.resolve("tables.jsonl"), mapper.writeValueAsString(table) + "\n");
    }

    private EvidenceChunk method() {
        return new EvidenceChunk("doc:patent-page:4:0", "patent/method/test-definition", 4, 0, 0,
                "Mycelial growth assay: 28 C, 2d, three replicate dishes, 14ml each, 5mm plugs.", "body", "");
    }

    private ValidatedEvidenceRow row(String name, String activity, String synergy) {
        List<String> cells = new ArrayList<>(Collections.nCopies(16, ""));
        cells.set(0, name);
        cells.set(6, "Mycelial growth assay");
        cells.set(7, activity + "; 72h; 2d; source time conflict");
        cells.set(13, synergy);
        return new ValidatedEvidenceRow(UUID.randomUUID(), cells, UUID.randomUUID().toString(), List.of(), ValidationStatus.VALID, null);
    }
}
