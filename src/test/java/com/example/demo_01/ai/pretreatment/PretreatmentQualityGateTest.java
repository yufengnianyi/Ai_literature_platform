package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityDecision;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PretreatmentQualityGateTest {

    private final PretreatmentQualityGate gate = new PretreatmentQualityGate();
    private final PretreatmentProperties.Quality properties = new PretreatmentProperties.Quality();

    @Test
    void rejectsMissingTitle() {
        var result = gate.evaluate(metadata("", "abstract"), goodChunks(), properties);

        assertThat(result.decision()).isEqualTo(QualityDecision.REJECT);
        assertThat(result.rejectReasonCode()).isEqualTo("MISSING_TITLE");
    }

    @Test
    void doesNotRejectMissingAbstract() {
        var result = gate.evaluate(metadata("A title", ""), goodChunks(), properties);

        assertThat(result.decision()).isEqualTo(QualityDecision.PASS);
        assertThat(result.rejectReasonCode()).isBlank();
    }

    @Test
    void rejectsLowChunkCount() {
        var result = gate.evaluate(metadata("A title", "abstract"), List.of(chunk(0, "long enough text")), properties);

        assertThat(result.decision()).isEqualTo(QualityDecision.REJECT);
        assertThat(result.rejectReasonCode()).isEqualTo("LOW_CHUNK_COUNT");
    }

    @Test
    void rejectsLowTextCoverage() {
        var result = gate.evaluate(metadata("A title", "abstract"),
                List.of(chunk(0, "short text"), chunk(1, "short text"), chunk(2, "short text")), properties);

        assertThat(result.decision()).isEqualTo(QualityDecision.REJECT);
        assertThat(result.rejectReasonCode()).isEqualTo("LOW_TEXT_COVERAGE");
    }

    @Test
    void rejectsHighReplacementRatio() {
        String noisy = "????????????????????????????????????????????????????????????????????????????????????????????????????";
        var result = gate.evaluate(metadata("A title", "abstract"),
                List.of(chunk(0, noisy), chunk(1, noisy), chunk(2, noisy.repeat(20))), properties);

        assertThat(result.decision()).isEqualTo(QualityDecision.REJECT);
        assertThat(result.rejectReasonCode()).isEqualTo("HIGH_GARBLED_TEXT_RATIO");
    }

    @Test
    void passesNormalArtifact() {
        var result = gate.evaluate(metadata("A title", "abstract"), goodChunks(), properties);

        assertThat(result.decision()).isEqualTo(QualityDecision.PASS);
        assertThat(result.metrics()).containsEntry("chunkCount", 3);
    }

    private RagDocumentMetadata metadata(String title, String abstractText) {
        return new RagDocumentMetadata(null, null, title, List.of(), List.of(), abstractText, "Journal", null, null);
    }

    private List<RagChunk> goodChunks() {
        String text = "This is a normally extracted paragraph with enough text to pass conversion quality checks. ".repeat(25);
        return List.of(chunk(0, text), chunk(1, text), chunk(2, text));
    }

    private RagChunk chunk(int index, String text) {
        return new RagChunk(UUID.randomUUID(), "key", null, "chunk-" + index, index,
                "body", "Body", index, 0, 1, "Title", text, "source.pdf", "source.tei", "v1");
    }
}
