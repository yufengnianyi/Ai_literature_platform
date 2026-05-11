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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private DocumentPromotionService documentPromotionService;

    @Resource
    private ReviewRerankerService reviewRerankerService;

    @Resource
    private DocumentKnowledgeEnrichmentService documentKnowledgeEnrichmentService;

    @Resource
    private EvidenceExtractionService evidenceExtractionService;

    @Resource
    private EvidenceFusionService evidenceFusionService;

    @Resource
    private ReportGeneratorService reportGeneratorService;

    @Resource
    private PaperEvidenceTableSynthesisService paperEvidenceTableSynthesisService;

    @Resource
    private QuantitativeAnchorRetriever quantitativeAnchorRetriever;

    @Resource
    private CompoundEvidenceSynthesizer compoundEvidenceSynthesizer;

    @Resource
    private CompoundProfileAuditor compoundProfileAuditor;

    @Resource
    private ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.bsc.langgraph4j.CompiledGraph<com.example.demo_01.ai.review.agent.PerPaperAgentState> perPaperGraph;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    public ReviewTaskAcceptedResponse submit(String userId, String question) {
        return submit(userId, question, PaperEvidenceTableSynthesisService.ANTIMICROBIAL_TEMPLATE_ID);
    }

    public ReviewTaskAcceptedResponse submit(String userId, String question, String templateId) {
        UUID taskId = UUID.randomUUID();
        String resolvedTemplate = normalizeTemplateId(templateId);
        reviewRepository.insertTask(taskId, userId, question, resolvedTemplate);
        reviewTaskExecutor.execute(() -> executePipeline(taskId, question, resolvedTemplate));
        return new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.QUEUED);
    }

    public ReviewTaskAcceptedResponse retry(UUID taskId) {
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (task.status() == ReviewTaskStatus.RUNNING) {
            throw new IllegalStateException("Task is still running, cannot retry");
        }

        reviewRepository.resetTaskForRetry(taskId);
        reviewTaskExecutor.execute(() -> executePipeline(taskId, task.question(), normalizeTemplateId(task.templateId())));
        return new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.QUEUED);
    }

    public Flux<String> submitStreaming(String userId, UUID taskId, String question) {
        return submitStreaming(userId, taskId, question, PaperEvidenceTableSynthesisService.ANTIMICROBIAL_TEMPLATE_ID);
    }

    public Flux<String> submitStreaming(String userId, UUID taskId, String question, String templateId) {
        String resolvedTemplate = normalizeTemplateId(templateId);
        reviewRepository.insertTask(taskId, userId, question, resolvedTemplate);

        return Flux.create(sink -> {
            reviewTaskExecutor.execute(() -> {
                try {
                    PipelineContext ctx = runPreReportStages(taskId, question, resolvedTemplate);
                    streamReportPhase(taskId, ctx, sink);
                } catch (Exception e) {
                    failTask(taskId, "PIPELINE_ERROR", e);
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
                        ctx.analysis, ctx.groups, ctx.evidence, ctx.userGuidance, ctx.focusSubQuestions,
                        ctx.synthesizedRecords, ctx.paperEvidenceTables)
                .doOnNext(chunk -> {
                    reportCollector.append(chunk);
                    sink.next(chunk);
                })
                .doOnComplete(() -> {
                    long reportMs = Duration.between(reportStart, Instant.now()).toMillis();
                    ctx.reportMs = reportMs;
                    if (ctx.pipelineStart != null) {
                        ctx.totalMs = Duration.between(ctx.pipelineStart, Instant.now()).toMillis();
                    } else {
                        ctx.totalMs = sumMetrics(ctx.retrievalMs, ctx.documentPromotionMs, ctx.rerankMs,
                                ctx.extractionMs, ctx.fusionMs, ctx.reportMs);
                    }
                    finalizeTask(taskId, ctx, reportCollector.toString());
                    sink.complete();
                })
                .doOnError(error -> {
                    failTask(taskId, "REPORT_STREAM_ERROR", error);
                    sink.error(error);
                })
                .subscribe();
    }

    private void executePipeline(UUID taskId, String question, String templateId) {
        try {
            PipelineContext ctx = runPreReportStages(taskId, question, templateId);
            finalizeWithGeneratedReport(taskId, ctx);
        } catch (Exception e) {
            failTask(taskId, "PIPELINE_ERROR", e);
        }
    }

    private PipelineContext runPreReportStages(UUID taskId, String question, String templateId) {
        PipelineContext ctx = new PipelineContext();
        ctx.pipelineStart = Instant.now();
        ctx.templateId = normalizeTemplateId(templateId);

        updateStage(taskId, ReviewStage.QUERY_ANALYSIS);
        QueryAnalysis analysis = queryAnalyzerService.analyze(question);
        ctx.canonicalQuestion = analysis.mainQuestion() != null && !analysis.mainQuestion().isBlank()
                ? analysis.mainQuestion()
                : question;
        ctx.analysis = analysis;
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
        List<RetrievedChunk> seedChunks = highRecallRetrievalService.retrieveSeedChunks(expandedQueries);
        ctx.retrievalMs = Duration.between(retrievalStart, Instant.now()).toMillis();
        log.info("Task {}: Retrieved {} seed candidates in {}ms", taskId, seedChunks.size(), ctx.retrievalMs);
        if (seedChunks == null || seedChunks.isEmpty()) {
            throw new IllegalStateException("No literature chunks were retrieved for this review question.");
        }

        reviewRepository.insertCandidates(taskId, seedChunks.stream()
                .map(c -> toSeedCandidate(taskId, c))
                .toList());

        updateStage(taskId, ReviewStage.DOCUMENT_PROMOTION);
        Instant promotionStart = Instant.now();
        SelectedDocument selected = selectBestDocument(seedChunks);
        ctx.documentPromotionMs = Duration.between(promotionStart, Instant.now()).toMillis();
        ctx.rerankMs = 0L;
        ctx.selectedSeedChunks = selected.seedChunks();
        reviewRepository.updateSelectedDocument(taskId, selected.documentId(), selected.documentTitle());
        reviewRepository.insertDocumentCandidates(taskId, List.of(toSinglePaperDocumentCandidate(taskId, selected)));

        updateStage(taskId, ReviewStage.RERANKING);
        for (RetrievedChunk c : seedChunks) {
            boolean included = selected.documentId().equals(c.documentId());
            reviewRepository.updateCandidateReranking(taskId, c.chunkId(),
                    c.score(), included ? Relevance.HIGH : Relevance.LOW,
                    included ? "Selected as the highest-scoring paper for the single-paper demo."
                            : "Not selected because another paper scored higher.",
                    included);
        }

        runSinglePaperAnalysis(taskId, ctx, analysis, selected, seedChunks.size());

        return ctx;
    }

    private void runSinglePaperAnalysis(UUID taskId,
                                        PipelineContext ctx,
                                        QueryAnalysis analysis,
                                        SelectedDocument selected,
                                        int candidateCount) {
        updateStage(taskId, ReviewStage.EVIDENCE_EXTRACTION);
        Instant analysisStart = Instant.now();
        List<RetrievedChunk> allChunks = reviewRepository.findAllChunksByDocumentId(selected.documentId());
        if (allChunks == null || allChunks.isEmpty()) {
            allChunks = selected.seedChunks();
        }
        String documentTitle = allChunks.stream()
                .map(RetrievedChunk::documentTitle)
                .filter(java.util.Objects::nonNull)
                .filter(title -> !title.isBlank())
                .findFirst()
                .orElse(selected.documentTitle());
        ReviewPaperEvidenceTable table = paperEvidenceTableSynthesisService.synthesizeBestTable(
                taskId,
                analysis,
                ctx.canonicalQuestion,
                selected.documentId(),
                documentTitle,
                allChunks,
                List.of(),
                null,
                ctx.templateId,
                selected.seedChunks()
        );
        ctx.paperEvidenceTables = List.of(table);
        ctx.extractionMs = Duration.between(analysisStart, Instant.now()).toMillis();
        reviewRepository.upsertPaperEvidenceTable(table);
        reviewRepository.updateTaskCounts(taskId, candidateCount, 1, 0);
        log.info("Task {}: Generated single-paper evidence table for doc {} in {}ms",
                taskId, selected.documentId(), ctx.extractionMs);
    }

    private void finalizeWithGeneratedReport(UUID taskId, PipelineContext ctx) {
        updateStage(taskId, ReviewStage.REPORT_GENERATION);
        Instant reportStart = Instant.now();
        String report = reportGeneratorService.generateReport(ctx.analysis, ctx.groups, ctx.evidence,
                ctx.synthesizedRecords, ctx.paperEvidenceTables);
        ctx.reportMs = Duration.between(reportStart, Instant.now()).toMillis();
        ctx.totalMs = ctx.pipelineStart == null
                ? sumMetrics(ctx.retrievalMs, ctx.documentPromotionMs, ctx.rerankMs, ctx.extractionMs, ctx.fusionMs, ctx.reportMs)
                : Duration.between(ctx.pipelineStart, Instant.now()).toMillis();
        finalizeTask(taskId, ctx, report);
    }

    private void finalizeTask(UUID taskId, PipelineContext ctx, String report) {
        if (report != null && !report.isBlank()) {
            reviewRepository.updateTaskReport(taskId, report, null);
        } else {
            log.warn("Task {}: report is null or blank at finalize stage", taskId);
        }
        ReviewTaskMetrics metrics = new ReviewTaskMetrics(
                ctx.retrievalMs, ctx.documentPromotionMs, ctx.rerankMs, ctx.extractionMs,
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
        QueryAnalysis analysis;
        String canonicalQuestion;
        long retrievalMs;
        long documentPromotionMs;
        long rerankMs;
        long extractionMs;
        long fusionMs;
        long reportMs;
        long totalMs;
        List<FusedEvidenceGroup> groups;
        String userGuidance;
        List<String> focusSubQuestions;
        List<ExtractedEvidence> evidence;
        List<SynthesizedCompoundRecord> synthesizedRecords;
        List<ReviewPaperEvidenceTable> paperEvidenceTables;
        String templateId;
        List<RetrievedChunk> selectedSeedChunks;
    }

    private String normalizeTemplateId(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return PaperEvidenceTableSynthesisService.ANTIMICROBIAL_TEMPLATE_ID;
        }
        if (!PaperEvidenceTableSynthesisService.ANTIMICROBIAL_TEMPLATE_ID.equals(templateId)) {
            throw new IllegalArgumentException("Unsupported review template: " + templateId);
        }
        return templateId;
    }

    private SelectedDocument selectBestDocument(List<RetrievedChunk> chunks) {
        Map<UUID, List<RetrievedChunk>> byDoc = chunks.stream()
                .filter(chunk -> chunk.documentId() != null)
                .collect(Collectors.groupingBy(RetrievedChunk::documentId,
                        java.util.LinkedHashMap::new, Collectors.toList()));
        if (byDoc.isEmpty()) {
            throw new IllegalStateException("Retrieved chunks do not contain document identifiers.");
        }
        return byDoc.entrySet().stream()
                .map(entry -> {
                    List<RetrievedChunk> docChunks = entry.getValue();
                    double maxScore = docChunks.stream().mapToDouble(RetrievedChunk::score).max().orElse(0.0);
                    double top3Avg = docChunks.stream()
                            .map(RetrievedChunk::score)
                            .sorted(java.util.Comparator.reverseOrder())
                            .limit(3)
                            .mapToDouble(Double::doubleValue)
                            .average()
                            .orElse(maxScore);
                    String title = docChunks.stream()
                            .map(RetrievedChunk::documentTitle)
                            .filter(java.util.Objects::nonNull)
                            .filter(value -> !value.isBlank())
                            .findFirst()
                            .orElse("Untitled document");
                    double finalScore = maxScore + Math.min(0.2, docChunks.size() * 0.02);
                    return new SelectedDocument(entry.getKey(), title, docChunks, maxScore, top3Avg, finalScore);
                })
                .max(java.util.Comparator
                        .comparingDouble(SelectedDocument::finalScore)
                        .thenComparingInt(item -> item.seedChunks().size()))
                .orElseThrow(() -> new IllegalStateException("No eligible paper was found after retrieval."));
    }

    private ReviewDocumentCandidate toSinglePaperDocumentCandidate(UUID taskId, SelectedDocument selected) {
        List<String> seedIds = selected.seedChunks().stream()
                .map(RetrievedChunk::chunkId)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new ReviewDocumentCandidate(
                null,
                taskId,
                selected.documentId(),
                selected.documentTitle(),
                selected.seedChunks().size(),
                seedIds,
                selected.seedMaxScore(),
                selected.seedAvgTop3Score(),
                0.0,
                0.0,
                selected.finalScore(),
                selected.finalScore(),
                Relevance.HIGH,
                "Automatically selected as the highest-scoring paper for the single-paper demo.",
                null,
                List.of(),
                List.of(),
                true,
                true
        );
    }

    private record SelectedDocument(UUID documentId,
                                    String documentTitle,
                                    List<RetrievedChunk> seedChunks,
                                    double seedMaxScore,
                                    double seedAvgTop3Score,
                                    double finalScore) {
    }

    private long sumMetrics(Long... values) {
        long total = 0L;
        for (Long value : values) {
            total += value == null ? 0L : value;
        }
        return total;
    }

    private ReviewCandidate toSeedCandidate(UUID taskId, RetrievedChunk chunk) {
        return new ReviewCandidate(
                null,
                taskId,
                chunk.chunkId(),
                chunk.documentId(),
                chunk.documentTitle(),
                chunk.score(),
                chunk.source(),
                chunk.sectionPath(),
                "SEED",
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                chunk.text()
        );
    }

}
