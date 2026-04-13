package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReviewPipelineServiceTest {

    @Test
    void submitShouldUseCanonicalQuestionInDownstreamStages() {
        ReviewPipelineService service = new ReviewPipelineService();
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        QueryAnalyzerService queryAnalyzerService = mock(QueryAnalyzerService.class);
        QueryExpansionService queryExpansionService = mock(QueryExpansionService.class);
        HighRecallRetrievalService highRecallRetrievalService = mock(HighRecallRetrievalService.class);
        ReviewRerankerService reviewRerankerService = mock(ReviewRerankerService.class);
        EvidenceExtractionService evidenceExtractionService = mock(EvidenceExtractionService.class);
        EvidenceFusionService evidenceFusionService = mock(EvidenceFusionService.class);
        ReportGeneratorService reportGeneratorService = mock(ReportGeneratorService.class);

        ReflectionTestUtils.setField(service, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(service, "queryAnalyzerService", queryAnalyzerService);
        ReflectionTestUtils.setField(service, "queryExpansionService", queryExpansionService);
        ReflectionTestUtils.setField(service, "highRecallRetrievalService", highRecallRetrievalService);
        ReflectionTestUtils.setField(service, "reviewRerankerService", reviewRerankerService);
        ReflectionTestUtils.setField(service, "evidenceExtractionService", evidenceExtractionService);
        ReflectionTestUtils.setField(service, "evidenceFusionService", evidenceFusionService);
        ReflectionTestUtils.setField(service, "reportGeneratorService", reportGeneratorService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "reviewTaskExecutor", (TaskExecutor) Runnable::run);

        String rawPrompt = """
                疫霉属植物病原体基因功能系统性回顾
                你的任务是提取生长、生殖和致病性相关基因，并输出 JSON 字段。
                """;
        String canonicalQuestion = "基于提供的文献，系统综述疫霉属植物病原体中参与生长、生殖、致病性的基因及其功能证据，并比较不同过程之间的共性与差异。";
        QueryAnalysis analysis = new QueryAnalysis(
                canonicalQuestion,
                List.of("哪些基因参与生长过程，这些基因的功能证据是什么？"),
                List.of("Phytophthora"),
                List.of("growth", "reproduction", "pathogenicity")
        );

        UUID documentId = UUID.randomUUID();
        RetrievedChunk candidate = new RetrievedChunk(
                "chunk-1", documentId, "Paper A", "chunk text", "Section 1", 0.9, "BM25");
        List<RetrievedChunk> candidates = List.of(candidate);
        List<ExtractedEvidence> evidence = List.of();
        List<FusedEvidenceGroup> groups = List.of(
                new FusedEvidenceGroup(
                        "哪些基因参与生长过程，这些基因的功能证据是什么？",
                        "summary",
                        List.of(),
                        0,
                        0,
                        List.of()
                )
        );

        when(queryAnalyzerService.analyze(rawPrompt)).thenReturn(analysis);
        when(queryExpansionService.expand(analysis)).thenReturn(List.of(canonicalQuestion));
        when(highRecallRetrievalService.retrieve(List.of(canonicalQuestion))).thenReturn(candidates);
        when(reviewRerankerService.rerank(canonicalQuestion, candidates)).thenReturn(candidates);
        when(reviewRerankerService.getJudgmentMap(canonicalQuestion, candidates)).thenReturn(
                Map.of("chunk-1", new ChunkRelevanceJudgment("chunk-1", Relevance.HIGH, "direct evidence"))
        );
        when(evidenceExtractionService.extract(canonicalQuestion, analysis.subQuestions(), candidates)).thenReturn(evidence);
        when(evidenceFusionService.fuse(analysis.subQuestions(), evidence)).thenReturn(groups);
        when(reportGeneratorService.generateReport(canonicalQuestion, groups)).thenReturn("report");

        service.submit("user-1", rawPrompt);

        verify(reviewRepository).insertTask(any(UUID.class), eq("user-1"), eq(rawPrompt));
        verify(reviewRerankerService).rerank(canonicalQuestion, candidates);
        verify(reviewRerankerService).getJudgmentMap(canonicalQuestion, candidates);
        verify(evidenceExtractionService).extract(canonicalQuestion, analysis.subQuestions(), candidates);
        verify(reportGeneratorService).generateReport(canonicalQuestion, groups);
        verify(reportGeneratorService, never()).generateReport(eq(rawPrompt), any());
    }
}
