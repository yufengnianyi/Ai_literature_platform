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

    public Flux<String> submitStreaming(String userId, String question) {
        UUID taskId = UUID.randomUUID();
        reviewRepository.insertTask(taskId, userId, question);

        return Flux.create(sink -> {
            reviewTaskExecutor.execute(() -> {
                try {
                    PipelineContext ctx = runPreReportStages(taskId, question);
                    updateStage(taskId, ReviewStage.REPORT_GENERATION);
                    Instant reportStart = Instant.now();
                    StringBuilder reportCollector = new StringBuilder();

                    reportGeneratorService.generateReportStreaming(question, ctx.groups)
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
                } catch (Exception e) {
                    failTask(taskId, "PIPELINE_ERROR", e);
                    sink.error(e);
                }
            });
        });
    }

    private void executePipeline(UUID taskId, String question) {
        try {
            PipelineContext ctx = runPreReportStages(taskId, question);

            // Stage 7: Report generation
            updateStage(taskId, ReviewStage.REPORT_GENERATION);
            Instant reportStart = Instant.now();
            String report = reportGeneratorService.generateReport(question, ctx.groups);
            ctx.reportMs = Duration.between(reportStart, Instant.now()).toMillis();
            ctx.totalMs = Duration.between(ctx.pipelineStart, Instant.now()).toMillis();

            finalizeTask(taskId, ctx, report);
        } catch (Exception e) {
            failTask(taskId, "PIPELINE_ERROR", e);
        }
    }

    private PipelineContext runPreReportStages(UUID taskId, String question) {
        PipelineContext ctx = new PipelineContext();
        ctx.pipelineStart = Instant.now();

        // Stage 1: Query analysis
        updateStage(taskId, ReviewStage.QUERY_ANALYSIS);
        QueryAnalysis analysis = queryAnalyzerService.analyze(question);
        reviewRepository.updateQueryAnalysis(taskId, analysis);
        reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, ReviewStage.QUERY_ANALYSIS);

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
        List<RetrievedChunk> included = reviewRerankerService.rerank(question, candidates);
        ctx.rerankMs = Duration.between(rerankStart, Instant.now()).toMillis();
        log.info("Task {}: Reranked to {} included chunks in {}ms", taskId, included.size(), ctx.rerankMs);

        Map<String, ChunkRelevanceJudgment> judgments = reviewRerankerService.getJudgmentMap(question, included);
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
                question, analysis.subQuestions(), included);
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
        long retrievalMs;
        long rerankMs;
        long extractionMs;
        long fusionMs;
        long reportMs;
        long totalMs;
        List<FusedEvidenceGroup> groups;
    }
}
