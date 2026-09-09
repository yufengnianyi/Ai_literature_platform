package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactDocument;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmJudgment;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmLabel;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityStatus;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.RelevanceDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentMode;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PretreatmentServiceScreeningTest {

    @TempDir
    Path tempDir;

    @Test
    void potentiallyRelevantAbstractIsAccepted() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        PretreatmentService service = service(llmJudge);

        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenReturn(new LlmJudgment(LlmLabel.POTENTIALLY_RELEVANT, List.of(), "", List.of(), "Needs review."));

        var result = service.screenDocument(UUID.randomUUID(), document(metadata("", "abstract"), goodChunks()));

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.ACCEPTED);
        assertThat(result.relevanceDecision()).isEqualTo(RelevanceDecision.POTENTIALLY_RELEVANT);
        assertThat(result.qualityStatus()).isEqualTo(QualityStatus.FULL_TEXT_READY);
        verify(llmJudge).judgeAbstract(any(), any(), anyInt());
    }

    @Test
    void lowTitleRelevanceStillRunsLlm() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenReturn(new LlmJudgment(LlmLabel.NOT_RELEVANT, List.of(),
                        "Consumer preference study", List.of(), "No oomycete focus."));
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("Consumer willingness to pay for potatoes", "This abstract is long enough."), goodChunks()));

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.REJECTED);
        assertThat(result.rejectReasonCode()).isEqualTo("LLM_NOT_RELEVANT");
        assertThat(result.llmLabel()).isEqualTo(LlmLabel.NOT_RELEVANT);
        verify(llmJudge).judgeAbstract(any(), any(), anyInt());
    }

    @Test
    void relevantWithEnoughConfidenceIsAccepted() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenReturn(new LlmJudgment(LlmLabel.RELEVANT, List.of("Pythium"),
                        "Pythium biology", List.of(), "Relevant oomycete focus."));
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("A new disease caused by Pythium", "The abstract focuses on Pythium biology."), goodChunks()));

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.ACCEPTED);
        assertThat(result.relevanceDecision()).isEqualTo(RelevanceDecision.RELEVANT);
        assertThat(result.rejectReasonCode()).isBlank();
    }

    @Test
    void notRelevantIsRejected() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenReturn(new LlmJudgment(LlmLabel.NOT_RELEVANT, List.of("Pythium"),
                        "Other organism", List.of(), "Only mentions Pythium in passing."));
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("A new disease caused by Pythium", "The abstract mentions Pythium as background."), goodChunks()));

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.REJECTED);
        assertThat(result.rejectReasonCode()).isEqualTo("LLM_NOT_RELEVANT");
    }

    @Test
    void lowQualityArtifactStillUsesAbstractScreening() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenReturn(new LlmJudgment(LlmLabel.RELEVANT, List.of("Pythium"),
                        "Pythium biology", List.of(), "Relevant oomycete focus."));
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("Pythium study", "The abstract focuses on Pythium biology."),
                        List.of(chunk(0, "short text"))));

        assertThat(result.qualityStatus()).isEqualTo(QualityStatus.METADATA_READY);
        assertThat(result.finalDecision()).isEqualTo(FinalDecision.ACCEPTED);
        verify(llmJudge).judgeAbstract(any(), any(), anyInt());
    }

    @Test
    void missingAbstractIsAcceptedAsPotentiallyRelevantWithoutLlm() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("Antifungal compounds against plant pathogens", ""), goodChunks()));

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.ACCEPTED);
        assertThat(result.relevanceDecision()).isEqualTo(RelevanceDecision.POTENTIALLY_RELEVANT);
        verify(llmJudge, never()).judgeAbstract(any(), any(), anyInt());
    }

    @Test
    void llmFailureIsAcceptedAsPotentiallyRelevant() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        when(llmJudge.judgeAbstract(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("Configured LLM is unavailable"));
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(),
                document(metadata("Plant pathogen control", "This abstract is long enough."), goodChunks()));

        assertThat(result.finalDecision()).isEqualTo(FinalDecision.ACCEPTED);
        assertThat(result.relevanceDecision()).isEqualTo(RelevanceDecision.POTENTIALLY_RELEVANT);
        assertThat(result.llmLabel()).isEqualTo(LlmLabel.NOT_RUN);
    }

    @Test
    void noMetadataOrReadableTextIsSkippedRatherThanRejected() {
        PretreatmentLlmJudge llmJudge = mock(PretreatmentLlmJudge.class);
        PretreatmentService service = service(llmJudge);

        var result = service.screenDocument(UUID.randomUUID(), document(metadata("", ""), List.of()));

        assertThat(result.qualityStatus()).isEqualTo(QualityStatus.UNUSABLE);
        assertThat(result.finalDecision()).isEqualTo(FinalDecision.SKIPPED);
        assertThat(result.relevanceDecision()).isEqualTo(RelevanceDecision.NOT_EVALUATED);
        verify(llmJudge, never()).judgeAbstract(any(), any(), anyInt());
    }

    @Test
    void applyRecordsRejectedIdsWithoutDeletingVectors() throws Exception {
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
        assertThat(summary.vectorsRemoved()).isZero();
        verifyNoInteractions(vectorIngestionService);
    }

    @Test
    void documentIdFilesSelectExactlyTheRequestedCanonicalDocuments() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        RagDocumentRecord first = mock(RagDocumentRecord.class);
        RagDocumentRecord second = mock(RagDocumentRecord.class);
        RagDocumentRecord extra = mock(RagDocumentRecord.class);
        when(first.documentId()).thenReturn(firstId);
        when(second.documentId()).thenReturn(secondId);
        when(extra.documentId()).thenReturn(UUID.randomUUID());

        Path idsFile = tempDir.resolve("previous-test-ids.txt");
        Files.writeString(idsFile, secondId + "\n" + firstId + "\n" + secondId + "\n");
        PretreatmentProperties properties = properties();
        properties.setDocumentIdFiles(List.of(idsFile.toString()));
        RagDocumentRepository ragDocumentRepository = mock(RagDocumentRepository.class);
        when(ragDocumentRepository.findAllCanonical()).thenReturn(List.of(first, extra, second));

        PretreatmentService service = new PretreatmentService();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "ragDocumentRepository", ragDocumentRepository);

        assertThat(service.selectDocuments()).containsExactly(second, first);
    }

    private PretreatmentService service(PretreatmentLlmJudge llmJudge) {
        PretreatmentProperties properties = properties();

        PretreatmentService service = new PretreatmentService();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "qualityGate", new PretreatmentQualityGate());
        ReflectionTestUtils.setField(service, "llmJudge", llmJudge);
        return service;
    }

    private PretreatmentProperties properties() {
        PretreatmentProperties properties = new PretreatmentProperties();
        properties.setPromptPath("PreTreatment/prompts/oomycete-main-study-system.txt");
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
}
