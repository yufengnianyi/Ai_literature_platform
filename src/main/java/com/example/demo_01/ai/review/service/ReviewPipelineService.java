package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private ReportGeneratorService reportGeneratorService;

    @Resource
    private PaperEvidenceTableSynthesisService paperEvidenceTableSynthesisService;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    public ReviewTaskAcceptedResponse submit(String userId, String question) {
        return submit(userId, question, PaperEvidenceTableSynthesisService.ANTIMICROBIAL_TEMPLATE_ID);
    }

    public ReviewTaskAcceptedResponse submit(String userId, String question, String templateId) {
        return submit(userId, question, templateId, null);
    }

    public ReviewTaskAcceptedResponse submit(String userId, String question, String templateId,
                                             ReviewLoadSettings loadSettings) {
        UUID taskId = UUID.randomUUID();
        String resolvedTemplate = normalizeTemplateId(templateId);
        reviewRepository.insertTask(taskId, userId, question, resolvedTemplate);
        ReviewLoadSettings resolvedLoadSettings = normalizeLoadSettings(loadSettings);
        reviewTaskExecutor.execute(() -> executeCandidateDiscovery(taskId, question, resolvedTemplate, resolvedLoadSettings));
        return new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.QUEUED);
    }

    public ReviewTaskAcceptedResponse retry(UUID taskId) {
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (task.status() == ReviewTaskStatus.RUNNING) {
            throw new IllegalStateException("Task is still running, cannot retry");
        }

        reviewRepository.resetTaskForRetry(taskId);
        reviewTaskExecutor.execute(() -> executeCandidateDiscovery(taskId, task.question(), normalizeTemplateId(task.templateId()), null));
        return new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.QUEUED);
    }

    public ReviewTaskAcceptedResponse confirmDocuments(UUID taskId, List<UUID> selectedDocumentIds) {
        List<UUID> safeIds = selectedDocumentIds == null ? List.of() : selectedDocumentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (safeIds.isEmpty()) {
            throw new IllegalArgumentException("At least one document must be selected.");
        }
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (task.status() == ReviewTaskStatus.RUNNING) {
            throw new IllegalStateException("Task is still running, cannot confirm documents.");
        }
        reviewRepository.updateDocumentSelection(taskId, safeIds);
        reviewTaskExecutor.execute(() -> executeSelectedDocuments(taskId, safeIds));
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
                    PipelineContext ctx = runAutoReportStages(taskId, question, resolvedTemplate);
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

    private void executeCandidateDiscovery(UUID taskId, String question, String templateId,
                                           ReviewLoadSettings loadSettings) {
        try {
            runCandidateDiscoveryStages(taskId, question, templateId, loadSettings);
            reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.AWAITING_USER, ReviewStage.RERANKING);
        } catch (Exception e) {
            failTask(taskId, "PIPELINE_ERROR", e);
        }
    }

    private void executeSelectedDocuments(UUID taskId, List<UUID> selectedDocumentIds) {
        try {
            ReviewTaskRecord task = reviewRepository.findTask(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            PipelineContext ctx = contextFromTask(task);
            runSelectedDocumentAnalysis(taskId, ctx, selectedDocumentIds);
            finalizeWithGeneratedReport(taskId, ctx);
        } catch (Exception e) {
            failTask(taskId, "PIPELINE_ERROR", e);
        }
    }

    private PipelineContext runAutoReportStages(UUID taskId, String question, String templateId) {
        PipelineContext ctx = runCandidateDiscoveryStages(taskId, question, templateId, null);
        runSelectedDocumentAnalysis(taskId, ctx, ctx.autoSelectedDocumentIds);
        return ctx;
    }

    private PipelineContext runCandidateDiscoveryStages(UUID taskId, String question, String templateId,
                                                       ReviewLoadSettings loadSettings) {
        PipelineContext ctx = new PipelineContext();
        ctx.pipelineStart = Instant.now();
        ctx.templateId = normalizeTemplateId(templateId);
        ctx.loadSettings = normalizeLoadSettings(loadSettings);

        updateStage(taskId, ReviewStage.QUERY_ANALYSIS);
        QueryAnalysis analysis = queryAnalyzerService.analyze(question);
        ctx.canonicalQuestion = analysis.mainQuestion() != null && !analysis.mainQuestion().isBlank()
                ? analysis.mainQuestion()
                : question;
        ctx.analysis = analysis;
        reviewRepository.updateQueryAnalysis(taskId, analysis);
        reviewRepository.updateTaskStatus(taskId, ReviewTaskStatus.RUNNING, ReviewStage.QUERY_ANALYSIS);

        runExpansionAndBeyond(taskId, ctx, analysis);
        return ctx;
    }

    private void runExpansionAndBeyond(UUID taskId, PipelineContext ctx,
                                       QueryAnalysis analysis) {
        // Stage 2: Query expansion
        updateStage(taskId, ReviewStage.QUERY_EXPANSION);
        List<String> expandedQueries = queryExpansionService.expand(analysis);

        // Stage 3: High-recall retrieval
        updateStage(taskId, ReviewStage.RETRIEVAL);
        Instant retrievalStart = Instant.now();
        List<RetrievedChunk> seedChunks = highRecallRetrievalService.retrieveSeedChunks(expandedQueries);
        ctx.retrievalMs = Duration.between(retrievalStart, Instant.now()).toMillis();
        if (seedChunks == null || seedChunks.isEmpty()) {
            throw new IllegalStateException("No literature chunks were retrieved for this review question.");
        }
        log.info("Task {}: Retrieved {} seed candidates in {}ms", taskId, seedChunks.size(), ctx.retrievalMs);

        reviewRepository.insertCandidates(taskId, seedChunks.stream()
                .map(c -> toSeedCandidate(taskId, c))
                .toList());

        updateStage(taskId, ReviewStage.DOCUMENT_PROMOTION);
        Instant promotionStart = Instant.now();
        List<SelectedDocument> selectedDocuments = rankDocuments(seedChunks);
        ctx.documentPromotionMs = Duration.between(promotionStart, Instant.now()).toMillis();
        ctx.rerankMs = 0L;
        double autoSelectThreshold = ctx.loadSettings.minScore();
        int maxDocuments = ctx.loadSettings.maxDocuments() == null
                ? Integer.MAX_VALUE
                : ctx.loadSettings.maxDocuments();
        List<UUID> autoSelectedDocumentIds = selectedDocuments.stream()
                .filter(selected -> selected.seedMaxScore() >= autoSelectThreshold)
                .map(SelectedDocument::documentId)
                .filter(Objects::nonNull)
                .distinct()
                .limit(maxDocuments)
                .toList();
        ctx.autoSelectedDocumentIds = autoSelectedDocumentIds;
        reviewRepository.insertDocumentCandidates(taskId, selectedDocuments.stream()
                .map(selected -> toDocumentCandidate(
                        taskId, selected, autoSelectedDocumentIds.contains(selected.documentId()), autoSelectThreshold))
                .toList());
        updateStage(taskId, ReviewStage.RERANKING);
        for (RetrievedChunk c : seedChunks) {
            boolean included = autoSelectedDocumentIds.contains(c.documentId());
            reviewRepository.updateCandidateReranking(taskId, c.chunkId(),
                    c.score(), included ? Relevance.HIGH : Relevance.LOW,
                    included ? autoSelectionReason(autoSelectThreshold)
                            : "Available as an unselected candidate; seed chunk retrieval score did not exceed the automatic selection threshold.",
                    included);
        }

        reviewRepository.updateTaskCounts(taskId, seedChunks.size(), selectedDocuments.size(), 0);
        log.info("Task {}: Auto-selected {} of {} document candidates with seedMaxScore >= {}, maxDocuments={}",
                taskId, autoSelectedDocumentIds.size(), selectedDocuments.size(), autoSelectThreshold, maxDocuments);
    }

    private void runSelectedDocumentAnalysis(UUID taskId,
                                             PipelineContext ctx,
                                             List<UUID> selectedDocumentIds) {
        updateStage(taskId, ReviewStage.EVIDENCE_EXTRACTION);
        Instant analysisStart = Instant.now();
        List<ReviewDocumentCandidate> candidates = reviewRepository.findDocumentCandidates(taskId);
        Map<UUID, ReviewDocumentCandidate> candidateByDocument = candidates.stream()
                .filter(candidate -> candidate.documentId() != null)
                .collect(Collectors.toMap(ReviewDocumentCandidate::documentId, candidate -> candidate,
                        (left, right) -> left, LinkedHashMap::new));
        Map<UUID, List<RetrievedChunk>> seedChunksByDocument = reviewRepository.findAllCandidates(taskId).stream()
                .filter(candidate -> candidate.documentId() != null)
                .collect(Collectors.groupingBy(
                        ReviewCandidate::documentId,
                        LinkedHashMap::new,
                        Collectors.mapping(this::toRetrievedChunk, Collectors.toList())));

        List<ReviewPaperEvidenceTable> tables = new ArrayList<>();
        for (UUID documentId : selectedDocumentIds == null ? List.<UUID>of() : selectedDocumentIds) {
            ReviewDocumentCandidate candidate = candidateByDocument.get(documentId);
            if (candidate == null) {
                continue;
            }
            List<RetrievedChunk> seedChunks = seedChunksByDocument.getOrDefault(documentId, List.of());
            List<RetrievedChunk> allChunks = reviewRepository.findAllChunksByDocumentId(documentId);
            if (allChunks == null || allChunks.isEmpty()) {
                allChunks = seedChunks;
            }
            String documentTitle = allChunks.stream()
                    .map(RetrievedChunk::documentTitle)
                    .filter(Objects::nonNull)
                    .filter(title -> !title.isBlank())
                    .findFirst()
                    .orElse(candidate.documentTitle());
            ReviewPaperEvidenceTable table = paperEvidenceTableSynthesisService.synthesizeBestTable(
                    taskId,
                    ctx.analysis,
                    ctx.canonicalQuestion,
                    documentId,
                    documentTitle,
                    allChunks,
                    List.of(),
                    null,
                    ctx.templateId,
                    seedChunks
            );
            tables.add(table);
            reviewRepository.upsertPaperEvidenceTable(table);
            log.info("Task {}: Generated paper evidence table for doc {}", taskId, documentId);
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("No selected document candidates were found for this task.");
        }
        ctx.paperEvidenceTables = tables;
        ctx.extractionMs = Duration.between(analysisStart, Instant.now()).toMillis();
        int candidateCount = reviewRepository.findAllCandidates(taskId).size();
        reviewRepository.updateTaskCounts(taskId, candidateCount, tables.size(), 0);
        log.info("Task {}: Generated {} paper evidence tables in {}ms", taskId, tables.size(), ctx.extractionMs);
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
        ReviewLoadSettings loadSettings;
        List<UUID> autoSelectedDocumentIds = List.of();
    }

    private PipelineContext contextFromTask(ReviewTaskRecord task) {
        PipelineContext ctx = new PipelineContext();
        ctx.pipelineStart = Instant.now();
        ctx.templateId = normalizeTemplateId(task.templateId());
        QueryAnalysis analysis = task.queryAnalysis() == null
                ? queryAnalyzerService.analyze(task.question())
                : task.queryAnalysis();
        ctx.analysis = analysis;
        ctx.canonicalQuestion = analysis.mainQuestion() != null && !analysis.mainQuestion().isBlank()
                ? analysis.mainQuestion()
                : task.question();
        if (task.queryAnalysis() == null) {
            reviewRepository.updateQueryAnalysis(task.taskId(), analysis);
        }
        ReviewTaskMetrics metrics = task.metrics();
        if (metrics != null) {
            ctx.retrievalMs = metrics.retrievalMs() == null ? 0L : metrics.retrievalMs();
            ctx.documentPromotionMs = metrics.documentPromotionMs() == null ? 0L : metrics.documentPromotionMs();
            ctx.rerankMs = metrics.rerankMs() == null ? 0L : metrics.rerankMs();
        }
        return ctx;
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

    private List<SelectedDocument> rankDocuments(List<RetrievedChunk> chunks) {
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
                .sorted(java.util.Comparator
                        .comparingDouble(SelectedDocument::finalScore)
                        .thenComparingInt(item -> item.seedChunks().size())
                        .reversed())
                .toList();
    }

    private ReviewDocumentCandidate toDocumentCandidate(UUID taskId, SelectedDocument selected, boolean defaultSelected,
                                                        double autoSelectThreshold) {
        List<String> seedIds = selected.seedChunks().stream()
                .map(RetrievedChunk::chunkId)
                .filter(Objects::nonNull)
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
                defaultSelected ? Relevance.HIGH : Relevance.MEDIUM,
                defaultSelected
                        ? autoSelectionReason(autoSelectThreshold)
                        : "Candidate paper available but below the automatic selection threshold.",
                null,
                List.of(),
                List.of(),
                true,
                defaultSelected
        );
    }

    private RetrievedChunk toRetrievedChunk(ReviewCandidate candidate) {
        double score = candidate.rerankScore() != null ? candidate.rerankScore() : candidate.retrievalScore();
        return new RetrievedChunk(
                candidate.chunkId(),
                candidate.documentId(),
                candidate.documentTitle(),
                candidate.chunkText(),
                candidate.sectionPath(),
                score,
                candidate.retrievalSource()
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

    private String autoSelectionReason(double threshold) {
        return "Selected automatically because seed chunk retrieval score met or exceeded threshold "
                + formatScore(threshold) + ".";
    }

    private ReviewLoadSettings normalizeLoadSettings(ReviewLoadSettings loadSettings) {
        double minScore = reviewProperties.getRetrieval().getAutoSelectMinSeedScore();
        Integer maxDocuments = null;
        if (loadSettings != null) {
            if (loadSettings.minScore() != null) {
                minScore = Math.max(0.0, Math.min(1.0, loadSettings.minScore()));
            }
            if (loadSettings.maxDocuments() != null) {
                maxDocuments = Math.max(1, Math.min(100, loadSettings.maxDocuments()));
            }
        }
        return new ReviewLoadSettings(minScore, maxDocuments);
    }

    private String formatScore(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
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
