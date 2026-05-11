package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ReviewPipelineServiceTest {

    @Test
    void submitShouldPrepareDocumentCandidatesAndConfirmShouldAnalyzeSelectedPapers() {
        ReviewPipelineService service = new ReviewPipelineService();
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        QueryAnalyzerService queryAnalyzerService = mock(QueryAnalyzerService.class);
        QueryExpansionService queryExpansionService = mock(QueryExpansionService.class);
        HighRecallRetrievalService highRecallRetrievalService = mock(HighRecallRetrievalService.class);
        DocumentPromotionService documentPromotionService = mock(DocumentPromotionService.class);
        ReviewRerankerService reviewRerankerService = mock(ReviewRerankerService.class);
        DocumentKnowledgeEnrichmentService documentKnowledgeEnrichmentService = mock(DocumentKnowledgeEnrichmentService.class);
        EvidenceExtractionService evidenceExtractionService = mock(EvidenceExtractionService.class);
        EvidenceFusionService evidenceFusionService = mock(EvidenceFusionService.class);
        ReportGeneratorService reportGeneratorService = mock(ReportGeneratorService.class);
        PaperEvidenceTableSynthesisService paperEvidenceTableSynthesisService = mock(PaperEvidenceTableSynthesisService.class);

        ReflectionTestUtils.setField(service, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(service, "queryAnalyzerService", queryAnalyzerService);
        ReflectionTestUtils.setField(service, "queryExpansionService", queryExpansionService);
        ReflectionTestUtils.setField(service, "highRecallRetrievalService", highRecallRetrievalService);
        ReflectionTestUtils.setField(service, "documentPromotionService", documentPromotionService);
        ReflectionTestUtils.setField(service, "reviewRerankerService", reviewRerankerService);
        ReflectionTestUtils.setField(service, "documentKnowledgeEnrichmentService", documentKnowledgeEnrichmentService);
        ReflectionTestUtils.setField(service, "evidenceExtractionService", evidenceExtractionService);
        ReflectionTestUtils.setField(service, "evidenceFusionService", evidenceFusionService);
        ReflectionTestUtils.setField(service, "reportGeneratorService", reportGeneratorService);
        ReflectionTestUtils.setField(service, "paperEvidenceTableSynthesisService", paperEvidenceTableSynthesisService);
        ReviewProperties reviewProperties = new ReviewProperties();
        reviewProperties.getRetrieval().setEnableQuantitativeAnchor(false);
        reviewProperties.getSynthesis().setEnableCompoundSynthesizer(false);
        ReflectionTestUtils.setField(service, "reviewProperties", reviewProperties);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "reviewTaskExecutor", (TaskExecutor) Runnable::run);

        String rawPrompt = """
                你是一个植物病原体抽取专家。
                请整理所有与疫霉菌生长、生殖、致病相关的基因，并输出字段 gene_name / evidence_text。
                """;
        String canonicalQuestion = "基于提供的文献，系统梳理疫霉菌中参与生长、生殖与致病过程的基因/蛋白、其证据强度，以及不同论文报告的创新点与机制差异。";
        QueryAnalysis analysis = new QueryAnalysis(
                canonicalQuestion,
                List.of("哪些基因/蛋白与疫霉菌的生长过程相关，证据强度分别如何？"),
                List.of("Phytophthora"),
                List.of("growth", "reproduction", "pathogenicity")
        );

        UUID documentId = UUID.randomUUID();
        RetrievedChunk candidate = new RetrievedChunk(
                "chunk-1", documentId, "Paper A", "chunk text", "Section 1", 0.9, "BM25");
        RetrievedChunk fullChunk = new RetrievedChunk(
                "chunk-2", documentId, "Paper A", "full paper chunk", "Section 2", 0.0, "DOC_ALL");
        List<RetrievedChunk> candidates = List.of(candidate);
        List<RetrievedChunk> allPaperChunks = List.of(candidate, fullChunk);
        when(queryAnalyzerService.analyze(rawPrompt)).thenReturn(analysis);
        when(queryExpansionService.expand(analysis)).thenReturn(List.of(canonicalQuestion));
        when(highRecallRetrievalService.retrieveSeedChunks(List.of(canonicalQuestion))).thenReturn(candidates);

        ReviewTaskAcceptedResponse accepted = service.submit("user-1", rawPrompt);
        assertEquals(ReviewTaskStatus.QUEUED, accepted.status());

        verify(reviewRepository).insertTask(eq(accepted.taskId()), eq("user-1"), eq(rawPrompt), eq("antimicrobial_compound"));
        verify(reviewRepository).insertDocumentCandidates(eq(accepted.taskId()), argThat(documents ->
                documents.size() == 1 && documents.get(0).documentId().equals(documentId) && documents.get(0).selected()));
        verify(reviewRepository).updateTaskStatus(accepted.taskId(), ReviewTaskStatus.AWAITING_USER, ReviewStage.RERANKING);
        verify(reviewRepository, never()).findAllChunksByDocumentId(any());
        verify(reportGeneratorService, never()).generateReport(any(), any(), any(), any(), any());

        ReviewPaperEvidenceTable paperTable = new ReviewPaperEvidenceTable(
                accepted.taskId(), documentId, "Paper A", canonicalQuestion, "paper summary",
                List.of("Finding", "Evidence"), List.of(List.of("finding", "evidence")),
                List.of("chunk-1", "chunk-2"), 2, 0.8, List.of(), null);
        ReviewTaskRecord task = new ReviewTaskRecord(
                accepted.taskId(),
                "user-1",
                rawPrompt,
                "antimicrobial_compound",
                documentId,
                "Paper A",
                ReviewTaskStatus.AWAITING_USER,
                ReviewStage.RERANKING,
                analysis,
                null,
                1,
                1,
                0,
                new ReviewTaskMetrics(1L, 1L, 0L, null, null, null, null),
                null,
                null,
                null,
                null,
                null
        );
        ReviewDocumentCandidate documentCandidate = new ReviewDocumentCandidate(
                null, accepted.taskId(), documentId, "Paper A", 1, List.of("chunk-1"),
                0.9, 0.9, 0.0, 0.0, 0.92, 0.92, Relevance.HIGH,
                "selected", null, List.of(), List.of(), true, true
        );
        ReviewCandidate storedCandidate = new ReviewCandidate(
                null, accepted.taskId(), "chunk-1", documentId, "Paper A", 0.9, "BM25",
                "Section 1", "SEED", null, null, null, 0.9, Relevance.HIGH,
                "selected", true, "chunk text"
        );

        when(reviewRepository.findTask(accepted.taskId())).thenReturn(java.util.Optional.of(task));
        when(reviewRepository.findDocumentCandidates(accepted.taskId())).thenReturn(List.of(documentCandidate));
        when(reviewRepository.findAllCandidates(accepted.taskId())).thenReturn(List.of(storedCandidate));
        when(reviewRepository.findAllChunksByDocumentId(documentId)).thenReturn(allPaperChunks);
        when(paperEvidenceTableSynthesisService.synthesizeBestTable(
                any(UUID.class), eq(analysis), eq(canonicalQuestion), eq(documentId), eq("Paper A"),
                eq(allPaperChunks), eq(List.of()), isNull(), eq("antimicrobial_compound"), eq(candidates)))
                .thenReturn(paperTable);
        when(reportGeneratorService.generateReport(eq(analysis), isNull(), isNull(), isNull(), eq(List.of(paperTable)))).thenReturn("report");

        service.confirmDocuments(accepted.taskId(), List.of(documentId));

        verify(reviewRepository).updateDocumentSelection(accepted.taskId(), List.of(documentId));
        verify(reviewRepository).findAllChunksByDocumentId(documentId);
        verify(documentPromotionService, never()).promote(any(), any(), any());
        verify(reviewRerankerService, never()).rerank(any(), any(), any());
        verify(reviewRerankerService, never()).getJudgmentMap(any(), any());
        verify(documentKnowledgeEnrichmentService, never()).enrich(any(UUID.class), any(), any());
        verify(evidenceExtractionService, never()).extract(any(), any(), any(), any());
        verify(evidenceFusionService, never()).fuse(any(), any());
        verify(paperEvidenceTableSynthesisService).synthesizeBestTable(
                any(UUID.class), eq(analysis), eq(canonicalQuestion), eq(documentId), eq("Paper A"),
                eq(allPaperChunks), eq(List.of()), isNull(), eq("antimicrobial_compound"), eq(candidates));
        verify(reviewRepository).upsertPaperEvidenceTable(paperTable);
        verify(reportGeneratorService).generateReport(eq(analysis), isNull(), isNull(), isNull(), eq(List.of(paperTable)));
        verify(reportGeneratorService, never()).generateReport(eq(rawPrompt), any());
    }
}
