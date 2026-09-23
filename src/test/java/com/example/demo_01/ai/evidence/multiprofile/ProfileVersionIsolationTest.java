package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceConfigScope;
import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.*;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileVersionIsolationTest {
    private final EvidenceProfileRegistry registry = new EvidenceProfileRegistry();

    @Test
    void extractionInheritsClassificationVersionAndRejectsCrossVersionReuse() {
        var service = new QuestionExtractionService();
        var repository = mock(MultiProfileEvidenceRepository.class);
        ReflectionTestUtils.setField(service, "profileRegistry", registry);
        ReflectionTestUtils.setField(service, "multiProfileRepository", repository);
        UUID batchId = UUID.randomUUID();
        BatchRecord batch = mock(BatchRecord.class);
        when(batch.profileVersion()).thenReturn(PROFILE_VERSION);
        when(repository.findBatch(batchId)).thenReturn(Optional.of(batch));
        assertThat(service.resolveVersion(request(batchId, null))).isEqualTo(PROFILE_VERSION);
        assertThatThrownBy(() -> service.resolveVersion(request(batchId, EXPERT_PROFILE_VERSION)))
                .hasMessageContaining("must match");
        when(batch.profileVersion()).thenReturn(EXPERT_PROFILE_VERSION);
        assertThat(service.resolveVersion(request(batchId, null))).isEqualTo(EXPERT_PROFILE_VERSION);
    }

    @Test
    void newBatchDefaultsToExpertVersionAndPinsItBeforeAsyncDispatch() {
        var service = new MultiProfileEvidenceService();
        var repository = mock(MultiProfileEvidenceRepository.class);
        var executor = mock(TaskExecutor.class);
        ReflectionTestUtils.setField(service, "profileRegistry", registry);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "properties", new EvidenceProperties());
        ReflectionTestUtils.setField(service, "batchExecutor", executor);
        UUID experimentId = UUID.randomUUID();
        var document = new SourceDocument(UUID.randomUUID(), "Paper", List.of(), 2026, "Journal", null, null);
        when(repository.findSourceDocuments(experimentId)).thenReturn(List.of(document));
        service.submit(new BatchRequest(experimentId, false));
        var captured = ArgumentCaptor.forClass(BatchRecord.class);
        verify(repository).insertBatch(captured.capture(), eq(List.of(document)));
        assertThat(captured.getValue().profileVersion()).isEqualTo(EXPERT_PROFILE_VERSION);
        verify(repository).findActiveBatch(eq(experimentId), anyString(), eq(EXPERT_PROFILE_VERSION),
                anyString(), isNull(), eq(true));
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void identicalDocumentsAndQuestionDoNotReuseOtherVersionExtractionRuns() {
        var service = new QuestionExtractionService();
        var repository = mock(QuestionExtractionRepository.class);
        var sources = mock(MultiProfileEvidenceRepository.class);
        var pipeline = new MultiProfileEvidenceService();
        ReflectionTestUtils.setField(pipeline, "profileRegistry", registry);
        EvidenceProperties properties = new EvidenceProperties();
        EvidenceConfigScope scope = new EvidenceConfigScope();
        ReflectionTestUtils.setField(scope, "global", properties);
        ReflectionTestUtils.setField(scope, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "multiProfileRepository", sources);
        ReflectionTestUtils.setField(service, "multiProfileEvidenceService", pipeline);
        ReflectionTestUtils.setField(service, "profileRegistry", registry);
        ReflectionTestUtils.setField(service, "configScope", scope);
        ReflectionTestUtils.setField(service, "batchExecutor", mock(TaskExecutor.class));
        UUID id = UUID.randomUUID();
        when(sources.findDocumentsByIds(List.of(id))).thenReturn(List.of(
                new SourceDocument(id, "Paper", List.of(), null, null, null, null)));
        for (String version : List.of(PROFILE_VERSION, EXPERT_PROFILE_VERSION)) {
            service.submit(new ExtractionRunRequest("Q1", "test", ExtractionSourceType.DOCUMENT_IDS,
                    null, null, null, List.of(id), null, null, false, version));
        }
        var runs = ArgumentCaptor.forClass(ExtractionRunRecord.class);
        verify(repository, times(2)).insertRun(runs.capture(), anyList());
        assertThat(runs.getAllValues()).extracting(ExtractionRunRecord::profileVersion)
                .containsExactly(PROFILE_VERSION, EXPERT_PROFILE_VERSION);
        assertThat(runs.getAllValues().get(0).inputHash()).isEqualTo(runs.getAllValues().get(1).inputHash());
        assertThat(runs.getAllValues().get(0).configHash()).isNotEqualTo(runs.getAllValues().get(1).configHash());
        for (String version : List.of(PROFILE_VERSION, EXPERT_PROFILE_VERSION)) {
            verify(repository).findActiveRun(eq("Q1"), anyString(), eq(version), anyString(), isNull());
            verify(repository).findReusableRun(eq("Q1"), anyString(), anyString(), isNull(), eq(version));
        }
    }

    @Test
    void expertRecordsNeverOverwriteLegacyCompoundProjection() {
        var service = new MultiProfileEvidencePersistenceService();
        var generic = mock(MultiProfileEvidenceRepository.class);
        var compounds = mock(EvidenceRepository.class);
        ReflectionTestUtils.setField(service, "multiProfileRepository", generic);
        ReflectionTestUtils.setField(service, "evidenceRepository", compounds);
        UUID document = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        for (String question : List.of("Q1", "Q5")) {
            service.replaceEvidence(null, run, document, question, ClassificationStatus.NOT_CLASSIFIED,
                    List.of(), "source", "prompt", "model", EXPERT_PROFILE_VERSION);
            verify(generic).replaceEvidence(null, run, document, question, EXPERT_PROFILE_VERSION,
                    ClassificationStatus.NOT_CLASSIFIED, List.of());
        }
        verifyNoInteractions(compounds);
    }

    private ExtractionRunRequest request(UUID batchId, String version) {
        return new ExtractionRunRequest("Q1", "test", ExtractionSourceType.CLASSIFICATION_RUN,
                batchId, null, null, null, null, null, false, version);
    }
}
