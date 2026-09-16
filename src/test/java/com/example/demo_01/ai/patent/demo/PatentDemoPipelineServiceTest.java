package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.BatchStatus;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunAccepted;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunRequest;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionSourceType;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionService;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.CandidateEnumeration;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.ExistingRagDocument;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRequest;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTextLayerStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.StructuredArtifactResult;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.TextLayerReport;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentIngestionOutcome;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagJobStatus;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.service.RagIngestionFromArtifactService;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import com.example.demo_01.ai.stage.CohortService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatentDemoPipelineServiceTest {

    @Mock
    private PatentCandidateEnumerator candidateEnumerator;
    @Mock
    private PatentTextLayerDetector textLayerDetector;
    @Mock
    private PatentPageTriageService pageTriageService;
    @Mock
    private PatentStructuredArtifactService artifactService;
    @Mock
    private PatentDemoRepository repository;
    @Mock
    private RagDocumentRepository ragDocumentRepository;
    @Mock
    private RagIngestionFromArtifactService ragIngestionFromArtifactService;
    @Mock
    private RagVectorIngestionService ragVectorIngestionService;
    @Mock
    private CohortService cohortService;
    @Mock
    private QuestionExtractionService questionExtractionService;

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @EnumSource(value = RagDocumentStatus.class, names = {"COMPLETED", "FAILED", "PROCESSING", "QUEUED"})
    void forceReusesCanonicalIdAndFinishesArtifactBeforeRebuilding(RagDocumentStatus previousStatus) throws Exception {
        PatentCandidate candidate = selectedCandidate();
        UUID documentId = UUID.randomUUID();
        Path oldArtifact = tempDir.resolve("previous/artifact-manifest.json");
        Files.createDirectories(oldArtifact.getParent());
        Files.writeString(oldArtifact, "previous artifact");
        when(repository.findCanonicalRagDocumentByPublicationNumber(candidate.publicationNumber()))
                .thenReturn(Optional.of(new ExistingRagDocument(documentId, oldArtifact.getParent().toString(), previousStatus)));
        Path nextArtifact = stubArtifact(candidate);
        when(ragIngestionFromArtifactService.ingestDocument(documentId, null))
                .thenReturn(outcome(documentId, RagJobStatus.COMPLETED));
        UUID cohortId = UUID.randomUUID();
        when(cohortService.create(anyString(), eq("PATENT_DEMO"), any(UUID.class), eq(List.of(documentId)), anyString()))
                .thenReturn(cohortId);
        when(questionExtractionService.submit(any())).thenReturn(new ExtractionRunAccepted(
                UUID.randomUUID(), "Q1", BatchStatus.QUEUED, 1, false));

        var accepted = service().submit(targetRequest(true, true));

        verify(repository).insertRun(eq(accepted.runId()), anyString(), anyString(), eq("cat"),
                eq(1), eq(1L), eq(true), eq(true), eq(List.of("CN106857590B")),
                eq("legacy"), eq("balanced"), eq("rules"));
        var order = inOrder(artifactService, repository, ragDocumentRepository,
                ragVectorIngestionService, ragIngestionFromArtifactService, questionExtractionService);
        order.verify(artifactService).write(eq(accepted.runId()), eq(documentId), eq(candidate), any());
        order.verify(repository).updateRagDocumentStorageRoot(documentId, nextArtifact.toString());
        order.verify(ragDocumentRepository).markProcessing(eq(documentId), any(), eq("same-pdf-sha"), eq("patent:CN106857590B"));
        order.verify(ragDocumentRepository).updatePreprocessState(documentId, PreprocessStatus.COMPLETED);
        order.verify(ragVectorIngestionService).removeDocument(documentId);
        order.verify(ragIngestionFromArtifactService).ingestDocument(documentId, null);
        order.verify(ragDocumentRepository).markCompleted(eq(documentId), any(), eq("same-pdf-sha"), eq("patent:CN106857590B"));
        order.verify(repository).markDocumentIngested(accepted.runId(), "CN106857590B", documentId, 1, 1);
        order.verify(questionExtractionService).submit(any());
        verify(ragDocumentRepository, never()).insertInitial(any(), anyString(), anyString(), any());
        verify(repository, never()).countVectorChunks(any());
        verify(repository).finishRun(accepted.runId(), PatentDemoRunStatus.COMPLETED, null);
        assertThat(Files.readString(oldArtifact)).isEqualTo("previous artifact");
    }

    @Test
    void artifactFailureLeavesExistingStorageAndIndexesUntouched() throws Exception {
        PatentCandidate candidate = selectedCandidate();
        UUID documentId = UUID.randomUUID();
        when(repository.findCanonicalRagDocumentByPublicationNumber(candidate.publicationNumber()))
                .thenReturn(Optional.of(new ExistingRagDocument(documentId, tempDir.resolve("previous").toString())));
        when(artifactService.write(any(), eq(documentId), eq(candidate), any()))
                .thenThrow(new IllegalStateException("artifact incomplete"));

        var accepted = service().submit(targetRequest(true, true));

        verify(repository, never()).updateRagDocumentStorageRoot(any(), anyString());
        verifyNoInteractions(ragVectorIngestionService, ragIngestionFromArtifactService, ragDocumentRepository,
                cohortService, questionExtractionService);
        verify(repository).markDocumentFailed(eq(accepted.runId()), eq("CN106857590B"), eq(documentId),
                eq(1), eq(0), contains("ARTIFACT_WRITE_FAILED: artifact incomplete"));
        verify(repository).finishRun(accepted.runId(), PatentDemoRunStatus.FAILED, null);
    }

    @ParameterizedTest
    @EnumSource(value = RagJobStatus.class, names = {"FAILED", "DUPLICATE_SKIPPED"})
    void unsuccessfulRebuildIsExplicitlyFailedAndNeverSubmittedForQ1(RagJobStatus outcomeStatus) throws Exception {
        PatentCandidate candidate = selectedCandidate();
        UUID documentId = UUID.randomUUID();
        when(repository.findCanonicalRagDocumentByPublicationNumber(candidate.publicationNumber()))
                .thenReturn(Optional.of(new ExistingRagDocument(documentId, tempDir.resolve("previous").toString())));
        Path nextArtifact = stubArtifact(candidate);
        when(ragIngestionFromArtifactService.ingestDocument(documentId, null)).thenReturn(outcome(documentId, outcomeStatus));

        var accepted = service().submit(targetRequest(true, true));

        verify(ragVectorIngestionService).removeDocument(documentId);
        verify(ragDocumentRepository).markFailed(documentId);
        verify(ragDocumentRepository, never()).markCompleted(any(), any(), anyString(), anyString());
        verify(repository).updateRagDocumentStorageRoot(documentId, nextArtifact.toString());
        verify(repository).markDocumentFailed(eq(accepted.runId()), eq("CN106857590B"), eq(documentId),
                eq(1), eq(1), contains("RAG_REBUILD_FAILED: RAG_INGESTION_" + outcomeStatus));
        verify(repository, never()).markDocumentIngested(any(), anyString(), any(), anyInt(), anyInt());
        verifyNoInteractions(cohortService, questionExtractionService);
    }

    @Test
    void indexRemovalFailureDoesNotIngestOrSubmitQ1() throws Exception {
        PatentCandidate candidate = selectedCandidate();
        UUID documentId = UUID.randomUUID();
        when(repository.findCanonicalRagDocumentByPublicationNumber(candidate.publicationNumber()))
                .thenReturn(Optional.of(new ExistingRagDocument(documentId, tempDir.resolve("previous").toString())));
        stubArtifact(candidate);
        doThrow(new IllegalStateException("BM25 removal failed")).when(ragVectorIngestionService).removeDocument(documentId);

        var accepted = service().submit(targetRequest(true, true));

        verify(ragDocumentRepository).markFailed(documentId);
        verify(repository).markDocumentFailed(eq(accepted.runId()), eq("CN106857590B"), eq(documentId),
                eq(1), eq(1), contains("RAG_REBUILD_FAILED: BM25 removal failed"));
        verifyNoInteractions(ragIngestionFromArtifactService, cohortService, questionExtractionService);
    }

    @ParameterizedTest
    @EnumSource(value = PatentTextLayerStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "TEXT_LAYER_USABLE")
    void forcedSkippedAndErrorPdfsNeverTouchCanonicalDocument(PatentTextLayerStatus status) throws Exception {
        PatentCandidate candidate = candidate();
        when(candidateEnumerator.enumerate(any(), any(), eq("cat"), eq(1), eq(1L), eq(List.of("CN106857590B"))))
                .thenReturn(new CandidateEnumeration(1, List.of(candidate)));
        when(textLayerDetector.inspect(candidate.pdfPath())).thenReturn(new TextLayerReport(status, 0, 0, 0, 0, 0, ""));

        var accepted = service().submit(targetRequest(true, true));

        verify(repository).markDocumentSkipped(accepted.runId(), "CN106857590B", status.name(), 0);
        verify(repository, never()).findCanonicalRagDocumentByPublicationNumber(anyString());
        verifyNoInteractions(pageTriageService, artifactService, ragDocumentRepository, ragVectorIngestionService,
                ragIngestionFromArtifactService, cohortService, questionExtractionService);
    }

    @Test
    void nonForcedCompletedCanonicalDocumentKeepsExistingArtifactAndIndexes() throws Exception {
        PatentCandidate candidate = selectedCandidate();
        UUID documentId = UUID.randomUUID();
        when(repository.findCanonicalRagDocumentByPublicationNumber(candidate.publicationNumber()))
                .thenReturn(Optional.of(new ExistingRagDocument(documentId, tempDir.resolve("previous").toString())));
        when(repository.countVectorChunks(documentId)).thenReturn(3L);

        var accepted = service().submit(targetRequest(false, false));

        verify(repository).markDocumentIngested(accepted.runId(), "CN106857590B", documentId, 1, 3);
        verifyNoInteractions(artifactService, ragDocumentRepository, ragVectorIngestionService,
                ragIngestionFromArtifactService, questionExtractionService);
    }

    @Test
    void getRunKeepsIngestionCompletedWhileExtractionStatusChanges() {
        UUID runId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        PatentDemoRunRecord running = runRecord(runId, extractionId, "RUNNING", 0);
        PatentDemoRunRecord completed = runRecord(runId, extractionId, "COMPLETED", 2);
        when(repository.findRun(runId)).thenReturn(Optional.of(running), Optional.of(completed));
        var service = service();

        assertThat(service.requireRun(runId)).isEqualTo(running);
        var refreshed = service.requireRun(runId);
        assertThat(refreshed.status()).isEqualTo(PatentDemoRunStatus.COMPLETED);
        assertThat(refreshed.extractionStatus()).isEqualTo("COMPLETED");
        assertThat(refreshed.validEvidenceRows()).isEqualTo(2);
        assertThat(refreshed.evidenceRows()).isEqualTo(3);
    }

    @Test
    void nonQ1TrialsAreAuditedButNeverIngestedOrSubmitted() throws Exception {
        PatentCandidate candidate = selectedCandidate();
        var page = new PatentPageAssessment(7, 20, Set.of(PatentPageRole.TEST_DEFINITION),
                true, "no-confirmed-q1-assay", 100, "Non-oomycete trial");
        when(pageTriageService.triage(candidate.pdfPath()))
                .thenReturn(new PatentTriageResult(List.of(page), List.of(page)));
        var accepted = service().submit(targetRequest(true, true));
        verify(repository).replacePages(accepted.runId(), candidate.publicationNumber(), List.of(page));
        verify(repository).markDocumentSkipped(accepted.runId(), candidate.publicationNumber(), "NO_Q1_ASSAY_EVIDENCE", 0);
        verifyNoInteractions(artifactService, ragIngestionFromArtifactService, questionExtractionService);
    }

    @Test
    void v3DefersNoEvidenceDecisionUntilArtifactDiscoveryFinishes() throws Exception {
        PatentCandidate candidate = candidate();
        when(candidateEnumerator.enumerate(any(), any(), eq("cat"), eq(1), eq(1L), eq(List.of("CN106857590B"))))
                .thenReturn(new CandidateEnumeration(1, List.of(candidate)));
        when(textLayerDetector.inspect(candidate.pdfPath())).thenReturn(new TextLayerReport(
                PatentTextLayerStatus.TEXT_LAYER_USABLE, 10, 1000, 2000, 1, 0, ""));
        var page = new PatentPageAssessment(7, 20, Set.of(PatentPageRole.TEST_DEFINITION),
                true, "assay-inspection|no-confirmed-q1-assay", 100, "Non-oomycete trial");
        PatentTriageResult triage = new PatentTriageResult(List.of(page), List.of(page));
        when(pageTriageService.triage(candidate.pdfPath())).thenReturn(triage);
        UUID documentId = UUID.randomUUID();
        when(repository.findCanonicalRagDocumentByPublicationNumber(candidate.publicationNumber()))
                .thenReturn(Optional.of(new ExistingRagDocument(documentId, tempDir.resolve("previous").toString())));
        when(artifactService.write(any(), eq(documentId), eq(candidate), eq(triage)))
                .thenReturn(new StructuredArtifactResult(documentId, tempDir.resolve("v3-artifact"), 1, "sha",
                        "patent:CN106857590B", new RagDocumentMetadata(null, null, "Target",
                        List.of(), List.of(), null, "Patent", null, null),
                        PatentDemoModels.PatentDiscoveryStatus.COMPLETE,
                        PatentDemoModels.PatentDiscoveryOutcome.NO_EVIDENCE_FOUND, 1));

        var accepted = service().submit(new PatentDemoRunRequest(tempDir.resolve("manifest.csv").toString(),
                tempDir.toString(), "cat", 1, 1L, true, true, List.of("CN106857590B"),
                "v3", "balanced", "rules"));

        verify(artifactService).write(accepted.runId(), documentId, candidate, triage);
        verify(repository).markDocumentSkipped(accepted.runId(), "CN106857590B",
                "NO_Q1_ASSAY_EVIDENCE", 1);
        verifyNoInteractions(ragIngestionFromArtifactService, questionExtractionService);
    }

    @Test
    void oldAndTargetedJsonRequestsRemainCompatible() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PatentDemoRunRequest old = mapper.readValue("{\"limit\":1}", PatentDemoRunRequest.class);
        assertThat(old.publicationNumbers()).isNull();
        PatentDemoRunRequest targeted = mapper.readValue("""
                {"publicationNumbers":["CN106857590B"],"limit":1,"force":true,
                 "strategyVersion":"v3","discoveryMode":"balanced","subjectMode":"rules"}
                """, PatentDemoRunRequest.class);
        assertThat(targeted.publicationNumbers()).containsExactly("CN106857590B");
        assertThat(targeted.force()).isTrue();
        assertThat(targeted.strategyVersion()).isEqualTo("v3");
        assertThat(targeted.discoveryMode()).isEqualTo("balanced");
        assertThat(targeted.subjectMode()).isEqualTo("rules");
    }

    private PatentDemoPipelineService service() {
        return new PatentDemoPipelineService(Runnable::run, candidateEnumerator, textLayerDetector,
                pageTriageService, artifactService, repository, ragDocumentRepository,
                ragIngestionFromArtifactService, ragVectorIngestionService, cohortService,
                questionExtractionService, new ObjectMapper());
    }

    private PatentCandidate candidate() {
        return new PatentCandidate("CN106857590B", "Target", "cat", tempDir.resolve("CN106857590B.pdf"));
    }

    private PatentCandidate selectedCandidate() throws Exception {
        PatentCandidate candidate = candidate();
        when(candidateEnumerator.enumerate(any(), any(), eq("cat"), eq(1), eq(1L), eq(List.of("CN106857590B"))))
                .thenReturn(new CandidateEnumeration(1, List.of(candidate)));
        when(textLayerDetector.inspect(candidate.pdfPath())).thenReturn(new TextLayerReport(
                PatentTextLayerStatus.TEXT_LAYER_USABLE, 10, 1000, 2000, 1, 0, ""));
        PatentPageAssessment selected = new PatentPageAssessment(7, 20, Set.of(PatentPageRole.ACTIVITY_TABLE, PatentPageRole.Q1_ACTIVITY),
                true, "activity-table", 100, "Cmpd No. Test A\n49 99\n");
        when(pageTriageService.triage(candidate.pdfPath()))
                .thenReturn(new PatentTriageResult(List.of(selected), List.of(selected)));
        return candidate;
    }

    private Path stubArtifact(PatentCandidate candidate) {
        Path storageDir = tempDir.resolve("replacement");
        when(artifactService.write(any(), any(), eq(candidate), any())).thenAnswer(invocation -> {
            Files.createDirectories(storageDir);
            Files.writeString(storageDir.resolve("artifact-manifest.json"), "complete replacement");
            return new StructuredArtifactResult(invocation.getArgument(1), storageDir, 1, "same-pdf-sha",
                    "patent:CN106857590B", new RagDocumentMetadata(null, null, "Target", List.of(), List.of(),
                    null, "Patent", null, null));
        });
        return storageDir;
    }

    private PatentDemoRunRequest targetRequest(boolean force, boolean runExtraction) {
        return new PatentDemoRunRequest(tempDir.resolve("manifest.csv").toString(), tempDir.toString(),
                "cat", 1, 1L, runExtraction, force, List.of("CN106857590B"));
    }

    private RagDocumentIngestionOutcome outcome(UUID documentId, RagJobStatus status) {
        return new RagDocumentIngestionOutcome(documentId, UUID.randomUUID(), status, null,
                1, 0L, 0L, null, null, null, null, null, 0L, 0L, 0L);
    }

    private PatentDemoRunRecord runRecord(UUID runId, UUID extractionId, String extractionStatus, int validRows) {
        return new PatentDemoRunRecord(runId, PatentDemoRunStatus.COMPLETED, "manifest.csv", "pdf", "cat",
                1, 1L, true, true, 1, 1, 1, 0, 0, UUID.randomUUID(), extractionId, 3, null, 1L,
                null, null, null, null, List.of("CN106857590B"), extractionStatus, validRows);
    }

    @Test
    void onlyTextLayerPatentsAreIngestedAndSubmittedForQ1Extraction() throws Exception {
        TaskExecutor directExecutor = Runnable::run;
        PatentDemoPipelineService service = new PatentDemoPipelineService(
                directExecutor,
                candidateEnumerator,
                textLayerDetector,
                pageTriageService,
                artifactService,
                repository,
                ragDocumentRepository,
                ragIngestionFromArtifactService,
                ragVectorIngestionService,
                cohortService,
                questionExtractionService,
                new ObjectMapper());

        PatentCandidate usable = new PatentCandidate(
                "AU-TEXT", "Fungicidal compounds", "cat", tempDir.resolve("text.pdf"));
        PatentCandidate scanned = new PatentCandidate(
                "AU-SCAN", "Scanned compounds", "cat", tempDir.resolve("scan.pdf"));
        when(candidateEnumerator.enumerate(any(Path.class), any(Path.class), eq("cat"), eq(2), eq(1L), eq(List.of())))
                .thenReturn(new CandidateEnumeration(2, List.of(usable, scanned)));
        when(textLayerDetector.inspect(usable.pdfPath())).thenReturn(new TextLayerReport(
                PatentTextLayerStatus.TEXT_LAYER_USABLE, 10, 1000L, 2000, 1.0d, 0.0d, ""));
        when(textLayerDetector.inspect(scanned.pdfPath())).thenReturn(new TextLayerReport(
                PatentTextLayerStatus.SCANNED_NEEDS_OCR, 10, 1000L, 0, 0.0d, 0.0d, ""));
        PatentPageAssessment selectedPage = new PatentPageAssessment(
                7,
                20,
                Set.of(PatentPageRole.ACTIVITY_TABLE, PatentPageRole.Q1_ACTIVITY),
                true,
                "activity-table",
                100,
                "Cmpd No. Test A\n49 99\n");
        PatentTriageResult triage = new PatentTriageResult(List.of(selectedPage), List.of(selectedPage));
        when(pageTriageService.triage(usable.pdfPath())).thenReturn(triage);
        when(repository.findCanonicalRagDocumentByPublicationNumber("AU-TEXT"))
                .thenReturn(Optional.empty());
        RagDocumentMetadata metadata = new RagDocumentMetadata(
                null, null, "Fungicidal compounds", List.of(), List.of(), null, "Patent", null, null);
        when(artifactService.write(any(UUID.class), any(UUID.class), eq(usable), eq(triage)))
                .thenAnswer(invocation -> new StructuredArtifactResult(
                        invocation.getArgument(1),
                        tempDir.resolve("artifact"),
                        1,
                        "sha",
                        "patent:AU-TEXT",
                        metadata));
        when(ragIngestionFromArtifactService.ingestDocument(any(UUID.class), isNull()))
                .thenAnswer(invocation -> new RagDocumentIngestionOutcome(
                        invocation.getArgument(0),
                        UUID.randomUUID(),
                        RagJobStatus.COMPLETED,
                        null,
                        1,
                        0L,
                        0L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        0L,
                        0L));
        UUID cohortId = UUID.randomUUID();
        UUID extractionRunId = UUID.randomUUID();
        when(cohortService.create(anyString(), eq("PATENT_DEMO"), any(UUID.class), anyList(), anyString()))
                .thenReturn(cohortId);
        when(questionExtractionService.submit(any(ExtractionRunRequest.class)))
                .thenReturn(new ExtractionRunAccepted(extractionRunId, "Q1", BatchStatus.QUEUED, 1, false));

        service.submit(new PatentDemoRunRequest(
                tempDir.resolve("manifest.csv").toString(),
                tempDir.toString(),
                "cat",
                2,
                1L,
                true,
                false));

        verify(ragDocumentRepository, times(1)).insertInitial(any(UUID.class), eq("text.pdf"), anyString(), any());
        verify(ragIngestionFromArtifactService, times(1)).ingestDocument(any(UUID.class), isNull());
        verify(repository).markDocumentSkipped(any(UUID.class), eq("AU-SCAN"), eq("SCANNED_NEEDS_OCR"), eq(0));
        ArgumentCaptor<ExtractionRunRequest> captor = ArgumentCaptor.forClass(ExtractionRunRequest.class);
        verify(questionExtractionService).submit(captor.capture());
        ExtractionRunRequest request = captor.getValue();
        assertThat(request.questionId()).isEqualTo("Q1");
        assertThat(request.sourceType()).isEqualTo(ExtractionSourceType.COHORT);
        assertThat(request.cohortId()).isEqualTo(cohortId);
        assertThat(request.overrides().path("table").path("enabled").asBoolean()).isTrue();
        assertThat(request.overrides().path("agents").path("verifier").path("enabled").asBoolean()).isTrue();
        assertThat(request.overrides().path("agents").path("verifier").path("dropInvalidRows").asBoolean()).isFalse();
        assertThat(request.overrides().path("agents").path("retriever").path("onDemandEnabled").asBoolean()).isFalse();
        assertThat(request.overrides().path("agents").path("coverage").path("enabled").asBoolean()).isFalse();
        assertThat(request.overrides().path("agents").path("reconciler").path("entityLinkingEnabled").asBoolean()).isFalse();
        assertThat(request.overrides().path("agents").path("reconciler").path("enqueueUnknownAsCandidates").asBoolean()).isFalse();
    }
}
