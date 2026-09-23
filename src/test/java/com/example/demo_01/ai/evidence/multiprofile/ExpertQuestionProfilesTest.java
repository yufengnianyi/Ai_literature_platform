package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import static org.assertj.core.api.Assertions.*;

class ExpertQuestionProfilesTest {
    private final EvidenceProfileRegistry registry = new EvidenceProfileRegistry();
    private final EvidenceMarkdownTableParser parser = new EvidenceMarkdownTableParser(new MultiProfileOutputValidator());

    @Test
    void expertFieldsFollowEverySourceFieldInOrderAndKeepCommonEvidence() throws Exception {
        var source = new ObjectMapper().readTree(getClass().getResourceAsStream(ExpertQuestionDefinitions.RESOURCE));
        int[] counts = {17, 18, 18, 18, 19, 21, 15, 35};
        assertThat(registry.all(EXPERT_PROFILE_VERSION)).hasSize(8);
        for (int i = 0; i < 8; i++) {
            var profile = registry.require(EXPERT_PROFILE_VERSION, "Q" + (i + 1));
            List<String> expected = new ArrayList<>();
            source.path("questions").get(i).path("fields").forEach(f -> expected.add(f.path("field_key").asText()));
            assertThat(expected).hasSize(counts[i]);
            assertThat(profile.fieldKeys().subList(0, counts[i])).containsExactlyElementsOf(expected);
            assertThat(profile.headers()).hasSize(counts[i] + 15);
            assertThat(profile.fieldKeys()).contains("common.evidence_level", "common.evidence_text", "common.provenance")
                    .doesNotContain("common.record_id", "common.paper_id");
            assertThat(PromptResources.load(PromptCatalog.evidenceQuestionExtractionSystem(EXPERT_PROFILE_VERSION, profile.questionId())))
                    .contains(profile.questionId());
        }
    }

    @Test
    void identicalNumbersRetainDifferentMeaningsWithoutMutatingLegacyRegistry() {
        assertThat(registry.all(PROFILE_VERSION)).hasSize(10);
        assertThat(registry.require(PROFILE_VERSION, "Q1").legacyCompound()).isTrue();
        assertThat(registry.require(EXPERT_PROFILE_VERSION, "Q1").compound()).isFalse();
        assertThat(registry.require(EXPERT_PROFILE_VERSION, "Q5").compound()).isTrue();
        assertThat(registry.require(PROFILE_VERSION, "Q5").compound()).isFalse();
        assertThat(registry.require("Q1").headers()).hasSize(16);
        assertThat(registry.resolveVersion(null)).isEqualTo(EXPERT_PROFILE_VERSION);
        assertThatThrownBy(() -> registry.require(EXPERT_PROFILE_VERSION, "Q9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.all("unrecognized")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromptCatalog.evidenceQuestionExtractionSystem("bad", "Q1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void geneAndCompoundRowsRoundTripWithTheirOwnHeaders() {
        for (String question : List.of("Q1", "Q5")) {
            var profile = registry.require(EXPERT_PROFILE_VERSION, question);
            List<String> cells = new ArrayList<>(Collections.nCopies(profile.headers().size(), ""));
            cells.set(0, question.equals("Q1") ? "PsGENE" : "Compound A");
            cells.set(profile.fieldKeys().indexOf("common.evidence_level"), "间接证据");
            var rows = parser.parse(markdown(profile.headers(), cells), profile);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().cells()).containsExactlyElementsOf(cells);
            assertThat(rows.getFirst().validationStatus()).isEqualTo(ValidationStatus.UNVERIFIED);
        }
        var legacy = registry.require(PROFILE_VERSION, "Q1");
        assertThatThrownBy(() -> parser.parse(markdown(legacy.headers(), Collections.nCopies(16, "x")),
                registry.require(EXPERT_PROFILE_VERSION, "Q1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsPositionalCellsToVersionedKeyedPayload() {
        var profile = registry.require(EXPERT_PROFILE_VERSION, "Q1");
        List<String> cells = new ArrayList<>(Collections.nCopies(profile.headers().size(), ""));
        cells.set(profile.fieldKeys().indexOf("gene_protein_name"), "PsCRN63");
        cells.set(profile.fieldKeys().indexOf("common.evidence_level"), "直接验证");

        Map<String, String> payload = registry.toPayload(EXPERT_PROFILE_VERSION, "Q1", cells);

        assertThat(payload).hasSize(profile.fieldKeys().size())
                .containsEntry("gene_protein_name", "PsCRN63")
                .containsEntry("common.evidence_level", "直接验证");
        assertThatThrownBy(() -> registry.toPayload(EXPERT_PROFILE_VERSION, "Q1", List.of("short")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cell count");
    }

    @Test
    void q8RequiresOneKnownRecordTypeButAllowsInapplicableModelFieldsToStayEmpty() {
        var profile = registry.require(EXPERT_PROFILE_VERSION, "Q8");
        List<String> cells = new ArrayList<>(Collections.nCopies(profile.headers().size(), ""));
        cells.set(0, "分子诊断");
        cells.set(1, "病害A");
        assertThat(parser.parse(markdown(profile.headers(), cells), profile)).hasSize(1);
        cells.set(0, "Q8A");
        assertThatThrownBy(() -> parser.parse(markdown(profile.headers(), cells), profile))
                .hasMessageContaining("record_type");
        assertThatThrownBy(() -> parser.parse(markdown(profile.headers(), List.of("分子诊断")), profile))
                .hasMessageContaining("exactly");
    }

    private String markdown(List<String> headers, List<String> cells) {
        return "| " + String.join(" | ", headers) + " |\n| "
                + String.join(" | ", Collections.nCopies(headers.size(), "---")) + " |\n| "
                + String.join(" | ", cells) + " |";
    }
}
