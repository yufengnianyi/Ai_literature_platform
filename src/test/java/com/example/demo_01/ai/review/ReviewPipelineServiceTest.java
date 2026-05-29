package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewPipelineServiceTest {

    @Test
    void reviewPropertiesShouldDefaultAutoSelectMinSeedScoreToPointSix() {
        ReviewProperties properties = new ReviewProperties();

        assertEquals(0.60, properties.getRetrieval().getAutoSelectMinSeedScore());
    }

    @Test
    void submitShouldPauseForUserSelectionAfterCandidateDiscovery() {
        ReviewPipelineService service = newService();
        ReviewRepository reviewRepository = repository(service);
        QueryAnalyzerService queryAnalyzerService = field(service, "queryAnalyzerService");
        QueryExpansionService queryExpansionService = field(service, "queryExpansionService");
        HighRecallRetrievalService highRecallRetrievalService = field(service, "highRecallRetrievalService");
        ReportGeneratorService reportGeneratorService = field(service, "reportGeneratorService");
        PaperEvidenceTableSynthesisService paperEvidenceTableSynthesisService =
                field(service, "paperEvidenceTableSynthesisService");

        String rawPrompt = "Summarize antimicrobial compounds in current literature.";
        String canonicalQuestion = "Summarize antimicrobial compounds.";
        QueryAnalysis analysis = new QueryAnalysis(
                canonicalQuestion,
                List.of("Which compounds have antimicrobial activity?"),
                List.of("Phytophthora"),
                List.of("growth", "pathogenicity")
        );

        UUID documentId = UUID.randomUUID();
        RetrievedChunk seedChunk = new RetrievedChunk(
                "chunk-1", documentId, "Paper A", "chunk text", "Section 1", 0.9, "BM25");

        when(queryAnalyzerService.analyze(rawPrompt)).thenReturn(analysis);
        when(queryExpansionService.expand(analysis)).thenReturn(List.of(canonicalQuestion));
        when(highRecallRetrievalService.retrieveSeedChunks(eq(List.of(canonicalQuestion)), any(ReviewLoadSettings.class)))
                .thenReturn(List.of(seedChunk));

        ReviewTaskAcceptedResponse accepted = service.submit("user-1", rawPrompt);

        assertEquals(ReviewTaskStatus.QUEUED, accepted.status());
        verify(reviewRepository).insertTask(eq(accepted.taskId()), eq("user-1"), eq(rawPrompt), eq("antimicrobial_compound"));
        verify(reviewRepository).insertDocumentCandidates(eq(accepted.taskId()), argThat(documents ->
                documents.size() == 1 && documents.get(0).documentId().equals(documentId) && documents.get(0).selected()));
        verify(reviewRepository).updateTaskStatus(accepted.taskId(), ReviewTaskStatus.AWAITING_USER, ReviewStage.RERANKING);
        verify(reviewRepository, never()).updateDocumentSelection(any(UUID.class), anyList(), anyString());
        verify(reviewRepository, never()).findAllChunksByDocumentId(any());
        verify(paperEvidenceTableSynthesisService, never()).synthesizeBestTable(
                any(UUID.class), any(), anyString(), any(), anyString(), anyList(), anyList(), any(), anyString(), anyList());
        verify(reportGeneratorService, never()).generateReport(any(), any(), any(), any(), anyList());
        verify(reviewRepository, never()).completeTask(any());
    }

    @Test
    void submitShouldAwaitUserSelectionWhenNoDocumentSeedScoreMeetsAutoSelectionThreshold() {
        ReviewPipelineService service = newService();
        ReviewRepository reviewRepository = repository(service);
        QueryAnalyzerService queryAnalyzerService = field(service, "queryAnalyzerService");
        QueryExpansionService queryExpansionService = field(service, "queryExpansionService");
        HighRecallRetrievalService highRecallRetrievalService = field(service, "highRecallRetrievalService");

        String rawPrompt = "Summarize antimicrobial compounds in current literature.";
        String canonicalQuestion = "Summarize antimicrobial compounds.";
        QueryAnalysis analysis = new QueryAnalysis(canonicalQuestion, List.of(), List.of(), List.of());
        UUID documentId = UUID.randomUUID();
        RetrievedChunk thresholdChunk = new RetrievedChunk(
                "chunk-1", documentId, "Paper A", "chunk text", "Section 1", 0.59, "BM25");

        when(queryAnalyzerService.analyze(rawPrompt)).thenReturn(analysis);
        when(queryExpansionService.expand(analysis)).thenReturn(List.of(canonicalQuestion));
        when(highRecallRetrievalService.retrieveSeedChunks(eq(List.of(canonicalQuestion)), any(ReviewLoadSettings.class)))
                .thenReturn(List.of(thresholdChunk));

        ReviewTaskAcceptedResponse accepted = service.submit("user-1", rawPrompt);

        assertEquals(ReviewTaskStatus.QUEUED, accepted.status());
        verify(reviewRepository).insertDocumentCandidates(eq(accepted.taskId()), argThat(documents ->
                documents.size() == 1 && documents.get(0).documentId().equals(documentId) && !documents.get(0).selected()));
        verify(reviewRepository).updateTaskStatus(accepted.taskId(), ReviewTaskStatus.AWAITING_USER, ReviewStage.RERANKING);
        verify(reviewRepository, never()).failTask(any(), anyString(), anyString());
        verify(reviewRepository, never()).updateDocumentSelection(any(UUID.class), anyList(), anyString());
        verify(reviewRepository, never()).findAllChunksByDocumentId(any());
        verify(reviewRepository, never()).completeTask(any());
    }

    @Test
    void submitShouldApplyUserLoadSettingsWhenAutoSelectingDocuments() {
        ReviewPipelineService service = newService();
        ReviewRepository reviewRepository = repository(service);
        QueryAnalyzerService queryAnalyzerService = field(service, "queryAnalyzerService");
        QueryExpansionService queryExpansionService = field(service, "queryExpansionService");
        HighRecallRetrievalService highRecallRetrievalService = field(service, "highRecallRetrievalService");

        String rawPrompt = "Summarize antimicrobial compounds in current literature.";
        QueryAnalysis analysis = new QueryAnalysis(rawPrompt, List.of(), List.of(), List.of());
        UUID firstDocument = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID();
        RetrievedChunk firstSeed = new RetrievedChunk("chunk-1", firstDocument, "Paper A", "chunk text", "Section 1", 0.91, "BM25");
        RetrievedChunk secondSeed = new RetrievedChunk("chunk-2", secondDocument, "Paper B", "chunk text", "Section 1", 0.89, "BM25");

        when(queryAnalyzerService.analyze(rawPrompt)).thenReturn(analysis);
        when(queryExpansionService.expand(analysis)).thenReturn(List.of(rawPrompt));
        when(highRecallRetrievalService.retrieveSeedChunks(eq(List.of(rawPrompt)), any(ReviewLoadSettings.class)))
                .thenReturn(List.of(firstSeed, secondSeed));

        ReviewTaskAcceptedResponse accepted = service.submit(
                "user-1", rawPrompt, "antimicrobial_compound",
                new ReviewLoadSettings(0.85, 1, 12, 7, 0.42, 9, 111));

        ArgumentCaptor<ReviewLoadSettings> settingsCaptor = ArgumentCaptor.forClass(ReviewLoadSettings.class);
        verify(highRecallRetrievalService).retrieveSeedChunks(eq(List.of(rawPrompt)), settingsCaptor.capture());
        ReviewLoadSettings settings = settingsCaptor.getValue();
        assertEquals(0.85, settings.minScore(), 0.0001);
        assertEquals(1, settings.maxDocuments());
        assertEquals(12, settings.seedFtsMaxResults());
        assertEquals(7, settings.seedDenseMaxResults());
        assertEquals(0.42, settings.seedDenseMinScore(), 0.0001);
        assertEquals(9, settings.seedBm25MaxResults());
        assertEquals(111, settings.maxCandidates());
        verify(reviewRepository).insertDocumentCandidates(eq(accepted.taskId()), argThat(documents ->
                documents.size() == 2
                        && documents.stream().filter(ReviewDocumentCandidate::selected).count() == 1
                        && documents.stream().filter(ReviewDocumentCandidate::selected).findFirst()
                        .map(ReviewDocumentCandidate::documentId).orElseThrow().equals(firstDocument)));
        verify(reviewRepository).updateTaskStatus(accepted.taskId(), ReviewTaskStatus.AWAITING_USER, ReviewStage.RERANKING);
        verify(reviewRepository, never()).findAllChunksByDocumentId(secondDocument);
    }

    @Test
    void confirmDocumentsShouldContinueReviewGeneration() {
        ReviewPipelineService service = newService();
        ReviewRepository reviewRepository = repository(service);
        PaperEvidenceTableSynthesisService paperEvidenceTableSynthesisService =
                field(service, "paperEvidenceTableSynthesisService");
        ReportGeneratorService reportGeneratorService = field(service, "reportGeneratorService");

        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String question = "Summarize antimicrobial compounds.";
        QueryAnalysis analysis = new QueryAnalysis(question, List.of(), List.of(), List.of());
        ReviewTaskRecord task = task(taskId, "user-1", question, analysis);
        RetrievedChunk seedChunk = new RetrievedChunk(
                "chunk-1", documentId, "Paper A", "seed text", "Section 1", 0.91, "BM25");
        RetrievedChunk firstFullChunk = new RetrievedChunk(
                "chunk-000", documentId, "Paper A", "intro text", "Section 1", 0.0, "DOC_ALL");
        RetrievedChunk secondFullChunk = new RetrievedChunk(
                "chunk-001", documentId, "Paper A", "methods text", "Section 2", 0.0, "DOC_ALL");
        ReviewDocumentCandidate documentCandidate = new ReviewDocumentCandidate(
                null, taskId, documentId, "Paper A", 1, List.of("chunk-1"),
                0.91, 0.91, 0.0, 0.0, 0.93, 0.93, Relevance.HIGH,
                "selected", null, List.of(), List.of(), true, true
        );
        ReviewCandidate storedCandidate = new ReviewCandidate(
                null, taskId, "chunk-1", documentId, "Paper A", 0.91, "BM25",
                "Section 1", "SEED", null, null, null, 0.91, Relevance.HIGH,
                "selected", true, "seed text"
        );
        List<RetrievedChunk> allPaperChunks = List.of(firstFullChunk, secondFullChunk);
        ReviewPaperEvidenceTable paperTable = new ReviewPaperEvidenceTable(
                UUID.randomUUID(), documentId, "Paper A", question, "paper summary",
                List.of("Finding", "Evidence"), List.of(List.of("finding", "evidence")),
                List.of("chunk-000", "chunk-001"), 2, 0.8, List.of(), null);

        when(reviewRepository.findTask(taskId)).thenReturn(Optional.of(task));
        when(reviewRepository.findDocumentCandidates(taskId)).thenReturn(List.of(documentCandidate));
        when(reviewRepository.findAllCandidates(taskId)).thenReturn(List.of(storedCandidate));
        when(reviewRepository.findAllChunksByDocumentId(documentId)).thenReturn(allPaperChunks);
        when(paperEvidenceTableSynthesisService.synthesizeBestTable(
                eq(taskId), eq(analysis), eq(question), eq(documentId), eq("Paper A"),
                eq(allPaperChunks), eq(List.of()), isNull(), eq("antimicrobial_compound"), eq(List.of(seedChunk))))
                .thenReturn(paperTable);
        when(reportGeneratorService.generateReport(eq(analysis), isNull(), isNull(), isNull(), eq(List.of(paperTable))))
                .thenReturn("report");

        ReviewTaskAcceptedResponse accepted = service.confirmDocuments(taskId, List.of(documentId));

        assertEquals(ReviewTaskStatus.QUEUED, accepted.status());
        verify(reviewRepository).updateDocumentSelection(taskId, List.of(documentId));
        verify(reviewRepository).findAllChunksByDocumentId(documentId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RetrievedChunk>> allChunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(paperEvidenceTableSynthesisService).synthesizeBestTable(
                eq(taskId), eq(analysis), eq(question), eq(documentId), eq("Paper A"),
                allChunksCaptor.capture(), eq(List.of()), isNull(), eq("antimicrobial_compound"), eq(List.of(seedChunk)));
        assertEquals(List.of("chunk-000", "chunk-001"),
                allChunksCaptor.getValue().stream().map(RetrievedChunk::chunkId).toList());
        verify(reviewRepository).upsertPaperEvidenceTable(paperTable);
        verify(reportGeneratorService).generateReport(eq(analysis), isNull(), isNull(), isNull(), eq(List.of(paperTable)));
        verify(reviewRepository).completeTask(taskId);
    }

    private ReviewPipelineService newService() {
        ReviewPipelineService service = new ReviewPipelineService();
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        QueryAnalyzerService queryAnalyzerService = mock(QueryAnalyzerService.class);
        QueryExpansionService queryExpansionService = mock(QueryExpansionService.class);
        HighRecallRetrievalService highRecallRetrievalService = mock(HighRecallRetrievalService.class);
        ReportGeneratorService reportGeneratorService = mock(ReportGeneratorService.class);
        PaperEvidenceTableSynthesisService paperEvidenceTableSynthesisService = mock(PaperEvidenceTableSynthesisService.class);

        ReflectionTestUtils.setField(service, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(service, "queryAnalyzerService", queryAnalyzerService);
        ReflectionTestUtils.setField(service, "queryExpansionService", queryExpansionService);
        ReflectionTestUtils.setField(service, "highRecallRetrievalService", highRecallRetrievalService);
        ReflectionTestUtils.setField(service, "reportGeneratorService", reportGeneratorService);
        ReflectionTestUtils.setField(service, "paperEvidenceTableSynthesisService", paperEvidenceTableSynthesisService);
        ReflectionTestUtils.setField(service, "reviewProperties", new ReviewProperties());
        ReflectionTestUtils.setField(service, "reviewTaskExecutor", (TaskExecutor) Runnable::run);
        when(reviewRepository.findTask(any(UUID.class))).thenReturn(Optional.empty());
        return service;
    }

    private ReviewRepository repository(ReviewPipelineService service) {
        return field(service, "reviewRepository");
    }

    private ReviewTaskRecord task(UUID taskId, String userId, String question, QueryAnalysis analysis) {
        Instant now = Instant.now();
        return new ReviewTaskRecord(
                taskId,
                userId,
                question,
                "antimicrobial_compound",
                null,
                null,
                ReviewTaskStatus.AWAITING_USER,
                ReviewStage.RERANKING,
                analysis,
                null,
                1,
                1,
                0,
                null,
                null,
                null,
                now,
                now,
                null
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T field(ReviewPipelineService service, String name) {
        return (T) ReflectionTestUtils.getField(service, name);
    }
}
