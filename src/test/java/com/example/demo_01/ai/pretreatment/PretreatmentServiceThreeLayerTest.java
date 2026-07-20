package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactDocument;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.JournalQuality;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmJudgment;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmLabel;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentMode;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ResolvedJournal;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PretreatmentServiceThreeLayerTest {

    @TempDir
    Path tempDir;

    @Test
    void qualityFailureShortCircuitsBeforeLlm() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(), document(metadata("", "abstract"), goodChunks()), Map.of());

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.REJECTED);
        assertThat(result.rejectReasonCode()).isEqualTo("MISSING_TITLE");
        assertThat(result.llmLabel()).isEqualTo(LlmLabel.NOT_RUN);
        verify(llmJudge, never()).judgeAbstract(any(), any(), anyInt());
    }

    @Test
    void lowTitleRelevanceShortCircuitsBeforeLlm() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("Consumer willingness to pay for potatoes", "This abstract is long enough."), goodChunks()), Map.of());

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.REJECTED);
        assertThat(result.rejectReasonCode()).isEqualTo("REJECTED_LOW_TITLE_RELEVANCE");
        assertThat(result.llmLabel()).isEqualTo(LlmLabel.NOT_RUN);
        verify(llmJudge, never()).judgeAbstract(any(), any(), anyInt());
    }

    @Test
    void primaryOomyceteWithEnoughConfidenceIsAccepted() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenReturn(new LlmJudgment(LlmLabel.PRIMARY_OOMYCETE, 0.9, List.of("Pythium"),
                        "Pythium biology", List.of(), "Primary oomycete focus."));
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("A new disease caused by Pythium", "The abstract focuses on Pythium biology."), goodChunks()), Map.of());

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.ACCEPTED);
        assertThat(result.rejectReasonCode()).isBlank();
    }

    @Test
    void incidentalMentionIsRejected() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenReturn(new LlmJudgment(LlmLabel.INCIDENTAL_MENTION, 0.8, List.of("Pythium"),
                        "Other organism", List.of(), "Only mentions Pythium in passing."));
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("A new disease caused by Pythium", "The abstract mentions Pythium as background."), goodChunks()), Map.of());

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.REJECTED);
        assertThat(result.rejectReasonCode()).isEqualTo("LLM_INCIDENTAL_MENTION");
    }

    @Test
    void applyStillRemovesOnlyRejectedIdsWhenNotDryRun() throws Exception {
        UUID rejectedId = UUID.randomUUID();
        Path runDir = Files.createDirectories(tempDir.resolve("run-1"));
        Files.writeString(runDir.resolve("rejected-document-ids.txt"), rejectedId + "\n");
        PretreatmentProperties properties = properties();
        properties.setOutputRoot(tempDir.toString());
        properties.getCli().setMode("apply");
        properties.getCli().setDryRun(false);
        properties.getCli().setApplyRunId("run-1");

        PretreatmentRepository repository = mock(PretreatmentRepository.class);
        RagVectorIngestionService vectorIngestionService = mock(RagVectorIngestionService.class);
        PretreatmentService service = new PretreatmentService();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "reportWriter", new PretreatmentReportWriter());
        ReflectionTestUtils.setField(service, "ragVectorIngestionService", vectorIngestionService);

        var summary = service.apply();

        assertThat(summary.mode()).isEqualTo(PretreatmentMode.apply);
        assertThat(summary.vectorsRemoved()).isEqualTo(1);
        verify(vectorIngestionService).removeDocument(rejectedId);
    }

    private PretreatmentService service(PretreatmentLlmJudge llmJudge) {
        PretreatmentProperties properties = properties();
        OomyceteTitleVectorMatcher titleMatcher = new OomyceteTitleVectorMatcher();
        ReflectionTestUtils.setField(titleMatcher, "quwenEmbeddingModel", new FakeEmbeddingModel());
        JournalResolverService journalResolverService = mock(JournalResolverService.class);
        when(journalResolverService.resolve(any(), any()))
                .thenReturn(ResolvedJournal.raw("Journal", JournalQuality.unknown()));

        PretreatmentService service = new PretreatmentService();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "qualityGate", new PretreatmentQualityGate());
        ReflectionTestUtils.setField(service, "titleVectorMatcher", titleMatcher);
        ReflectionTestUtils.setField(service, "journalResolverService", journalResolverService);
        ReflectionTestUtils.setField(service, "llmJudge", llmJudge);
        return service;
    }

    private PretreatmentProperties properties() {
        PretreatmentProperties properties = new PretreatmentProperties();
        properties.setPromptPath("PreTreatment/prompts/oomycete-main-study-system.txt");
        properties.setAcceptanceConfidenceThreshold(0.65);
        properties.getTitleVector().setActiveThreshold(0.40);
        properties.getTitleVector().setThresholds(List.of(0.30, 0.40, 0.50, 0.60));
        return properties;
    }

    private ArtifactDocument document(RagDocumentMetadata metadata, List<RagChunk> chunks) {
        UUID documentId = UUID.randomUUID();
        PreprocessArtifact manifest = new PreprocessArtifact(documentId, "data/rag", "source.pdf",
                "header.tei", "fulltext.tei", "document.jsonl", "sha", "key",
                metadata, chunks.size(), "strategy", "preprocess");
        return new ArtifactDocument(documentId, "data/rag/" + documentId, manifest, chunks);
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

    private static class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(vector(segment.text())))
                    .toList());
        }

        private float[] vector(String text) {
            String lower = text == null ? "" : text.toLowerCase();
            if (lower.contains("oomycete")
                    || lower.contains("phytophthora")
                    || lower.contains("pythium")
                    || lower.contains("saprolegnia")
                    || lower.contains("plasmopara")
                    || lower.contains("peronospora")
                    || lower.contains("aphanomyces")
                    || lower.contains("achlya")
                    || lower.contains("bremia")
                    || lower.contains("hyaloperonospora")
                    || lower.contains("albugo")
                    || lower.contains("peronosclerospora")
                    || lower.contains("downy mildew")
                    || lower.contains("late blight")
                    || lower.contains("pythiosis")
                    || lower.contains("water mold")
                    || lower.contains("water mould")
                    || lower.contains("卵菌")
                    || lower.contains("疫霉")
                    || lower.contains("腐霉")
                    || lower.contains("水霉")
                    || lower.contains("霜霉")
                    || lower.contains("stramenopile plant pathogens")) {
                return new float[]{1.0f, 0.0f};
            }
            return new float[]{0.0f, 1.0f};
        }
    }
}
