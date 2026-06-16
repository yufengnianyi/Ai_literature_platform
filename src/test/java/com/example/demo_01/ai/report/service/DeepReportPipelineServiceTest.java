package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ValidationStatus;
import com.example.demo_01.ai.report.config.ReportProperties;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import com.example.demo_01.ai.report.model.ReportModels.SectionEvidenceMatrix;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureAnalysisStatus;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureSourceType;
import com.example.demo_01.ai.report.model.ReportModels.SelectedLiterature;
import com.example.demo_01.ai.report.repository.ReportLiteratureRepository;
import com.example.demo_01.ai.report.repository.ReportRepository;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class DeepReportPipelineServiceTest {

    @Test
    void commonCompoundsShouldRankByIndependentDocumentCoverage() {
        DeepReportPipelineService service = service(new ReportProperties(), null, null);
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID documentC = UUID.randomUUID();
        List<RankedEvidence> evidence = List.of(
                ranked(evidence(documentA, "Compound A")),
                ranked(evidence(documentA, "Compound A")),
                ranked(evidence(documentB, "Compound B")),
                ranked(evidence(documentC, "Compound B"))
        );

        List<DeepReportPipelineService.CommonCompound> common = service.commonCompounds(evidence);

        assertEquals("Compound B", common.getFirst().name());
        assertEquals(2, common.getFirst().documentCount());
        assertEquals("Compound A", common.get(1).name());
        assertEquals(1, common.get(1).documentCount());
    }

    @Test
    void directLiteratureShouldRespectLimitAndRequireCompletedDocumentsWithChunks() {
        ReportProperties properties = new ReportProperties();
        properties.setMaxDirectDocuments(2);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        ReportLiteratureRepository literatureRepository = mock(ReportLiteratureRepository.class);
        DeepReportPipelineService service =
                service(properties, documentRepository, literatureRepository);
        UUID completedOne = UUID.randomUUID();
        UUID completedTwo = UUID.randomUUID();
        UUID noChunks = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        when(documentRepository.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id.equals(failed)) {
                return Optional.of(document(id, RagDocumentStatus.FAILED));
            }
            return Optional.of(document(id, RagDocumentStatus.COMPLETED));
        });
        when(literatureRepository.hasDocumentChunks(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return !id.equals(noChunks);
        });
        List<RankedEvidence> evidence = List.of(
                ranked(evidence(noChunks, "Compound A")),
                ranked(evidence(failed, "Compound B")),
                ranked(evidence(completedOne, "Compound C")),
                ranked(evidence(completedTwo, "Compound D"))
        );

        var selected = service.selectDirectLiterature(evidence);

        assertEquals(2, selected.size());
        assertEquals(
                List.of(completedOne, completedTwo).stream().sorted().toList(),
                selected.stream().map(item -> item.documentId()).sorted().toList());
    }

    @Test
    void supplementalRetrievalShouldNeverExceedTwoRounds() {
        ReportProperties properties = new ReportProperties();
        properties.setRetrievalRounds(10);
        properties.setMaxSupplementalDocuments(1);
        ReportLiteratureRetrievalService retrievalService =
                mock(ReportLiteratureRetrievalService.class);
        when(retrievalService.retrieve(any(), anyInt())).thenReturn(List.of());
        DeepReportPipelineService service = service(properties, null, null);
        ReflectionTestUtils.setField(service, "retrievalService", retrievalService);
        SectionEvidenceMatrix matrix = new SectionEvidenceMatrix(
                Map.of("mechanisms", 0),
                List.of("mechanisms"),
                List.of("compound mechanism"));

        service.selectSupplementalLiterature(matrix, List.of(), new ArrayList<>());

        verify(retrievalService, times(2)).retrieve(any(), anyInt());
    }

    @Test
    void supplementalLiteratureShouldBeHardCappedAtFiveDocuments() {
        ReportProperties properties = new ReportProperties();
        properties.setMaxSupplementalDocuments(20);
        properties.setRetrievalRounds(1);
        ReportLiteratureRetrievalService retrievalService =
                mock(ReportLiteratureRetrievalService.class);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        ReportLiteratureRepository literatureRepository = mock(ReportLiteratureRepository.class);
        List<ReportLiteratureRetrievalService.DocumentHit> hits = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            UUID documentId = UUID.randomUUID();
            hits.add(new ReportLiteratureRetrievalService.DocumentHit(
                    documentId, "Paper " + index, 10 - index,
                    List.of("mechanism"), List.of("chunk-" + index)));
            when(documentRepository.findById(documentId))
                    .thenReturn(Optional.of(document(documentId, RagDocumentStatus.COMPLETED)));
            when(literatureRepository.hasDocumentChunks(documentId)).thenReturn(true);
        }
        when(retrievalService.retrieve(any(), anyInt())).thenReturn(hits);
        DeepReportPipelineService service =
                service(properties, documentRepository, literatureRepository);
        ReflectionTestUtils.setField(service, "retrievalService", retrievalService);

        var selected = service.selectSupplementalLiterature(
                new SectionEvidenceMatrix(
                        Map.of("mechanisms", 0),
                        List.of("mechanisms"),
                        List.of("compound mechanism")),
                List.of(),
                new ArrayList<>());

        assertEquals(5, selected.size());
    }

    @Test
    void activityComparisonShouldRequireSameMethodMetricAndUnit() {
        DeepReportPipelineService service = service(new ReportProperties(), null, null);
        CompoundEvidenceRecord sameOne = evidence(
                UUID.randomUUID(), "Compound A", "MIC", "MIC = 8 mg/L");
        CompoundEvidenceRecord sameTwo = evidence(
                UUID.randomUUID(), "Compound B", "MIC", "MIC = 12 mg/L");
        CompoundEvidenceRecord differentUnit = evidence(
                UUID.randomUUID(), "Compound C", "MIC", "MIC = 10 ug/mL");

        var comparable = service.comparableActivityGroups(List.of(
                ranked(sameOne), ranked(sameTwo), ranked(differentUnit)));

        assertEquals(1, comparable.size());
        assertEquals("mg/l", comparable.getFirst().unit());
        assertEquals(2, comparable.getFirst().values().size());
    }

    @Test
    void failedDocumentAnalysisShouldContinueWithWarning() {
        ReportProperties properties = new ReportProperties();
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        ReportLiteratureRepository literatureRepository = mock(ReportLiteratureRepository.class);
        ReportFullDocumentAnalysisService analysisService =
                mock(ReportFullDocumentAnalysisService.class);
        ReportRepository reportRepository = mock(ReportRepository.class);
        DeepReportPipelineService service =
                service(properties, documentRepository, literatureRepository);
        ReflectionTestUtils.setField(service, "fullDocumentAnalysisService", analysisService);
        ReflectionTestUtils.setField(service, "reportRepository", reportRepository);
        UUID documentId = UUID.randomUUID();
        RagDocumentRecord document = document(documentId, RagDocumentStatus.COMPLETED);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        doThrow(new IllegalStateException("model timeout"))
                .when(analysisService).analyze(document);
        List<String> warnings = new ArrayList<>();

        var result = service.analyzeDocuments(
                UUID.randomUUID(),
                List.of(new SelectedLiterature(
                        documentId, document.title(), LiteratureSourceType.DIRECT,
                        1, 1.0, "test")),
                warnings);

        assertTrue(result.profiles().isEmpty());
        assertFalse(warnings.isEmpty());
        verify(literatureRepository).updateLiteratureStatus(
                any(), org.mockito.ArgumentMatchers.eq(documentId),
                org.mockito.ArgumentMatchers.eq(LiteratureAnalysisStatus.FAILED),
                org.mockito.ArgumentMatchers.contains("model timeout"));
    }

    private DeepReportPipelineService service(ReportProperties properties,
                                              RagDocumentRepository documentRepository,
                                              ReportLiteratureRepository literatureRepository) {
        DeepReportPipelineService service = new DeepReportPipelineService();
        ReflectionTestUtils.setField(service, "properties", properties);
        if (documentRepository != null) {
            ReflectionTestUtils.setField(service, "ragDocumentRepository", documentRepository);
        }
        if (literatureRepository != null) {
            ReflectionTestUtils.setField(service, "literatureRepository", literatureRepository);
        }
        return service;
    }

    private RankedEvidence ranked(CompoundEvidenceRecord evidence) {
        return new RankedEvidence(evidence, 0, 1, null);
    }

    private CompoundEvidenceRecord evidence(UUID documentId, String compound) {
        return evidence(documentId, compound, "MIC", "MIC = 8 mg/L");
    }

    private CompoundEvidenceRecord evidence(UUID documentId,
                                            String compound,
                                            String method,
                                            String activity) {
        UUID evidenceId = UUID.randomUUID();
        return new CompoundEvidenceRecord(
                evidenceId,
                UUID.randomUUID(),
                documentId,
                "Paper " + documentId,
                1,
                new CompoundEvidenceRow(
                        compound, compound, "small molecule", "化学合成", "",
                        "Phytophthora infestans", method, activity,
                        "", "", "", "", "", "", "", ""),
                "fingerprint-" + evidenceId,
                NameKind.PURE_COMPOUND,
                "compound:" + compound.toLowerCase(),
                0.8,
                ValidationStatus.VALID,
                List.of(),
                ReviewStatus.PENDING,
                null,
                true,
                List.of(),
                Instant.now(),
                Instant.now());
    }

    private RagDocumentRecord document(UUID id, RagDocumentStatus status) {
        return new RagDocumentRecord(
                id, null, null, "key", null, null, "hash-" + id,
                "Paper " + id, List.of(), List.of(), null, null, null, null,
                null, "paper.pdf", "data", status, Instant.now(), Instant.now());
    }
}
