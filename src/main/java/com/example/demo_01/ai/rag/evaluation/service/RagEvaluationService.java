package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.ai.rag.evaluation.config.RagEvaluationProperties;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository.DocumentForEvaluation;
import com.example.demo_01.ai.rag.evaluation.service.QwenRerankClient.RerankDocument;
import com.example.demo_01.ai.rag.evaluation.service.QwenRerankClient.RerankResult;
import com.example.demo_01.ai.rag.evaluation.service.QwenRerankClient.RerankScore;
import com.example.demo_01.ai.rag.retrieval.Bm25ContentRetriever;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexService;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.QueryAnalyzerService;
import com.example.demo_01.ai.review.service.QueryExpansionService;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.DEFAULT_QUESTION;

@Slf4j
@Service
public class RagEvaluationService {

    @Resource
    private RagEvaluationProperties properties;

    @Resource
    private RagEvaluationRepository evaluationRepository;

    @Resource
    private RagEvaluationMetricsService metricsService;

    @Resource
    private ReviewRepository reviewRepository;

    @Resource
    private QueryAnalyzerService queryAnalyzerService;

    @Resource
    private QueryExpansionService queryExpansionService;

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private DashScopeModelProperties modelProperties;

    @Resource
    private TokenCountEstimator tokenCountEstimator;

    @Resource
    private QwenRerankClient qwenRerankClient;

    @Resource
    private Bm25IndexService bm25IndexService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    @Qualifier("reviewTaskExecutor")
    private TaskExecutor taskExecutor;

    @Value("${langchain4j.community.dashscope.embedding-model.model-name:text-embedding-v4}")
    private String embeddingModelName;

    private final ThreadLocal<ExperimentTelemetry> telemetry = new ThreadLocal<>();

    private final ThreadLocal<Map<String, Object>> activeConfig = new ThreadLocal<>();

    public RagEvaluationAcceptedResponse submit(String userId, String question) {
        return submit(userId, question, null);
    }

    public RagEvaluationAcceptedResponse submit(String userId, String question, RetrievalScope retrievalScope) {
        return submit(userId, new RagEvaluationExperimentRequest(question, retrievalScope));
    }

    public RagEvaluationAcceptedResponse submit(String userId, RagEvaluationExperimentRequest request) {
        UUID experimentId = UUID.randomUUID();
        String question = request == null ? null : request.question();
        String resolvedQuestion = question == null || question.isBlank() ? DEFAULT_QUESTION : question.trim();
        RetrievalScope retrievalScope = request == null ? null : request.retrievalScope();
        RetrievalScope resolvedScope = retrievalScope == null ? properties.getRetrievalScope() : retrievalScope;
        String reportRoot = Path.of(properties.getReportRoot(), experimentId.toString()).toString();
        evaluationRepository.insertExperiment(experimentId, userId, resolvedQuestion,
                config(resolvedScope, request, null), reportRoot);
        taskExecutor.execute(() -> runExperiment(experimentId));
        return new RagEvaluationAcceptedResponse(experimentId, ExperimentStatus.QUEUED);
    }

    public RagEvaluationSuiteAcceptedResponse submitRequiredSuite(String userId, String question) {
        UUID suiteId = UUID.randomUUID();
        String resolvedQuestion = question == null || question.isBlank() ? DEFAULT_QUESTION : question.trim();
        List<RagEvaluationExperimentRequest> specs = requiredSuiteSpecs(resolvedQuestion);
        List<UUID> experimentIds = new ArrayList<>();
        List<RagEvaluationAcceptedResponse> responses = new ArrayList<>();
        for (RagEvaluationExperimentRequest spec : specs) {
            UUID experimentId = UUID.randomUUID();
            String reportRoot = Path.of(properties.getReportRoot(), suiteId.toString(),
                    phaseName(spec.phase()), experimentId.toString()).toString();
            evaluationRepository.insertExperiment(experimentId, userId, resolvedQuestion,
                    config(resolveScope(spec), spec, suiteId), reportRoot);
            experimentIds.add(experimentId);
            responses.add(new RagEvaluationAcceptedResponse(experimentId, ExperimentStatus.QUEUED));
        }
        taskExecutor.execute(() -> {
            for (UUID experimentId : experimentIds) {
                runExperiment(experimentId);
            }
        });
        return new RagEvaluationSuiteAcceptedResponse(suiteId, responses);
    }

    public Optional<RagEvaluationExperimentRecord> findExperiment(UUID experimentId) {
        return evaluationRepository.findExperiment(experimentId);
    }

    public List<RagEvaluationDocumentJudgment> findJudgments(UUID experimentId) {
        return evaluationRepository.findJudgments(experimentId);
    }

    public RagEvaluationMetrics findMetrics(UUID experimentId) {
        return evaluationRepository.findExperiment(experimentId)
                .map(RagEvaluationExperimentRecord::metrics)
                .orElse(RagEvaluationMetrics.empty());
    }

    public RagEvaluationMetrics overrideJudgment(UUID experimentId, UUID documentId,
                                                 RagEvaluationOverrideRequest request) {
        if (request == null || request.label() == null) {
            throw new IllegalArgumentException("label is required");
        }
        evaluationRepository.updateOverride(experimentId, documentId, request.label(),
                request.keyChunkIds(), request.note());
        RagEvaluationMetrics metrics = recalculateMetrics(experimentId);
        evaluationRepository.updateMetrics(experimentId, metrics);
        return metrics;
    }

    private void runExperiment(UUID experimentId) {
        Instant startedAt = Instant.now();
        telemetry.set(new ExperimentTelemetry());
        try {
            RagEvaluationExperimentRecord experiment = evaluationRepository.findExperiment(experimentId)
                    .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));
            activeConfig.set(experiment.config());
            evaluationRepository.updateStatus(experimentId, ExperimentStatus.RUNNING);
            Path reportDir = Path.of(experiment.reportRoot()).toAbsolutePath().normalize();
            Files.createDirectories(reportDir);

            String effectiveQuestion = effectiveQuestion(experiment, reportDir);
            prepareJudgments(experiment, reportDir);
            RagEvaluationMetrics metrics;
            if (properties.isJudgmentOnly()) {
                log.info("RAG evaluation experiment {} is judgment-only; retrieval evaluation skipped", experimentId);
                metrics = RagEvaluationMetrics.empty();
            } else {
                QueryAnalysis analysis = timedQueryAnalysis(effectiveQuestion);
                List<String> queries = timedQueryExpansion(analysis);
                if (queries.isEmpty()) {
                    queries = List.of(effectiveQuestion);
                }
                evaluateRetrieval(experiment, effectiveQuestion, analysis, queries);
                metrics = recalculateMetrics(experiment);
            }
            evaluationRepository.completeExperiment(experimentId, attachTelemetry(metrics, startedAt));
            log.info("RAG evaluation experiment {} completed", experimentId);
        } catch (Exception e) {
            log.error("RAG evaluation experiment {} failed: {}", experimentId, e.getMessage(), e);
            evaluationRepository.failExperiment(experimentId, "RAG_EVAL_ERROR",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            telemetry.remove();
            activeConfig.remove();
        }
    }

    private void prepareJudgments(RagEvaluationExperimentRecord experiment, Path reportDir) {
        UUID experimentId = experiment.experimentId();
        List<RagEvaluationDocumentJudgment> existing = evaluationRepository.findJudgments(experimentId);
        if (!existing.isEmpty()) {
            log.info("RAG evaluation {} reuses {} existing judgments", experimentId, existing.size());
            return;
        }
        Instant startedAt = Instant.now();
        UUID sourceExperimentId = resolveSourceJudgmentExperimentId(experiment.config());
        if (sourceExperimentId == null) {
            throw new IllegalStateException("No source judgment experiment configured; refuse to re-label with LLM");
        }
        List<RagEvaluationDocumentJudgment> sourceJudgments = evaluationRepository.findJudgments(sourceExperimentId);
        if (sourceJudgments.isEmpty()) {
            throw new IllegalStateException("Source judgment experiment has no judgments: " + sourceExperimentId);
        }
        List<RagEvaluationDocumentJudgment> selected = selectedSourceJudgmentsForExperiment(experiment.config(), sourceJudgments);
        if (selected.isEmpty()) {
            throw new IllegalStateException("No reusable judgments selected from source experiment: " + sourceExperimentId);
        }
        for (RagEvaluationDocumentJudgment source : selected) {
            evaluationRepository.upsertJudgment(copyJudgment(source, experimentId));
        }
        evaluationRepository.mergeConfig(experimentId, Map.of(
                "sourceJudgmentExperimentId", sourceExperimentId.toString(),
                "reusedJudgmentCount", selected.size()
        ));
        activeConfig.set(new LinkedHashMap<>(evaluationRepository.findExperiment(experimentId)
                .map(RagEvaluationExperimentRecord::config)
                .orElse(experiment.config())));
        writeReusedJudgmentsReport(reportDir, sourceExperimentId, selected);
        recordUsage("judgment-reuse", "database", 0L, 0L, 0L, 0L,
                Duration.between(startedAt, Instant.now()).toMillis(), 1);
        log.info("RAG evaluation {} copied {} judgments from {}", experimentId, selected.size(), sourceExperimentId);
    }

    private UUID resolveSourceJudgmentExperimentId(Map<String, Object> config) {
        UUID explicit = uuidConfig(config, "sourceJudgmentExperimentId");
        if (explicit != null) {
            return explicit;
        }
        int corpusSize = intConfig(config, "corpusSize", properties.getMaxDocuments());
        if (corpusSize == 100 && properties.getSourceJudgments100ExperimentId() != null) {
            return properties.getSourceJudgments100ExperimentId();
        }
        if ((corpusSize == 1000 || hasBalancedTargets(config))
                && properties.getSourceJudgments1000ExperimentId() != null) {
            return properties.getSourceJudgments1000ExperimentId();
        }
        if (properties.getSourceJudgments1000ExperimentId() != null) {
            return properties.getSourceJudgments1000ExperimentId();
        }
        return properties.getSourceJudgments100ExperimentId();
    }

    private List<RagEvaluationDocumentJudgment> selectedSourceJudgmentsForExperiment(
            Map<String, Object> config,
            List<RagEvaluationDocumentJudgment> sourceJudgments) {
        if (hasBalancedTargets(config)) {
            return selectedJudgments(config, sourceJudgments);
        }
        int corpusSize = intConfig(config, "corpusSize", properties.getMaxDocuments());
        if (corpusSize > 0 && sourceJudgments.size() > corpusSize) {
            return sourceJudgments.stream().limit(corpusSize).toList();
        }
        return sourceJudgments;
    }

    private RagEvaluationDocumentJudgment copyJudgment(RagEvaluationDocumentJudgment source, UUID targetExperimentId) {
        return new RagEvaluationDocumentJudgment(
                null,
                targetExperimentId,
                source.documentId(),
                source.documentTitle(),
                source.effectiveLabel(),
                null,
                source.effectiveLabel(),
                source.keyEntities(),
                source.keyChunkIds(),
                source.llmReason(),
                source.reportPath(),
                source.confidence(),
                "Reused from source experiment " + source.experimentId(),
                Instant.now(),
                Instant.now()
        );
    }

    private void writeReusedJudgmentsReport(Path reportDir,
                                            UUID sourceExperimentId,
                                            List<RagEvaluationDocumentJudgment> selected) {
        try {
            Files.createDirectories(reportDir);
            long relevant = selected.stream().filter(j -> j.effectiveLabel() == JudgmentLabel.RELEVANT).count();
            long distractor = selected.stream().filter(j -> j.effectiveLabel() == JudgmentLabel.DISTRACTOR).count();
            long irrelevant = selected.stream().filter(j -> j.effectiveLabel() == JudgmentLabel.IRRELEVANT).count();
            Path path = reportDir.resolve("reused-judgments.md").toAbsolutePath().normalize();
            String markdown = """
                    # 标注复用记录

                    来源实验：%s

                    复用文献数：%d

                    相关：%d

                    干扰：%d

                    无关：%d

                    说明：LLM 标注阶段按当前实验流程视为一次性产物，本实验未重新调用文献标注模型。
                    """.formatted(sourceExperimentId, selected.size(), relevant, distractor, irrelevant);
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write reused judgments report", e);
        }
    }

    private void judgeCorpus(RagEvaluationExperimentRecord experiment, String question, Path reportDir) {
        UUID experimentId = experiment.experimentId();
        List<DocumentForEvaluation> documents = evaluationRepository.findCompletedDocuments();
        int maxDocuments = intConfig(experiment.config(), "corpusSize", properties.getMaxDocuments());
        if (maxDocuments > 0 && documents.size() > maxDocuments) {
            documents = documents.subList(0, maxDocuments);
        }
        Set<UUID> alreadyJudgedDocumentIds = evaluationRepository.findJudgments(experimentId).stream()
                .map(RagEvaluationDocumentJudgment::documentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int judged = alreadyJudgedDocumentIds.size();
        for (DocumentForEvaluation document : documents) {
            if (alreadyJudgedDocumentIds.contains(document.documentId())) {
                continue;
            }
            List<RetrievedChunk> chunks = reviewRepository.findAllChunksByDocumentId(document.documentId());
            RagEvaluationDocumentJudgment judgment = judgeDocument(
                    experimentId, question, reportDir, document, chunks == null ? List.of() : chunks);
            evaluationRepository.upsertJudgment(judgment);
            judged++;
        }
        log.info("RAG evaluation {} judged {}/{} documents", experimentId, judged, documents.size());
    }

    private RagEvaluationDocumentJudgment judgeDocument(UUID experimentId,
                                                       String question,
                                                       Path reportDir,
                                                       DocumentForEvaluation document,
                                                       List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return judgment(experimentId, document, JudgmentLabel.IRRELEVANT, List.of(), List.of(),
                    "No chunks available for this document.", null, 0.0);
        }
        List<LlmDocumentJudgmentOutput> batchOutputs = new ArrayList<>();
        int batchSize = Math.max(1, properties.getChunkBatchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            batchOutputs.add(callJudgmentModel(question, document, chunks.subList(start, end)));
        }
        LlmDocumentJudgmentOutput merged = mergeOutputs(batchOutputs);
        String reportPath = null;
        if (!properties.isJudgmentOnly() && merged.label() == JudgmentLabel.RELEVANT) {
            reportPath = writeReport(reportDir, document, question, merged);
        }
        return judgment(experimentId, document, merged.label(), merged.keyEntities(), merged.keyChunkIds(),
                merged.reason(), reportPath, merged.confidence());
    }

    LlmDocumentJudgmentOutput callJudgmentModel(String question,
                                                DocumentForEvaluation document,
                                                List<RetrievedChunk> chunks) {
        String baseUserMessage = """
                Question:
                %s

                Document:
                %s (%s)

                Chunks:
                %s
                """.formatted(question, firstNonBlank(document.title(), "Untitled document"),
                document.documentId(), renderChunks(chunks));
        int maxAttempts = properties == null ? 3 : Math.max(1, properties.getJudgmentMaxAttempts());
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String userMessage = judgmentUserMessage(baseUserMessage, attempt, lastError);
                Instant startedAt = Instant.now();
                ChatResponse response = reasoningChatClient.chatStandard(
                        SystemMessage.from(PromptResources.load(PromptCatalog.RAG_EVALUATION_DOCUMENT_JUDGMENT_SYSTEM)),
                        UserMessage.from(userMessage));
                recordChatUsage("document-judgment", response,
                        estimateTokens(PromptResources.load(PromptCatalog.RAG_EVALUATION_DOCUMENT_JUDGMENT_SYSTEM))
                                + estimateTokens(userMessage),
                        Duration.between(startedAt, Instant.now()).toMillis());
                AiMessage ai = response.aiMessage();
                String raw = ai == null ? null : ai.text();
                return parseJudgmentOutput(raw);
            } catch (Exception e) {
                lastError = e;
                log.warn("RAG evaluation LLM judgment failed for document {} on attempt {}/{}: {}",
                        document.documentId(), attempt, maxAttempts, e.getMessage());
                if (attempt >= maxAttempts || isNonRetryableJudgmentError(e)) {
                    throw new IllegalStateException("LLM judgment failed for document "
                            + document.documentId() + ": " + e.getMessage(), e);
                }
            }
        }
        throw new IllegalStateException("LLM judgment failed for document " + document.documentId()
                + ": " + (lastError == null ? "unknown error" : lastError.getMessage()), lastError);
    }

    private LlmDocumentJudgmentOutput parseJudgmentOutput(String raw) throws JsonProcessingException {
        return normalizeOutput(objectMapper.readValue(extractJson(raw), LlmDocumentJudgmentOutput.class));
    }

    private String judgmentUserMessage(String baseUserMessage, int attempt, Exception previousError) {
        if (attempt <= 1) {
            return baseUserMessage;
        }
        String error = previousError == null || previousError.getMessage() == null
                ? "The previous output was not accepted."
                : previousError.getMessage();
        return baseUserMessage + """

                Retry instruction:
                The previous output was invalid and could not be parsed as JSON:
                %s

                Return only one strict JSON object. Do not use Markdown fences.
                Every item in keyEntities and keyChunkIds must be a quoted JSON string.
                Example: "keyEntities": ["putrescine", "antimicrobial activity"].
                Do not output bare words such as putrescine without quotes.
                """.formatted(error);
    }

    private boolean isNonRetryableJudgmentError(Exception error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("arrearage")
                        || lower.contains("invalid api-key")
                        || lower.contains("invalid api key")
                        || lower.contains("access denied")
                        || lower.contains("unauthorized")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    LlmDocumentJudgmentOutput normalizeOutput(LlmDocumentJudgmentOutput output) {
        if (output == null) {
            return new LlmDocumentJudgmentOutput(JudgmentLabel.IRRELEVANT, List.of(), List.of(),
                    "Empty model output.", "", 0.0);
        }
        JudgmentLabel label = output.label() == null ? JudgmentLabel.IRRELEVANT : output.label();
        return new LlmDocumentJudgmentOutput(
                label,
                distinct(output.keyEntities()),
                distinct(output.keyChunkIds()),
                firstNonBlank(output.reason(), "No reason returned by model."),
                output.summary() == null ? "" : output.summary().trim(),
                clamp(output.confidence())
        );
    }

    private LlmDocumentJudgmentOutput mergeOutputs(List<LlmDocumentJudgmentOutput> outputs) {
        List<LlmDocumentJudgmentOutput> safe = outputs == null ? List.of() : outputs.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeOutput)
                .toList();
        if (safe.isEmpty()) {
            return normalizeOutput(null);
        }
        JudgmentLabel label = safe.stream().anyMatch(o -> o.label() == JudgmentLabel.RELEVANT)
                ? JudgmentLabel.RELEVANT
                : safe.stream().anyMatch(o -> o.label() == JudgmentLabel.DISTRACTOR)
                ? JudgmentLabel.DISTRACTOR
                : JudgmentLabel.IRRELEVANT;
        List<String> entities = safe.stream().flatMap(o -> o.keyEntities().stream())
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        List<String> chunkIds = safe.stream().flatMap(o -> o.keyChunkIds().stream())
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        String reason = safe.stream().map(LlmDocumentJudgmentOutput::reason)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.joining(" / "));
        String summary = safe.stream().filter(o -> o.label() == JudgmentLabel.RELEVANT)
                .map(LlmDocumentJudgmentOutput::summary)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n\n"));
        double confidence = safe.stream().mapToDouble(LlmDocumentJudgmentOutput::confidence).average().orElse(0.0);
        return new LlmDocumentJudgmentOutput(label, entities, chunkIds, reason, summary, confidence);
    }

    private RagEvaluationDocumentJudgment judgment(UUID experimentId,
                                                   DocumentForEvaluation document,
                                                   JudgmentLabel label,
                                                   List<String> keyEntities,
                                                   List<String> keyChunkIds,
                                                   String reason,
                                                   String reportPath,
                                                   double confidence) {
        return new RagEvaluationDocumentJudgment(
                null,
                experimentId,
                document.documentId(),
                document.title(),
                label,
                null,
                label,
                distinct(keyEntities),
                distinct(keyChunkIds),
                reason,
                reportPath,
                confidence,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    private void evaluateRetrieval(RagEvaluationExperimentRecord experiment,
                                   String question,
                                   QueryAnalysis analysis,
                                   List<String> queries) {
        UUID experimentId = experiment.experimentId();
        evaluationRepository.deleteHits(experimentId);
        List<RagEvaluationRetrievalHit> routeHits = new ArrayList<>();
        List<String> baselineQueries = baselineQueries(question);
        List<RagEvaluationDocumentJudgment> judgments = selectedJudgments(experiment.config(),
                evaluationRepository.findJudgments(experimentId));
        routeHits.addAll(retrieveRouteGroup(experimentId, baselineQueries,
                RetrievalRoute.BASELINE_FTS, RetrievalRoute.BASELINE_DENSE, RetrievalRoute.BASELINE_BM25));
        routeHits.addAll(retrieveRouteGroup(experimentId, queries,
                RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25));
        if (properties.isEntityEnhancedEnabled()) {
            List<RagEvaluationRetrievalHit> reviewEntityHits = retrieveRouteGroup(experimentId,
                    buildReviewEntityQueries(question, analysis, queries),
                    RetrievalRoute.REVIEW_ENTITY_FTS, RetrievalRoute.REVIEW_ENTITY_DENSE,
                    RetrievalRoute.REVIEW_ENTITY_BM25);
            routeHits.addAll(filterHighPrecisionReviewEntityHits(experiment.config(), reviewEntityHits));
            routeHits.addAll(retrieveRouteGroup(experimentId,
                    buildGoldEntityQueries(question, queries, judgments),
                    RetrievalRoute.GOLD_ENTITY_FTS, RetrievalRoute.GOLD_ENTITY_DENSE,
                    RetrievalRoute.GOLD_ENTITY_BM25));
        }
        if (resolveRetrievalScope(experiment.config()) == RetrievalScope.JUDGED_DOCUMENTS
                || hasBalancedTargets(experiment.config())) {
            Set<UUID> judgedDocumentIds = judgments.stream()
                    .map(RagEvaluationDocumentJudgment::documentId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            routeHits = filterAndRerankHits(routeHits, judgedDocumentIds);
        }
        List<RagEvaluationRetrievalHit> allHits = new ArrayList<>(routeHits);
        allHits.addAll(fuseOverall(experimentId, routeHits,
                List.of(RetrievalRoute.BASELINE_FTS, RetrievalRoute.BASELINE_DENSE, RetrievalRoute.BASELINE_BM25),
                RetrievalRoute.BASELINE_OVERALL));
        allHits.addAll(fuseOverall(experimentId, routeHits,
                List.of(RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25),
                RetrievalRoute.OVERALL));
        allHits.addAll(fuseOverall(experimentId, routeHits,
                List.of(RetrievalRoute.REVIEW_ENTITY_FTS, RetrievalRoute.REVIEW_ENTITY_DENSE,
                        RetrievalRoute.REVIEW_ENTITY_BM25),
                RetrievalRoute.REVIEW_ENTITY_OVERALL));
        allHits.addAll(fuseOverall(experimentId, routeHits,
                List.of(RetrievalRoute.GOLD_ENTITY_FTS, RetrievalRoute.GOLD_ENTITY_DENSE,
                        RetrievalRoute.GOLD_ENTITY_BM25),
                RetrievalRoute.GOLD_ENTITY_OVERALL));
        if (booleanConfig(experiment.config(), "rerankEnabled", properties.getRerank().isEnabled())) {
            allHits.addAll(rerankCandidateDocuments(experiment, question, allHits));
        }
        evaluationRepository.insertHits(experimentId, allHits);
    }

    List<RagEvaluationRetrievalHit> filterAndRerankHits(List<RagEvaluationRetrievalHit> hits,
                                                        Set<UUID> allowedDocumentIds) {
        if (hits == null || hits.isEmpty() || allowedDocumentIds == null || allowedDocumentIds.isEmpty()) {
            return List.of();
        }
        List<RagEvaluationRetrievalHit> result = new ArrayList<>();
        for (RetrievalRoute route : RetrievalRoute.values()) {
            if (isOverallRoute(route)) {
                continue;
            }
            int rank = 1;
            List<RagEvaluationRetrievalHit> routeHits = hits.stream()
                    .filter(hit -> hit.route() == route)
                    .filter(hit -> hit.documentId() != null && allowedDocumentIds.contains(hit.documentId()))
                    .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                    .toList();
            for (RagEvaluationRetrievalHit hit : routeHits) {
                result.add(new RagEvaluationRetrievalHit(hit.id(), hit.experimentId(), hit.route(),
                        hit.query(), rank++, hit.documentId(), hit.chunkId(), hit.score()));
            }
        }
        return result;
    }

    List<RagEvaluationRetrievalHit> filterHighPrecisionReviewEntityHits(Map<String, Object> config,
                                                                        List<RagEvaluationRetrievalHit> hits) {
        List<RagEvaluationRetrievalHit> safe = hits == null ? List.of() : hits;
        if (safe.isEmpty() || !booleanConfig(config, "reviewEntityHighPrecisionEnabled",
                properties.isReviewEntityHighPrecisionEnabled())) {
            return safe;
        }
        String marker = stringConfig(config, "reviewEntityHighPrecisionQueryMarker",
                properties.getReviewEntityHighPrecisionQueryMarker());
        String normalizedMarker = marker == null ? "" : marker.trim().toLowerCase(java.util.Locale.ROOT);
        List<RagEvaluationRetrievalHit> filtered = safe.stream()
                .filter(hit -> hit.route() == RetrievalRoute.REVIEW_ENTITY_DENSE
                        || hit.route() == RetrievalRoute.REVIEW_ENTITY_BM25)
                .filter(hit -> normalizedMarker.isBlank()
                        || firstNonBlank(hit.query(), "").toLowerCase(java.util.Locale.ROOT)
                        .contains(normalizedMarker))
                .toList();
        List<RagEvaluationRetrievalHit> result = new ArrayList<>();
        for (RetrievalRoute route : List.of(RetrievalRoute.REVIEW_ENTITY_DENSE, RetrievalRoute.REVIEW_ENTITY_BM25)) {
            int rank = 1;
            for (RagEvaluationRetrievalHit hit : filtered.stream()
                    .filter(hit -> hit.route() == route)
                    .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                    .toList()) {
                result.add(new RagEvaluationRetrievalHit(hit.id(), hit.experimentId(), hit.route(),
                        hit.query(), rank++, hit.documentId(), hit.chunkId(), hit.score()));
            }
        }
        return result;
    }

    List<String> baselineQueries(String question) {
        return distinct(List.of(firstNonBlank(question)));
    }

    List<String> buildReviewEntityQueries(String question,
                                          QueryAnalysis analysis,
                                          List<String> expandedQueries) {
        if (useBestRecallReviewEntities()) {
            return buildEntityEnhancedQueries(question, expandedQueries, properties.getReviewEntityBestRecallTerms());
        }
        List<String> entities = new ArrayList<>();
        if (analysis != null) {
            entities.addAll(analysis.keyEntities() == null ? List.of() : analysis.keyEntities());
            entities.addAll(analysis.keyConcepts() == null ? List.of() : analysis.keyConcepts());
        }
        return buildEntityEnhancedQueries(question, expandedQueries, entities);
    }

    private boolean useBestRecallReviewEntities() {
        Map<String, Object> config = activeConfig.get();
        String phase = stringConfig(config, "phase", "");
        return ExperimentPhase.RERANK_BEST_RECALL.name().equals(phase)
                || booleanConfig(config, "reviewEntityBestRecallEnabled",
                properties.isReviewEntityBestRecallEnabled());
    }

    List<String> buildGoldEntityQueries(String question,
                                        List<String> expandedQueries,
                                        List<RagEvaluationDocumentJudgment> judgments) {
        List<String> entities = (judgments == null ? List.<RagEvaluationDocumentJudgment>of() : judgments).stream()
                .filter(judgment -> judgment.effectiveLabel() == JudgmentLabel.RELEVANT)
                .flatMap(judgment -> (judgment.keyEntities() == null
                        ? List.<String>of()
                        : judgment.keyEntities()).stream())
                .toList();
        return buildEntityEnhancedQueries(question, expandedQueries, entities);
    }

    List<String> buildEntityEnhancedQueries(String question,
                                            List<String> expandedQueries,
                                            List<String> entities) {
        LinkedHashSet<String> queries = new LinkedHashSet<>(distinct(expandedQueries));
        List<String> entityTerms = distinct(entities).stream()
                .limit(maxEntityTerms())
                .toList();
        String baseQuestion = firstNonBlank(question);
        boolean stronglyRelated = booleanConfig(activeConfig.get(), "stronglyRelatedEntitiesEnabled", false);
        for (String entity : entityTerms) {
            if (!stronglyRelated) {
                queries.add(entity);
            }
            if (!baseQuestion.isBlank()) {
                queries.add(baseQuestion + " " + entity);
            }
        }
        List<String> expansionBases = distinct(expandedQueries).stream()
                .filter(query -> !query.equals(baseQuestion))
                .toList();
        int combinations = 0;
        int maxCombinations = maxEntityTerms();
        for (String expandedQuery : expansionBases) {
            for (String entity : entityTerms) {
                if (combinations >= maxCombinations) {
                    return List.copyOf(queries);
                }
                queries.add(expandedQuery + " " + entity);
                combinations++;
            }
        }
        return List.copyOf(queries);
    }

    private List<RagEvaluationRetrievalHit> retrieveFts(UUID experimentId, List<String> queries) {
        return retrieveFts(experimentId, queries, RetrievalRoute.FTS);
    }

    private List<RagEvaluationRetrievalHit> retrieveDense(UUID experimentId, List<String> queries) {
        return retrieveDense(experimentId, queries, RetrievalRoute.DENSE);
    }

    private List<RagEvaluationRetrievalHit> retrieveBm25(UUID experimentId, List<String> queries) {
        return retrieveBm25(experimentId, queries, RetrievalRoute.BM25);
    }

    private List<RagEvaluationRetrievalHit> retrieveRouteGroup(UUID experimentId,
                                                               List<String> queries,
                                                               RetrievalRoute ftsRoute,
                                                               RetrievalRoute denseRoute,
                                                               RetrievalRoute bm25Route) {
        List<String> safeQueries = distinct(queries);
        List<RagEvaluationRetrievalHit> hits = new ArrayList<>();
        hits.addAll(retrieveFts(experimentId, safeQueries, ftsRoute));
        hits.addAll(retrieveDense(experimentId, safeQueries, denseRoute));
        hits.addAll(retrieveBm25(experimentId, safeQueries, bm25Route));
        return hits;
    }

    private List<RagEvaluationRetrievalHit> retrieveFts(UUID experimentId,
                                                        List<String> queries,
                                                        RetrievalRoute route) {
        Instant startedAt = Instant.now();
        List<RagEvaluationRetrievalHit> hits = new ArrayList<>();
        int rank = 1;
        Set<String> seen = new LinkedHashSet<>();
        for (String query : queries) {
            List<UUID> documentIds;
            try {
                documentIds = reviewRepository.searchDocumentsByFts(query, ftsMaxResults());
            } catch (Exception e) {
                log.warn("RAG evaluation FTS query failed for '{}': {}", query, e.getMessage());
                continue;
            }
            for (UUID documentId : documentIds) {
                List<RetrievedChunk> chunks = reviewRepository.findPriorityChunksByDocumentIds(
                        Set.of(documentId), priorityChunksPerFtsDocument());
                if (chunks.isEmpty()) {
                    String key = documentId + "|";
                    if (seen.add(key)) {
                        hits.add(new RagEvaluationRetrievalHit(null, experimentId, route,
                                query, rank++, documentId, null, 0.0));
                    }
                    continue;
                }
                for (RetrievedChunk chunk : chunks) {
                    String key = documentId + "|" + chunk.chunkId();
                    if (seen.add(key)) {
                        hits.add(new RagEvaluationRetrievalHit(null, experimentId, route,
                                query, rank++, documentId, chunk.chunkId(), chunk.score()));
                    }
                }
            }
        }
        recordUsage("retrieval-" + route.name().toLowerCase(java.util.Locale.ROOT),
                "local-fts", estimatedQueryTokens(queries), 0L, 0L, 0L,
                Duration.between(startedAt, Instant.now()).toMillis(), queries == null ? 0 : queries.size());
        return hits;
    }

    private List<RagEvaluationRetrievalHit> retrieveDense(UUID experimentId,
                                                          List<String> queries,
                                                          RetrievalRoute route) {
        Instant startedAt = Instant.now();
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(quwenEmbeddingModel)
                .maxResults(denseMaxResults())
                .minScore(properties.getDenseMinScore())
                .build();
        List<RagEvaluationRetrievalHit> hits = retrieveContentRoute(experimentId, route, queries, retriever);
        recordUsage("retrieval-" + route.name().toLowerCase(java.util.Locale.ROOT),
                firstNonBlank(embeddingModelName, "embedding-model"), estimatedQueryTokens(queries),
                0L, 0L, 0L, Duration.between(startedAt, Instant.now()).toMillis(),
                queries == null ? 0 : queries.size());
        return hits;
    }

    private List<RagEvaluationRetrievalHit> retrieveBm25(UUID experimentId,
                                                         List<String> queries,
                                                         RetrievalRoute route) {
        Instant startedAt = Instant.now();
        Bm25ContentRetriever retriever = new Bm25ContentRetriever(
                bm25IndexService, objectMapper, bm25MaxResults());
        List<RagEvaluationRetrievalHit> hits = retrieveContentRoute(experimentId, route, queries, retriever);
        recordUsage("retrieval-" + route.name().toLowerCase(java.util.Locale.ROOT),
                "local-bm25", estimatedQueryTokens(queries), 0L, 0L, 0L,
                Duration.between(startedAt, Instant.now()).toMillis(), queries == null ? 0 : queries.size());
        return hits;
    }

    private List<RagEvaluationRetrievalHit> retrieveContentRoute(UUID experimentId,
                                                                 RetrievalRoute route,
                                                                 List<String> queries,
                                                                 ContentRetriever retriever) {
        List<RagEvaluationRetrievalHit> hits = new ArrayList<>();
        int rank = 1;
        Set<String> seen = new LinkedHashSet<>();
        for (String query : queries) {
            for (Content content : retriever.retrieve(Query.from(query))) {
                TextSegment segment = content.textSegment();
                String chunkId = segment.metadata().getString("chunk_id");
                UUID documentId = parseUuid(segment.metadata().getString("document_id"));
                String key = (chunkId == null || chunkId.isBlank())
                        ? String.valueOf(documentId) + "|" + segment.text().hashCode()
                        : chunkId;
                if (!seen.add(key)) {
                    continue;
                }
                Object scoreObj = content.metadata() == null ? null : content.metadata().get(ContentMetadata.SCORE);
                double score = scoreObj instanceof Number number ? number.doubleValue() : 0.0;
                hits.add(new RagEvaluationRetrievalHit(null, experimentId, route, query, rank++,
                        documentId, chunkId, score));
            }
        }
        return hits;
    }

    List<RagEvaluationRetrievalHit> fuseOverall(UUID experimentId, List<RagEvaluationRetrievalHit> routeHits) {
        return fuseOverall(experimentId, routeHits,
                List.of(RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25),
                RetrievalRoute.OVERALL);
    }

    List<RagEvaluationRetrievalHit> fuseOverall(UUID experimentId,
                                                List<RagEvaluationRetrievalHit> routeHits,
                                                List<RetrievalRoute> inputRoutes,
                                                RetrievalRoute outputRoute) {
        Map<String, OverallAccumulator> scores = new LinkedHashMap<>();
        for (RetrievalRoute route : inputRoutes == null ? List.<RetrievalRoute>of() : inputRoutes) {
            List<RagEvaluationRetrievalHit> hits = routeHits.stream()
                    .filter(hit -> hit.route() == route)
                    .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                    .toList();
            for (RagEvaluationRetrievalHit hit : hits) {
                String key = hit.chunkId() == null || hit.chunkId().isBlank()
                        ? "doc:" + hit.documentId()
                        : "chunk:" + hit.chunkId();
                OverallAccumulator acc = scores.computeIfAbsent(key,
                        ignored -> new OverallAccumulator(hit.documentId(), hit.chunkId()));
                acc.score += 1.0 / (rrfK() + hit.rank());
                acc.queries.add(hit.query());
            }
        }
        List<OverallAccumulator> ranked = scores.values().stream()
                .sorted(Comparator.comparingDouble(OverallAccumulator::score).reversed())
                .toList();
        List<RagEvaluationRetrievalHit> result = new ArrayList<>();
        int rank = 1;
        for (OverallAccumulator acc : ranked) {
            result.add(new RagEvaluationRetrievalHit(null, experimentId, outputRoute,
                    String.join(" | ", acc.queries), rank++, acc.documentId, acc.chunkId, acc.score));
        }
        return result;
    }

    private RagEvaluationMetrics recalculateMetrics(UUID experimentId) {
        return metricsService.calculate(
                evaluationRepository.findJudgments(experimentId),
                evaluationRepository.findHits(experimentId)
        );
    }

    private RagEvaluationMetrics recalculateMetrics(RagEvaluationExperimentRecord experiment) {
        return metricsService.calculate(
                selectedJudgments(experiment.config(), evaluationRepository.findJudgments(experiment.experimentId())),
                evaluationRepository.findHits(experiment.experimentId())
        );
    }

    private List<RagEvaluationRetrievalHit> rerankCandidateDocuments(RagEvaluationExperimentRecord experiment,
                                                                     String question,
                                                                     List<RagEvaluationRetrievalHit> allHits) {
        List<RagEvaluationRetrievalHit> candidateHits = (allHits == null ? List.<RagEvaluationRetrievalHit>of() : allHits)
                .stream()
                .filter(hit -> hit.route() == RetrievalRoute.REVIEW_ENTITY_OVERALL)
                .filter(hit -> hit.documentId() != null)
                .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                .toList();
        if (candidateHits.isEmpty()) {
            return List.of();
        }
        Map<UUID, RagEvaluationRetrievalHit> firstHitByDocument = new LinkedHashMap<>();
        for (RagEvaluationRetrievalHit hit : candidateHits) {
            firstHitByDocument.putIfAbsent(hit.documentId(), hit);
        }
        int maxDocuments = intConfig(experiment.config(), "documentRerankMaxDocuments",
                properties.getDocumentRerankMaxDocuments());
        List<RagEvaluationRetrievalHit> documentCandidates = firstHitByDocument.values().stream()
                .limit(maxDocuments <= 0 ? Long.MAX_VALUE : maxDocuments)
                .toList();
        Set<UUID> documentIds = documentCandidates.stream()
                .map(RagEvaluationRetrievalHit::documentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<RetrievedChunk>> chunksByDocument = reviewRepository.findChunksByDocumentIds(documentIds).stream()
                .filter(chunk -> chunk.documentId() != null)
                .collect(Collectors.groupingBy(RetrievedChunk::documentId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Set<String>> preferredChunkIds = candidateHits.stream()
                .filter(hit -> hit.chunkId() != null && !hit.chunkId().isBlank())
                .collect(Collectors.groupingBy(RagEvaluationRetrievalHit::documentId, LinkedHashMap::new,
                        Collectors.mapping(RagEvaluationRetrievalHit::chunkId,
                                Collectors.toCollection(LinkedHashSet::new))));
        int chunksPerDocument = Math.max(1, intConfig(experiment.config(), "documentRerankMaxChunksPerDocument",
                properties.getDocumentRerankMaxChunksPerDocument()));
        List<RerankDocument> documents = new ArrayList<>();
        for (RagEvaluationRetrievalHit candidate : documentCandidates) {
            List<RetrievedChunk> chunks = representativeChunks(
                    chunksByDocument.get(candidate.documentId()),
                    preferredChunkIds.get(candidate.documentId()),
                    chunksPerDocument);
            documents.add(new RerankDocument(candidate.documentId().toString(),
                    renderDocumentForRerank(candidate, chunks)));
        }
        String model = stringConfig(experiment.config(), "rerankModel", properties.getRerank().getModel());
        RerankResult result = qwenRerankClient.rerank(question, documents, model);
        recordUsage("document-rerank", model, estimatedDocumentTokens(question, documents), 0L, 0L,
                result.providerTotalTokens(), result.elapsedMs(), 1);
        Map<String, Double> scoreByDocumentId = result.scores().stream()
                .collect(Collectors.toMap(RerankScore::id, RerankScore::score,
                        (left, right) -> left, LinkedHashMap::new));
        List<RagEvaluationRetrievalHit> reranked = documentCandidates.stream()
                .filter(hit -> scoreByDocumentId.getOrDefault(hit.documentId().toString(), 0.0)
                        >= documentRerankMinScore(experiment.config()))
                .sorted(Comparator
                        .comparingDouble((RagEvaluationRetrievalHit hit) ->
                                scoreByDocumentId.getOrDefault(hit.documentId().toString(), hit.score())).reversed()
                        .thenComparingInt(RagEvaluationRetrievalHit::rank))
                .toList();
        List<RagEvaluationRetrievalHit> resultHits = new ArrayList<>();
        int rank = 1;
        for (RagEvaluationRetrievalHit hit : reranked) {
            resultHits.add(new RagEvaluationRetrievalHit(null, experiment.experimentId(),
                    RetrievalRoute.RERANK_DOCUMENT_OVERALL, hit.query(), rank++,
                    hit.documentId(), null,
                    scoreByDocumentId.getOrDefault(hit.documentId().toString(), hit.score())));
        }
        return resultHits;
    }

    private double documentRerankMinScore(Map<String, Object> config) {
        return doubleConfig(config, "documentRerankMinScore", properties.getDocumentRerankMinScore());
    }

    private List<RetrievedChunk> representativeChunks(List<RetrievedChunk> chunks,
                                                      Set<String> preferredChunkIds,
                                                      int limit) {
        List<RetrievedChunk> safe = chunks == null ? List.of() : chunks;
        if (safe.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, RetrievedChunk> selected = new LinkedHashMap<>();
        Set<String> preferred = preferredChunkIds == null ? Set.of() : preferredChunkIds;
        for (String chunkId : preferred) {
            safe.stream()
                    .filter(chunk -> Objects.equals(chunk.chunkId(), chunkId))
                    .findFirst()
                    .ifPresent(chunk -> selected.putIfAbsent(chunk.chunkId(), chunk));
            if (selected.size() >= limit) {
                return List.copyOf(selected.values());
            }
        }
        for (RetrievedChunk chunk : safe) {
            String key = firstNonBlank(chunk.chunkId(), String.valueOf(selected.size()));
            selected.putIfAbsent(key, chunk);
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(selected.values());
    }

    private String renderDocumentForRerank(RagEvaluationRetrievalHit candidate, List<RetrievedChunk> chunks) {
        String title = chunks == null || chunks.isEmpty()
                ? ""
                : firstNonBlank(chunks.get(0).documentTitle(), "");
        StringBuilder builder = new StringBuilder();
        builder.append("Document ID: ").append(candidate.documentId()).append('\n');
        if (!title.isBlank()) {
            builder.append("Title: ").append(title).append('\n');
        }
        builder.append("Candidate query evidence: ").append(firstNonBlank(candidate.query(), "")).append('\n');
        int index = 1;
        for (RetrievedChunk chunk : chunks == null ? List.<RetrievedChunk>of() : chunks) {
            builder.append("\nEvidence ").append(index++).append('\n')
                    .append("Section: ").append(firstNonBlank(chunk.sectionPath(), "-")).append('\n')
                    .append(firstNonBlank(chunk.text(), ""));
        }
        return truncateRerankDocument(builder.toString());
    }

    private String truncateRerankDocument(String text) {
        int maxChars = Math.max(1000, intConfig(activeConfig.get(), "documentRerankMaxDocumentChars",
                properties.getDocumentRerankMaxDocumentChars()));
        String safe = firstNonBlank(text, "");
        if (safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, maxChars) + "\n[truncated for qwen3-vl-rerank input limit]";
    }

    private long estimatedDocumentTokens(String question, List<RerankDocument> documents) {
        long total = estimateTokens(question);
        for (RerankDocument document : documents == null ? List.<RerankDocument>of() : documents) {
            total += estimateTokens(document.text());
        }
        return total;
    }

    private String effectiveQuestion(RagEvaluationExperimentRecord experiment, Path reportDir) {
        if (!booleanConfig(experiment.config(), "questionRewriteEnabled", false)) {
            return experiment.question();
        }
        Instant startedAt = Instant.now();
        QueryAnalysis analysis = queryAnalyzerService.analyze(experiment.question());
        long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
        String rewritten = firstNonBlank(analysis.mainQuestion(), analysis.displayMainQuestion(), experiment.question());
        recordUsage("question-rewrite", chatModelName(), estimateTokens(experiment.question()), 0L, 0L,
                0L, elapsedMs, 1);
        writeQuestionRewriteReport(reportDir, experiment.question(), rewritten, analysis);
        evaluationRepository.mergeConfig(experiment.experimentId(), Map.of(
                "rewrittenQuestion", rewritten,
                "rewriteLanguageCode", firstNonBlank(analysis.languageCode(), "")
        ));
        return rewritten;
    }

    private QueryAnalysis timedQueryAnalysis(String question) {
        Instant startedAt = Instant.now();
        QueryAnalysis analysis = queryAnalyzerService.analyze(question);
        recordUsage("query-analysis", chatModelName(), estimateTokens(question), 0L, 0L,
                0L, Duration.between(startedAt, Instant.now()).toMillis(), 1);
        return analysis;
    }

    private List<String> timedQueryExpansion(QueryAnalysis analysis) {
        Instant startedAt = Instant.now();
        List<String> queries = queryExpansionService.expand(analysis);
        long estimatedTokens = estimateTokens(analysis == null ? "" : String.valueOf(analysis));
        recordUsage("query-expansion", "local-query-expansion", estimatedTokens, 0L, 0L,
                0L, Duration.between(startedAt, Instant.now()).toMillis(), 1);
        return queries;
    }

    private List<RagEvaluationDocumentJudgment> selectedJudgments(Map<String, Object> config,
                                                                  List<RagEvaluationDocumentJudgment> judgments) {
        List<RagEvaluationDocumentJudgment> safe = judgments == null ? List.of() : judgments;
        int relevant = intConfig(config, "targetRelevantDocuments", 0);
        int distractor = intConfig(config, "targetDistractorDocuments", 0);
        int irrelevant = intConfig(config, "targetIrrelevantDocuments", 0);
        if (relevant <= 0 && distractor <= 0 && irrelevant <= 0) {
            return safe;
        }
        int totalTarget = Math.max(0, relevant) + Math.max(0, distractor) + Math.max(0, irrelevant);
        if (totalTarget > 0 && safe.size() <= totalTarget) {
            return safe;
        }
        List<RagEvaluationDocumentJudgment> selected = new ArrayList<>();
        selected.addAll(limitByLabel(safe, JudgmentLabel.RELEVANT, relevant));
        selected.addAll(limitByLabel(safe, JudgmentLabel.DISTRACTOR, distractor));
        selected.addAll(limitByLabel(safe, JudgmentLabel.IRRELEVANT, irrelevant));
        return selected;
    }

    private List<RagEvaluationDocumentJudgment> limitByLabel(List<RagEvaluationDocumentJudgment> judgments,
                                                             JudgmentLabel label,
                                                             int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<RagEvaluationDocumentJudgment> candidates = new ArrayList<>(judgments.stream()
                .filter(judgment -> judgment.effectiveLabel() == label)
                .toList());
        Collections.shuffle(candidates);
        return candidates.stream().limit(limit).toList();
    }

    private boolean hasBalancedTargets(Map<String, Object> config) {
        return intConfig(config, "targetRelevantDocuments", 0) > 0
                || intConfig(config, "targetDistractorDocuments", 0) > 0
                || intConfig(config, "targetIrrelevantDocuments", 0) > 0;
    }

    private RagEvaluationMetrics attachTelemetry(RagEvaluationMetrics metrics, Instant startedAt) {
        RagEvaluationMetrics safe = metrics == null ? RagEvaluationMetrics.empty() : metrics;
        List<ModelUsageMetric> usage = currentTelemetry().toMetrics();
        return new RagEvaluationMetrics(safe.routes(), safe.calculatedAt(), usage,
                Duration.between(startedAt, Instant.now()).toMillis());
    }

    private void recordChatUsage(String phase, ChatResponse response, long estimatedInputTokens, long elapsedMs) {
        TokenUsage usage = response == null ? null : response.tokenUsage();
        recordUsage(phase, chatModelName(), estimatedInputTokens,
                tokenValue(usage == null ? null : usage.inputTokenCount()),
                tokenValue(usage == null ? null : usage.outputTokenCount()),
                tokenValue(usage == null ? null : usage.totalTokenCount()),
                elapsedMs, 1);
    }

    private void recordUsage(String phase, String model, long estimatedInputTokens,
                             Long providerInputTokens, Long providerOutputTokens,
                             Long providerTotalTokens, Long elapsedMs, int calls) {
        ExperimentTelemetry current = telemetry.get();
        if (current != null) {
            current.record(phase, model, estimatedInputTokens, providerInputTokens,
                    providerOutputTokens, providerTotalTokens, elapsedMs, calls);
        }
    }

    private ExperimentTelemetry currentTelemetry() {
        ExperimentTelemetry current = telemetry.get();
        return current == null ? new ExperimentTelemetry() : current;
    }

    private long estimateTokens(String text) {
        if (tokenCountEstimator == null) {
            return text == null ? 0L : Math.max(1L, text.length() / 4L);
        }
        return tokenCountEstimator.estimateTokenCountInText(text);
    }

    private long estimatedQueryTokens(List<String> queries) {
        return (queries == null ? List.<String>of() : queries).stream()
                .mapToLong(this::estimateTokens)
                .sum();
    }

    private Long tokenValue(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private String chatModelName() {
        return modelProperties == null || modelProperties.getChatModel() == null
                ? "qwen-chat"
                : firstNonBlank(modelProperties.getChatModel().getModelName(), "qwen-chat");
    }

    private List<RagEvaluationExperimentRequest> requiredSuiteSpecs(String question) {
        return List.of(
                new RagEvaluationExperimentRequest(question, RetrievalScope.JUDGED_DOCUMENTS,
                        ExperimentPhase.BUBBLE, 100, null, null, null,
                        false, false, false, null, null),
                new RagEvaluationExperimentRequest(question, RetrievalScope.JUDGED_DOCUMENTS,
                        ExperimentPhase.QUESTION_REWRITE_ENTITY, 100, null, null, null,
                        true, true, false, null, null),
                new RagEvaluationExperimentRequest(question, RetrievalScope.JUDGED_DOCUMENTS,
                        ExperimentPhase.QUESTION_REWRITE_ENTITY, 1000, null, null, null,
                        true, true, false, null, null),
                new RagEvaluationExperimentRequest(question, RetrievalScope.JUDGED_DOCUMENTS,
                        ExperimentPhase.BALANCED_500, 0, 100, 100, 300,
                        false, false, false, null, null),
                new RagEvaluationExperimentRequest(question, RetrievalScope.JUDGED_DOCUMENTS,
                        ExperimentPhase.RERANK_BEST_RECALL, 100, null, null, null,
                        false, false, true, properties.getRerank().getModel(), null)
        );
    }

    private RetrievalScope resolveScope(RagEvaluationExperimentRequest request) {
        return request == null || request.retrievalScope() == null
                ? properties.getRetrievalScope()
                : request.retrievalScope();
    }

    private String phaseName(ExperimentPhase phase) {
        if (phase == null) {
            return "自定义实验";
        }
        return switch (phase) {
            case BUBBLE -> "冒泡实验";
            case QUESTION_REWRITE_ENTITY -> "实验一-问题重写与实体检索";
            case BALANCED_500 -> "实验二-五百篇平衡数据集";
            case RERANK_BEST_RECALL -> "实验三-97召回文献级Rerank";
            case ANTIMICROBIAL_PAPER_SUMMARY -> "实验四-百篇抑菌化合物逐篇总结";
        };
    }

    private int routeMaxResults(RagEvaluationExperimentRequest request, String route) {
        if (request != null && request.phase() == ExperimentPhase.RERANK_BEST_RECALL) {
            return switch (route) {
                case "fts", "bm25" -> 100;
                case "dense" -> 300;
                default -> 100;
            };
        }
        return switch (route) {
            case "fts" -> properties.getFtsMaxResults();
            case "dense" -> properties.getDenseMaxResults();
            case "bm25" -> properties.getBm25MaxResults();
            default -> 100;
        };
    }

    private int priorityChunks(RagEvaluationExperimentRequest request) {
        return properties.getPriorityChunksPerFtsDocument();
    }

    private int rrfK(RagEvaluationExperimentRequest request) {
        return properties.getRrfK();
    }

    private int intConfig(Map<String, Object> config, String key, int fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double doubleConfig(Map<String, Object> config, String key, double fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanConfig(Map<String, Object> config, String key, boolean fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    private String stringConfig(Map<String, Object> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null ? fallback : firstNonBlank(String.valueOf(value), fallback);
    }

    private UUID uuidConfig(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return UUID.fromString(text.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private int ftsMaxResults() {
        return intConfig(activeConfig.get(), "ftsMaxResults", properties.getFtsMaxResults());
    }

    private int denseMaxResults() {
        return intConfig(activeConfig.get(), "denseMaxResults", properties.getDenseMaxResults());
    }

    private int bm25MaxResults() {
        return intConfig(activeConfig.get(), "bm25MaxResults", properties.getBm25MaxResults());
    }

    private int priorityChunksPerFtsDocument() {
        return intConfig(activeConfig.get(), "priorityChunksPerFtsDocument",
                properties.getPriorityChunksPerFtsDocument());
    }

    private int rrfK() {
        return intConfig(activeConfig.get(), "rrfK", properties.getRrfK());
    }

    private void writeQuestionRewriteReport(Path reportDir, String originalQuestion,
                                            String rewrittenQuestion, QueryAnalysis analysis) {
        try {
            Files.createDirectories(reportDir);
            Path path = reportDir.resolve("question-rewrite.md").toAbsolutePath().normalize();
            String markdown = """
                    # 闂閲嶅啓璁板綍

                    鍘熷闂锛?s

                    閲嶅啓闂锛?s

                    鍏抽敭瀹炰綋锛?s

                    鍏抽敭姒傚康锛?s
                    """.formatted(
                    firstNonBlank(originalQuestion, ""),
                    firstNonBlank(rewrittenQuestion, ""),
                    String.join(", ", analysis.keyEntities() == null ? List.of() : analysis.keyEntities()),
                    String.join(", ", analysis.keyConcepts() == null ? List.of() : analysis.keyConcepts()));
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write question rewrite report", e);
        }
    }

    private static class ExperimentTelemetry {
        private final Map<String, UsageAccumulator> usage = new LinkedHashMap<>();

        private void record(String phase, String model, long estimatedInputTokens,
                            Long providerInputTokens, Long providerOutputTokens,
                            Long providerTotalTokens, Long elapsedMs, int calls) {
            String key = firstNonBlankStatic(phase, "unknown") + "|" + firstNonBlankStatic(model, "unknown");
            UsageAccumulator acc = usage.computeIfAbsent(key,
                    ignored -> new UsageAccumulator(firstNonBlankStatic(phase, "unknown"),
                            firstNonBlankStatic(model, "unknown")));
            acc.estimatedInputTokens += Math.max(0L, estimatedInputTokens);
            acc.providerInputTokens += defaultLong(providerInputTokens);
            acc.providerOutputTokens += defaultLong(providerOutputTokens);
            acc.providerTotalTokens += defaultLong(providerTotalTokens);
            acc.elapsedMs += defaultLong(elapsedMs);
            acc.calls += Math.max(0, calls);
        }

        private List<ModelUsageMetric> toMetrics() {
            return usage.values().stream()
                    .map(acc -> new ModelUsageMetric(acc.phase, acc.model, acc.estimatedInputTokens,
                            acc.providerInputTokens, acc.providerOutputTokens, acc.providerTotalTokens,
                            acc.elapsedMs, acc.calls))
                    .toList();
        }

        private static long defaultLong(Long value) {
            return value == null ? 0L : value;
        }

        private static String firstNonBlankStatic(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private static class UsageAccumulator {
        private final String phase;
        private final String model;
        private long estimatedInputTokens;
        private long providerInputTokens;
        private long providerOutputTokens;
        private long providerTotalTokens;
        private long elapsedMs;
        private int calls;

        private UsageAccumulator(String phase, String model) {
            this.phase = phase;
            this.model = model;
        }
    }

    private String writeReport(Path reportDir, DocumentForEvaluation document,
                               String question, LlmDocumentJudgmentOutput output) {
        try {
            Files.createDirectories(reportDir);
            Path path = reportDir.resolve(document.documentId() + ".md").toAbsolutePath().normalize();
            String markdown = """
                    # %s

                    Question: %s

                    Document ID: %s

                    Label: %s

                    Key entities: %s

                    Key chunks: %s

                    ## Summary

                    %s

                    ## Reason

                    %s
                    """.formatted(
                    firstNonBlank(document.title(), "Untitled document"),
                    question,
                    document.documentId(),
                    output.label(),
                    String.join(", ", output.keyEntities()),
                    String.join(", ", output.keyChunkIds()),
                    firstNonBlank(output.summary(), "No summary returned."),
                    output.reason());
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
            return path.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write RAG evaluation report", e);
        }
    }

    private RetrievalScope resolveRetrievalScope(Map<String, Object> config) {
        Object value = config == null ? null : config.get("retrievalScope");
        if (value instanceof RetrievalScope scope) {
            return scope;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return RetrievalScope.valueOf(text.trim());
            } catch (IllegalArgumentException ignored) {
                return properties.getRetrievalScope();
            }
        }
        return properties.getRetrievalScope();
    }

    private Map<String, Object> config(RetrievalScope retrievalScope,
                                       RagEvaluationExperimentRequest request,
                                       UUID suiteId) {
        Map<String, Object> config = new LinkedHashMap<>();
        boolean rerankBestRecallPhase = request != null
                && request.phase() == ExperimentPhase.RERANK_BEST_RECALL;
        boolean reviewEntityHighPrecisionEnabled = !rerankBestRecallPhase
                && properties.isReviewEntityHighPrecisionEnabled();
        String reviewEntityProfile = properties.isReviewEntityBestRecallEnabled() && !reviewEntityHighPrecisionEnabled
                ? "S1 dense300 review entity (Recall 97.44%, Precision 48.10%)"
                : "default";
        config.put("chunkBatchSize", properties.getChunkBatchSize());
        config.put("judgmentMaxAttempts", properties.getJudgmentMaxAttempts());
        config.put("ftsMaxResults", routeMaxResults(request, "fts"));
        config.put("denseMaxResults", routeMaxResults(request, "dense"));
        config.put("denseMinScore", properties.getDenseMinScore());
        config.put("bm25MaxResults", routeMaxResults(request, "bm25"));
        config.put("priorityChunksPerFtsDocument", priorityChunks(request));
        config.put("rrfK", rrfK(request));
        config.put("maxDocuments", properties.getMaxDocuments());
        config.put("corpusSize", request == null || request.corpusSize() == null
                ? properties.getMaxDocuments()
                : request.corpusSize());
        config.put("entityEnhancedEnabled", properties.isEntityEnhancedEnabled());
        config.put("judgmentOnly", properties.isJudgmentOnly());
        config.put("maxEntityTerms", properties.getMaxEntityTerms());
        config.put("retrievalScope", retrievalScope.name());
        config.put("reviewEntityBestRecallEnabled", properties.isReviewEntityBestRecallEnabled());
        config.put("reviewEntityProfile", reviewEntityProfile);
        config.put("reviewEntityHighPrecisionEnabled", reviewEntityHighPrecisionEnabled);
        config.put("reviewEntityHighPrecisionQueryMarker", properties.getReviewEntityHighPrecisionQueryMarker());
        config.put("reviewEntityBestRecallTerms", properties.getReviewEntityBestRecallTerms());
        config.put("documentRerankMaxDocuments", properties.getDocumentRerankMaxDocuments());
        config.put("documentRerankMaxChunksPerDocument", properties.getDocumentRerankMaxChunksPerDocument());
        config.put("documentRerankMaxDocumentChars", properties.getDocumentRerankMaxDocumentChars());
        config.put("documentRerankMinScore", properties.getDocumentRerankMinScore());
        if (suiteId != null) {
            config.put("suiteId", suiteId.toString());
        }
        if (request != null) {
            if (request.phase() != null) {
                config.put("phase", request.phase().name());
                config.put("phaseName", phaseName(request.phase()));
            }
            config.put("targetRelevantDocuments", request.targetRelevantDocuments());
            config.put("targetDistractorDocuments", request.targetDistractorDocuments());
            config.put("targetIrrelevantDocuments", request.targetIrrelevantDocuments());
            config.put("questionRewriteEnabled", Boolean.TRUE.equals(request.questionRewriteEnabled()));
            config.put("stronglyRelatedEntitiesEnabled", Boolean.TRUE.equals(request.stronglyRelatedEntitiesEnabled()));
            config.put("rerankEnabled", Boolean.TRUE.equals(request.rerankEnabled()));
            config.put("rerankModel", firstNonBlank(request.rerankModel(), properties.getRerank().getModel()));
            if (request.sourceJudgmentExperimentId() != null) {
                config.put("sourceJudgmentExperimentId", request.sourceJudgmentExperimentId().toString());
            }
        }
        return config;
    }

    private String renderChunks(List<RetrievedChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            builder.append("\n--- chunk_id=").append(chunk.chunkId())
                    .append("; section=").append(firstNonBlank(chunk.sectionPath(), "-"))
                    .append(" ---\n")
                    .append(firstNonBlank(chunk.text(), ""));
        }
        return builder.toString();
    }

    String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private int maxEntityTerms() {
        return properties == null ? 20 : Math.max(1, intConfig(activeConfig.get(),
                "maxEntityTerms", properties.getMaxEntityTerms()));
    }

    private boolean isOverallRoute(RetrievalRoute route) {
        return route == RetrievalRoute.OVERALL || route.name().endsWith("_OVERALL");
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static class OverallAccumulator {
        private final UUID documentId;
        private final String chunkId;
        private final Set<String> queries = new LinkedHashSet<>();
        private double score;

        private OverallAccumulator(UUID documentId, String chunkId) {
            this.documentId = documentId;
            this.chunkId = chunkId;
        }

        private double score() {
            return score;
        }
    }
}
