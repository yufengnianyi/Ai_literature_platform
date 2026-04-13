package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ReviewPipelineService {

    @Resource
    private ReviewProperties reviewProperties;

    @Resource
    private ReviewRepository reviewRepository;

    @Resource
    private QueryAnalyzerService queryAnalyzerService;

    @Resource
    private QueryExpansionService queryExpansionService;

    @Resource
    private HighRecallRetrievalService highRecallRetrievalService;

    @Resource
    private ReviewRerankerService reviewRerankerService;

    @Resource
    private EvidenceExtractionService evidenceExtractionService;

    @Resource
    private EvidenceFusionService evidenceFusionService;

    @Resource
    private ReportGeneratorService reportGeneratorService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    public ReviewTaskAcceptedResponse submit(String userId, String question) {
        UUID taskId = UUID.randomUUID();
        reviewRepository.insertTask(taskId, userId, question);
        reviewTaskExecutor.execute(() -> executePipeline(taskId, question));
        return new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.QUEUED);
    }

    public ReviewTaskAcceptedResponse retry(UUID taskId) {
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (task.status() == ReviewTaskStatus.RUNNING) {
            throw new IllegalStateException("Task is still running, cannot retry");
        }

        reviewRepository.resetTaskForRetry(taskId);
        reviewTaskExecutor.execute(() -> executePipeline(taskId, task.question()));
        return new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.QUEUED);
    }

    public Flux<String> submitStreaming(String userId, UUID taskId, String question) {
        reviewRepository.insertTask(taskId, userId, question);

        return Flux.create(sink -> {
            reviewTaskExecutor.execute(() -> {
                try {
                    PipelineContext ctx = runPreReportStages(taskId, question);
                    streamReportPhase(taskId, ctx, sink);
                } catch (Exception e) {
                    failTask(taskId, "PIPELINE_ERROR", e);
                    sink.error(e);
                }
            });
        });
    }

    public Flux<String> submitStreamingWithSelections(String userId, UUID taskId,
                                                      ReviewGenerateRequest request) {
        reviewRepository.insertTask(taskId, userId, request.question());

        return Flux.create(sink -> {
            reviewTaskExecutor.execute(() -> {
                try {
                    QueryAnalysis filteredAnalysis = request.toFilteredAnalysis();
                    PipelineContext ctx = runPreReportStagesWithAnalysis(taskId, request.question(), filteredAnalysis);
                    streamReportPhase(taskId, ctx, sink);
                } catch (Exception e) {
                    failTask(taskId, "PIPELINE_ERROR", e);
                    sink.error(e);
                }
            });
        });
    }

    /**
     * Segment A: runs Stages 2-4 (Query Expansion + Retrieval + Reranking),
     * then pauses at AWAITING_USER for candidate review.
     */
    public void executeRetrievalSegment(UUID taskId, String question, QueryAnalysis analysis) {
        reviewTaskExecutor.execute(() -> {
            try {
                reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, ReviewStage.QUERY_EXPANSION);
                reviewRepository.updateQueryAnalysis(taskId, analysis);

                // Stage 2: Query expansion
                updateStage(taskId, ReviewStage.QUERY_EXPANSION);
                List<String> expandedQueries = queryExpansionService.expand(analysis);

                // Stage 3: High-recall retrieval
                updateStage(taskId, ReviewStage.RETRIEVAL);
                Instant retrievalStart = Instant.now();
                List<RetrievedChunk> candidates = highRecallRetrievalService.retrieve(expandedQueries);
                long retrievalMs = Duration.between(retrievalStart, Instant.now()).toMillis();
                log.info("Task {}: Retrieved {} candidates in {}ms", taskId, candidates.size(), retrievalMs);

                List<ReviewCandidate> candidateRecords = candidates.stream()
                        .map(c -> new ReviewCandidate(null, taskId, c.chunkId(), c.documentId(),
                                c.documentTitle(), c.score(), c.source(), null, null, null, false, c.text()))
                        .toList();
                reviewRepository.insertCandidates(taskId, candidateRecords);

                // Stage 4: Reranking
                updateStage(taskId, ReviewStage.RERANKING);
                Instant rerankStart = Instant.now();
                List<RetrievedChunk> included = reviewRerankerService.rerank(
                        analysis.mainQuestion() != null ? analysis.mainQuestion() : "",
                        candidates);
                long rerankMs = Duration.between(rerankStart, Instant.now()).toMillis();
                log.info("Task {}: Reranked to {} included chunks in {}ms", taskId, included.size(), rerankMs);

                Map<String, ChunkRelevanceJudgment> judgments = reviewRerankerService.getJudgmentMap(
                        analysis.mainQuestion() != null ? analysis.mainQuestion() : "", included);
                for (RetrievedChunk c : candidates) {
                    ChunkRelevanceJudgment j = judgments.get(c.chunkId());
                    boolean isIncluded = included.stream().anyMatch(ic -> ic.chunkId().equals(c.chunkId()));
                    reviewRepository.updateCandidateReranking(taskId, c.chunkId(),
                            c.score(),
                            j != null ? j.relevance() : Relevance.LOW,
                            j != null ? j.reason() : null,
                            isIncluded);
                }

                reviewRepository.updateTaskCounts(taskId, candidates.size(), 0);

                // Pause for user review
                reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.AWAITING_USER, ReviewStage.RERANKING);
                log.info("Task {}: Retrieval segment complete, awaiting user candidate review", taskId);
            } catch (Exception e) {
                failTask(taskId, "RETRIEVAL_SEGMENT_ERROR", e);
            }
        });
    }

    /**
     * Segment B: applies user candidate decisions, runs Stages 5-6 (Evidence Extraction + Fusion),
     * then pauses at AWAITING_USER for evidence review.
     */
    public void executeEvidenceSegment(UUID taskId, CandidateReviewRequest userReview) {
        reviewTaskExecutor.execute(() -> {
            try {
                reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, ReviewStage.EVIDENCE_EXTRACTION);

                // Apply user decisions
                if (userReview.excludedChunkIds() != null && !userReview.excludedChunkIds().isEmpty()) {
                    reviewRepository.updateCandidateUserExcluded(taskId, userReview.excludedChunkIds(), true);
                }
                if (userReview.prioritizedChunkIds() != null && !userReview.prioritizedChunkIds().isEmpty()) {
                    reviewRepository.updateCandidateUserPrioritized(taskId, userReview.prioritizedChunkIds(), true);
                }

                // Get user-approved candidates
                List<ReviewCandidate> approvedCandidates = reviewRepository.findUserApprovedCandidates(taskId);
                List<RetrievedChunk> included = approvedCandidates.stream()
                        .map(c -> new RetrievedChunk(c.chunkId(), c.documentId(), c.documentTitle(),
                                c.chunkText(), null, c.retrievalScore(), c.retrievalSource()))
                        .toList();

                // Retrieve QueryAnalysis from DB
                ReviewTaskRecord task = reviewRepository.findTask(taskId)
                        .orElseThrow(() -> new IllegalStateException("Task not found: " + taskId));
                QueryAnalysis analysis = task.queryAnalysis();
                String canonicalQuestion = analysis != null && analysis.mainQuestion() != null
                        ? analysis.mainQuestion() : task.question();
                List<String> subQuestions = analysis != null ? analysis.subQuestions() : List.of();

                // Stage 5: Evidence extraction
                updateStage(taskId, ReviewStage.EVIDENCE_EXTRACTION);
                Instant extractionStart = Instant.now();
                List<ExtractedEvidence> evidence = evidenceExtractionService.extract(
                        canonicalQuestion, subQuestions, included);
                long extractionMs = Duration.between(extractionStart, Instant.now()).toMillis();
                log.info("Task {}: Extracted {} evidence items in {}ms", taskId, evidence.size(), extractionMs);

                for (ExtractedEvidence e : evidence) {
                    reviewRepository.insertEvidence(taskId, e);
                }
                reviewRepository.updateTaskCounts(taskId, approvedCandidates.size(), evidence.size());

                // Stage 6: Evidence fusion
                updateStage(taskId, ReviewStage.EVIDENCE_FUSION);
                Instant fusionStart = Instant.now();
                List<FusedEvidenceGroup> groups = evidenceFusionService.fuse(subQuestions, evidence);
                long fusionMs = Duration.between(fusionStart, Instant.now()).toMillis();
                log.info("Task {}: Fused into {} groups in {}ms", taskId, groups.size(), fusionMs);

                // Pause for user review
                reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.AWAITING_USER, ReviewStage.EVIDENCE_FUSION);
                log.info("Task {}: Evidence segment complete, awaiting user evidence review", taskId);
            } catch (Exception e) {
                failTask(taskId, "EVIDENCE_SEGMENT_ERROR", e);
            }
        });
    }

    /**
     * Segment C: applies user evidence decisions and generates the report via SSE streaming.
     */
    public Flux<String> executeReportSegment(UUID taskId, EvidenceReviewRequest userReview) {
        return Flux.create(sink -> {
            reviewTaskExecutor.execute(() -> {
                try {
                    reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, ReviewStage.REPORT_GENERATION);

                    // Apply user decisions
                    if (userReview.excludedEvidenceIds() != null && !userReview.excludedEvidenceIds().isEmpty()) {
                        reviewRepository.updateEvidenceUserExcluded(taskId, userReview.excludedEvidenceIds(), true);
                    }
                    if (userReview.focusSubQuestions() != null || userReview.userGuidance() != null) {
                        reviewRepository.updateTaskUserGuidance(taskId,
                                userReview.userGuidance(),
                                userReview.focusSubQuestions());
                    }

                    ReviewTaskRecord task = reviewRepository.findTask(taskId)
                            .orElseThrow(() -> new IllegalStateException("Task not found: " + taskId));
                    QueryAnalysis analysis = task.queryAnalysis();
                    String canonicalQuestion = analysis != null && analysis.mainQuestion() != null
                            ? analysis.mainQuestion() : task.question();
                    List<String> subQuestions = analysis != null ? analysis.subQuestions() : List.of();

                    // Re-fuse with user-approved evidence only
                    List<ReviewEvidenceRecord> approvedRecords = reviewRepository.findUserApprovedEvidence(taskId);
                    List<ExtractedEvidence> approvedEvidence = approvedRecords.stream()
                            .map(r -> new ExtractedEvidence(
                                    r.chunkId(), r.documentId() != null ? r.documentId().toString() : null,
                                    null, r.claim(), r.finding(), r.methodology(),
                                    r.entities(), r.evidenceType(), r.confidence(),
                                    r.originalText(), r.subQuestion()))
                            .toList();

                    List<FusedEvidenceGroup> groups = evidenceFusionService.fuse(subQuestions, approvedEvidence);

                    // Reorder: focus sub-questions first
                    if (userReview.focusSubQuestions() != null && !userReview.focusSubQuestions().isEmpty()) {
                        List<FusedEvidenceGroup> reordered = new java.util.ArrayList<>();
                        List<FusedEvidenceGroup> rest = new java.util.ArrayList<>();
                        for (FusedEvidenceGroup g : groups) {
                            if (userReview.focusSubQuestions().contains(g.subQuestion())) {
                                reordered.add(g);
                            } else {
                                rest.add(g);
                            }
                        }
                        reordered.addAll(rest);
                        groups = reordered;
                    }

                    PipelineContext ctx = new PipelineContext();
                    ctx.pipelineStart = Instant.now();
                    ctx.canonicalQuestion = canonicalQuestion;
                    ctx.groups = groups;
                    ctx.userGuidance = userReview.userGuidance();
                    ctx.focusSubQuestions = userReview.focusSubQuestions();

                    streamReportPhase(taskId, ctx, sink);
                } catch (Exception e) {
                    failTask(taskId, "REPORT_SEGMENT_ERROR", e);
                    sink.error(e);
                }
            });
        });
    }

    private void streamReportPhase(UUID taskId, PipelineContext ctx,
                                   reactor.core.publisher.FluxSink<String> sink) {
        updateStage(taskId, ReviewStage.REPORT_GENERATION);
        Instant reportStart = Instant.now();
        StringBuilder reportCollector = new StringBuilder();

        reportGeneratorService.generateReportStreaming(
                        ctx.canonicalQuestion, ctx.groups, ctx.userGuidance, ctx.focusSubQuestions)
                .doOnNext(chunk -> {
                    reportCollector.append(chunk);
                    sink.next(chunk);
                })
                .doOnComplete(() -> {
                    long reportMs = Duration.between(reportStart, Instant.now()).toMillis();
                    ctx.reportMs = reportMs;
                    ctx.totalMs = Duration.between(ctx.pipelineStart, Instant.now()).toMillis();
                    finalizeTask(taskId, ctx, reportCollector.toString());
                    sink.complete();
                })
                .doOnError(error -> {
                    failTask(taskId, "REPORT_STREAM_ERROR", error);
                    sink.error(error);
                })
                .subscribe();
    }

    private void executePipeline(UUID taskId, String question) {
        try {
            PipelineContext ctx = runPreReportStages(taskId, question);

            // Stage 7: Report generation
            updateStage(taskId, ReviewStage.REPORT_GENERATION);
            Instant reportStart = Instant.now();
            String report = reportGeneratorService.generateReport(ctx.canonicalQuestion, ctx.groups);
            ctx.reportMs = Duration.between(reportStart, Instant.now()).toMillis();
            ctx.totalMs = Duration.between(ctx.pipelineStart, Instant.now()).toMillis();

            finalizeTask(taskId, ctx, report);
        } catch (Exception e) {
            failTask(taskId, "PIPELINE_ERROR", e);
        }
    }

    private PipelineContext runPreReportStagesWithAnalysis(UUID taskId, String question,
                                                              QueryAnalysis analysis) {
        PipelineContext ctx = new PipelineContext();
        ctx.pipelineStart = Instant.now();

        ctx.canonicalQuestion = analysis.mainQuestion() != null && !analysis.mainQuestion().isBlank()
                ? analysis.mainQuestion()
                : question;
        reviewRepository.updateQueryAnalysis(taskId, analysis);
        reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, ReviewStage.QUERY_ANALYSIS);

        return runExpansionAndBeyond(taskId, ctx, analysis);
    }

    private PipelineContext runPreReportStages(UUID taskId, String question) {
        PipelineContext ctx = new PipelineContext();
        ctx.pipelineStart = Instant.now();

        updateStage(taskId, ReviewStage.QUERY_ANALYSIS);
        QueryAnalysis analysis = queryAnalyzerService.analyze(question);
        ctx.canonicalQuestion = analysis.mainQuestion() != null && !analysis.mainQuestion().isBlank()
                ? analysis.mainQuestion()
                : question;
        reviewRepository.updateQueryAnalysis(taskId, analysis);
        reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, ReviewStage.QUERY_ANALYSIS);

        return runExpansionAndBeyond(taskId, ctx, analysis);
    }

    private PipelineContext runExpansionAndBeyond(UUID taskId, PipelineContext ctx,
                                                  QueryAnalysis analysis) {
        // Stage 2: Query expansion
        updateStage(taskId, ReviewStage.QUERY_EXPANSION);
        List<String> expandedQueries = queryExpansionService.expand(analysis);

        // Stage 3: High-recall retrieval
        updateStage(taskId, ReviewStage.RETRIEVAL);
        Instant retrievalStart = Instant.now();
        List<RetrievedChunk> candidates = highRecallRetrievalService.retrieve(expandedQueries);
        ctx.retrievalMs = Duration.between(retrievalStart, Instant.now()).toMillis();
        log.info("Task {}: Retrieved {} candidates in {}ms", taskId, candidates.size(), ctx.retrievalMs);

        List<ReviewCandidate> candidateRecords = candidates.stream()
                .map(c -> new ReviewCandidate(null, taskId, c.chunkId(), c.documentId(),
                        c.documentTitle(), c.score(), c.source(), null, null, null, false, c.text()))
                .toList();
        reviewRepository.insertCandidates(taskId, candidateRecords);

        // Stage 4: Reranking
        updateStage(taskId, ReviewStage.RERANKING);
        Instant rerankStart = Instant.now();
        List<RetrievedChunk> included = reviewRerankerService.rerank(ctx.canonicalQuestion, candidates);
        ctx.rerankMs = Duration.between(rerankStart, Instant.now()).toMillis();
        log.info("Task {}: Reranked to {} included chunks in {}ms", taskId, included.size(), ctx.rerankMs);

        Map<String, ChunkRelevanceJudgment> judgments = reviewRerankerService.getJudgmentMap(ctx.canonicalQuestion, included);
        for (RetrievedChunk c : candidates) {
            ChunkRelevanceJudgment j = judgments.get(c.chunkId());
            boolean isIncluded = included.stream().anyMatch(ic -> ic.chunkId().equals(c.chunkId()));
            reviewRepository.updateCandidateReranking(taskId, c.chunkId(),
                    c.score(),
                    j != null ? j.relevance() : Relevance.LOW,
                    j != null ? j.reason() : null,
                    isIncluded);
        }

        // Stage 5: Evidence extraction
        updateStage(taskId, ReviewStage.EVIDENCE_EXTRACTION);
        Instant extractionStart = Instant.now();
        List<ExtractedEvidence> evidence = evidenceExtractionService.extract(
                ctx.canonicalQuestion, analysis.subQuestions(), included);
        ctx.extractionMs = Duration.between(extractionStart, Instant.now()).toMillis();
        log.info("Task {}: Extracted {} evidence items in {}ms", taskId, evidence.size(), ctx.extractionMs);

        for (ExtractedEvidence e : evidence) {
            reviewRepository.insertEvidence(taskId, e);
        }
        reviewRepository.updateTaskCounts(taskId, candidates.size(), evidence.size());

        // Stage 6: Evidence fusion
        updateStage(taskId, ReviewStage.EVIDENCE_FUSION);
        Instant fusionStart = Instant.now();
        ctx.groups = evidenceFusionService.fuse(analysis.subQuestions(), evidence);
        ctx.fusionMs = Duration.between(fusionStart, Instant.now()).toMillis();
        log.info("Task {}: Fused into {} groups in {}ms", taskId, ctx.groups.size(), ctx.fusionMs);

        return ctx;
    }

    private void finalizeTask(UUID taskId, PipelineContext ctx, String report) {
        if (report != null && !report.isBlank()) {
            reviewRepository.updateTaskReport(taskId, report, null);
        } else {
            log.warn("Task {}: report is null or blank at finalize stage", taskId);
        }
        ReviewTaskMetrics metrics = new ReviewTaskMetrics(
                ctx.retrievalMs, ctx.rerankMs, ctx.extractionMs,
                ctx.fusionMs, ctx.reportMs, ctx.totalMs);
        reviewRepository.updateTaskMetrics(taskId, metrics);
        reviewRepository.completeTask(taskId);
        log.info("Task {} completed in {}ms, report={}", taskId, ctx.totalMs,
                report == null ? "null" : report.length() + " chars");
    }

    private void failTask(UUID taskId, String errorCode, Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        log.error("Task {} failed: {} - {}", taskId, errorCode, message, error);
        reviewRepository.failTask(taskId, errorCode, message);
    }

    private void updateStage(UUID taskId, ReviewStage stage) {
        reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, stage);
    }

    private static class PipelineContext {
        Instant pipelineStart;
        String canonicalQuestion;
        long retrievalMs;
        long rerankMs;
        long extractionMs;
        long fusionMs;
        long reportMs;
        long totalMs;
        List<FusedEvidenceGroup> groups;
        String userGuidance;
        List<String> focusSubQuestions;
    }
}
