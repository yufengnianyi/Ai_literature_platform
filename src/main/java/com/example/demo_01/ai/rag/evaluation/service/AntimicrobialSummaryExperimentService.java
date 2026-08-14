package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactLoader;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.rag.evaluation.config.RagEvaluationProperties;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository.AntimicrobialDocument;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.rag.repository.RagChunkRepository;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AntimicrobialSummaryExperimentService {

    static final String EXPERIMENT_QUESTION = "请逐篇判定并总结与抑菌化合物及卵菌活性相关的文献";
    static final String CLASSIFICATION_PHASE = "document-classification";
    static final String SUMMARY_PHASE = "paper-table-synthesis";
    static final String CLASSIFICATION_PROMPT_VERSION = "antimicrobial-classification-v1";
    static final String SUMMARY_PROMPT_VERSION = "antimicrobial-summary-16-column-v1";
    static final List<String> TABLE_HEADERS = List.of(
            "化合物原文名称", "化合物标准名称", "结构类型", "来源类别", "来源具体描述",
            "测试卵菌拉丁名", "实验方法", "活性数据", "阳性对照", "作用靶标/机制",
            "靶标验证方法", "细胞毒性", "抗性/交叉抗性", "协同增效", "参考文献", "专利信息"
    );

    @Resource
    private RagEvaluationProperties properties;

    @Resource
    private RagEvaluationRepository evaluationRepository;

    @Resource
    private RagChunkRepository ragChunkRepository;

    @Resource
    private PreprocessArtifactLoader artifactLoader;

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private DashScopeModelProperties modelProperties;

    @Resource
    private TokenCountEstimator tokenCountEstimator;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    @Qualifier("reviewTaskExecutor")
    private TaskExecutor taskExecutor;

    public RagEvaluationAcceptedResponse submit(String userId) {
        UUID sourceExperimentId = properties.getAntimicrobialSummarySourceExperimentId();
        List<AntimicrobialDocument> documents =
                evaluationRepository.findAntimicrobialSourceDocuments(sourceExperimentId);
        if (documents.size() != 100) {
            throw new IllegalStateException("Antimicrobial summary source experiment must contain exactly 100 documents: "
                    + sourceExperimentId + " returned " + documents.size());
        }

        UUID experimentId = UUID.randomUUID();
        Path reportRoot = Path.of(properties.getReportRoot(), experimentId.toString());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("phase", ExperimentPhase.ANTIMICROBIAL_PAPER_SUMMARY.name());
        config.put("sourceExperimentId", sourceExperimentId.toString());
        config.put("corpusSize", documents.size());
        config.put("model", chatModelName());
        config.put("classificationPromptVersion", CLASSIFICATION_PROMPT_VERSION);
        config.put("summaryPromptVersion", SUMMARY_PROMPT_VERSION);
        config.put("documentIds", documents.stream().map(document -> document.documentId().toString()).toList());

        evaluationRepository.insertExperiment(experimentId, userId, EXPERIMENT_QUESTION,
                config, reportRoot.toString());
        Instant createdAt = Instant.now();
        for (AntimicrobialDocument document : documents) {
            evaluationRepository.upsertAntimicrobialResult(new AntimicrobialPaperResult(
                    experimentId, document.documentId(), document.title(), AntimicrobialResultStatus.PENDING,
                    null, null, null, null, null, null, null, createdAt, createdAt));
        }
        taskExecutor.execute(() -> runExperiment(experimentId, documents, reportRoot));
        return new RagEvaluationAcceptedResponse(experimentId, ExperimentStatus.QUEUED);
    }

    public List<AntimicrobialPaperResult> findResults(UUID experimentId) {
        return evaluationRepository.findAntimicrobialResults(experimentId);
    }

    private void runExperiment(UUID experimentId,
                               List<AntimicrobialDocument> documents,
                               Path reportRoot) {
        Instant experimentStartedAt = Instant.now();
        ExperimentTelemetry telemetry = new ExperimentTelemetry();
        try {
            evaluationRepository.updateStatus(experimentId, ExperimentStatus.RUNNING);
            Path normalizedRoot = reportRoot.toAbsolutePath().normalize();
            Path papersDir = normalizedRoot.resolve("papers");
            Files.createDirectories(papersDir);

            for (AntimicrobialDocument document : documents) {
                processDocument(experimentId, document, papersDir, telemetry);
            }

            List<AntimicrobialPaperResult> results = evaluationRepository.findAntimicrobialResults(experimentId);
            AntimicrobialSummaryMetrics summaryMetrics = summarizeResults(results);
            long totalElapsedMs = Duration.between(experimentStartedAt, Instant.now()).toMillis();
            RagEvaluationMetrics metrics = new RagEvaluationMetrics(
                    List.of(), Instant.now(), telemetry.toMetrics(), totalElapsedMs, summaryMetrics);
            writeExperimentSummary(normalizedRoot, experimentId, metrics);
            evaluationRepository.completeExperiment(experimentId, metrics);
            log.info("Antimicrobial summary experiment {} completed: {}", experimentId, summaryMetrics);
        } catch (Exception e) {
            log.error("Antimicrobial summary experiment {} failed: {}", experimentId, e.getMessage(), e);
            evaluationRepository.failExperiment(experimentId, "ANTIMICROBIAL_SUMMARY_ERROR",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void processDocument(UUID experimentId,
                                 AntimicrobialDocument document,
                                 Path papersDir,
                                 ExperimentTelemetry telemetry) {
        Instant startedAt = Instant.now();
        Boolean relevant = null;
        Integer chunkCount = null;
        try {
            List<RetrievedChunk> chunks = loadAllChunks(document);
            chunkCount = chunks.size();
            if (chunks.isEmpty()) {
                saveResult(experimentId, document, AntimicrobialResultStatus.NO_CHUNKS,
                        null, 0, "No chunks available in vector storage or document.jsonl.",
                        null, null, startedAt, Instant.now());
                return;
            }

            ClassificationOutput classification = classify(document, chunks, telemetry);
            relevant = classification.relevant;
            if (!Boolean.TRUE.equals(classification.relevant)) {
                saveResult(experimentId, document, AntimicrobialResultStatus.IRRELEVANT,
                        false, chunks.size(), classification.reason, null, null, startedAt, Instant.now());
                return;
            }

            String markdown = summarize(document, chunks, telemetry);
            Path outputPath = papersDir.resolve(document.documentId() + ".md").toAbsolutePath().normalize();
            Files.writeString(outputPath, markdown + System.lineSeparator(), StandardCharsets.UTF_8);
            saveResult(experimentId, document, AntimicrobialResultStatus.SUMMARIZED,
                    true, chunks.size(), classification.reason, outputPath.toString(),
                    null, startedAt, Instant.now());
        } catch (Exception e) {
            if (isNonRetryableModelError(e)) {
                saveResult(experimentId, document, AntimicrobialResultStatus.FAILED,
                        relevant, chunkCount, null, null, message(e), startedAt, Instant.now());
                throw new IllegalStateException("Non-retryable model error for document "
                        + document.documentId() + ": " + message(e), e);
            }
            saveResult(experimentId, document, AntimicrobialResultStatus.FAILED,
                    relevant, chunkCount, null, null, message(e), startedAt, Instant.now());
            log.warn("Antimicrobial summary failed for document {}: {}", document.documentId(), message(e));
        }
    }

    private List<RetrievedChunk> loadAllChunks(AntimicrobialDocument document) {
        List<RetrievedChunk> databaseChunks = ragChunkRepository.findAllChunksByDocumentId(document.documentId());
        if (databaseChunks != null && !databaseChunks.isEmpty()) {
            return databaseChunks;
        }
        if (document.storageRoot() == null || document.storageRoot().isBlank()) {
            return List.of();
        }
        try {
            List<RagChunk> artifactChunks = artifactLoader.loadChunks(Path.of(document.storageRoot()));
            return artifactChunks.stream()
                    .map(chunk -> new RetrievedChunk(
                            chunk.chunkId(),
                            chunk.documentId(),
                            firstNonBlank(chunk.title(), document.title()),
                            chunk.text(),
                            chunk.sectionPath(),
                            0.0,
                            "DOCUMENT_JSONL"
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("Chunk artifact fallback unavailable for document {}: {}",
                    document.documentId(), message(e));
            return List.of();
        }
    }

    private ClassificationOutput classify(AntimicrobialDocument document,
                                          List<RetrievedChunk> chunks,
                                          ExperimentTelemetry telemetry) throws JsonProcessingException {
        String systemPrompt = PromptResources.load(
                PromptCatalog.RAG_EVALUATION_ANTIMICROBIAL_CLASSIFICATION_SYSTEM);
        String userMessage = documentInput(document, chunks);
        Instant startedAt = Instant.now();
        ChatResponse response = reasoningChatClient.chatStandard(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage));
        telemetry.recordChat(CLASSIFICATION_PHASE, chatModelName(), response,
                estimateTokens(systemPrompt) + estimateTokens(userMessage),
                Duration.between(startedAt, Instant.now()).toMillis());
        String raw = response == null || response.aiMessage() == null
                ? null
                : response.aiMessage().text();
        ClassificationOutput output = objectMapper.readValue(extractJson(raw), ClassificationOutput.class);
        if (output.relevant == null) {
            throw new IllegalArgumentException("Classification response must include relevant=true or false");
        }
        if (output.reason == null || output.reason.isBlank()) {
            throw new IllegalArgumentException("Classification response must include a reason");
        }
        return output;
    }

    private String summarize(AntimicrobialDocument document,
                             List<RetrievedChunk> chunks,
                             ExperimentTelemetry telemetry) {
        String systemPrompt = PromptResources.load(PromptCatalog.RAG_EVALUATION_ANTIMICROBIAL_SUMMARY_SYSTEM);
        String baseUserMessage = documentInput(document, chunks);
        int maxAttempts = Math.max(1, properties.getAntimicrobialSummaryMaxAttempts());
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String userMessage = baseUserMessage;
            if (lastError != null) {
                userMessage += """

                        上一次输出未通过Markdown表格校验，错误为：
                        %s
                        请重新输出完整表格，且不要添加表格之外的文字。
                        """.formatted(message(lastError));
            }
            try {
                Instant startedAt = Instant.now();
                ChatResponse response = reasoningChatClient.chatCore(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userMessage));
                telemetry.recordChat(SUMMARY_PHASE, chatModelName(), response,
                        estimateTokens(systemPrompt) + estimateTokens(userMessage),
                        Duration.between(startedAt, Instant.now()).toMillis());
                AiMessage aiMessage = response == null ? null : response.aiMessage();
                return validateMarkdownTable(aiMessage == null ? null : aiMessage.text());
            } catch (Exception e) {
                if (isNonRetryableModelError(e)) {
                    throw new IllegalStateException("Non-retryable model error: " + message(e), e);
                }
                lastError = e;
                if (attempt == maxAttempts) {
                    break;
                }
            }
        }
        throw new IllegalStateException("Markdown table synthesis failed after "
                + maxAttempts + " attempts: " + message(lastError), lastError);
    }

    String validateMarkdownTable(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Model output is empty");
        }
        String trimmed = raw.trim();
        if (trimmed.contains("```")) {
            throw new IllegalArgumentException("Markdown fences are not allowed");
        }
        List<String> lines = trimmed.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() < 2) {
            throw new IllegalArgumentException("Table must contain a header and separator row");
        }
        List<String> headers = parseMarkdownRow(lines.get(0));
        if (!TABLE_HEADERS.equals(headers)) {
            throw new IllegalArgumentException("Table header must match the required 16 columns");
        }
        List<String> separator = parseMarkdownRow(lines.get(1));
        if (separator.size() != TABLE_HEADERS.size()
                || separator.stream().anyMatch(cell -> !cell.matches(":?-{3,}:?"))) {
            throw new IllegalArgumentException("Invalid Markdown separator row");
        }
        for (int index = 2; index < lines.size(); index++) {
            List<String> cells = parseMarkdownRow(lines.get(index));
            if (cells.size() != TABLE_HEADERS.size()) {
                throw new IllegalArgumentException("Data row " + index + " must contain 16 cells");
            }
            if (cells.stream().anyMatch(cell -> "无".equals(cell)
                    || "null".equalsIgnoreCase(cell))) {
                throw new IllegalArgumentException("Missing values must use empty cells");
            }
        }
        return String.join(System.lineSeparator(), lines);
    }

    List<String> parseMarkdownRow(String line) {
        if (line == null || !line.startsWith("|") || !line.endsWith("|")) {
            throw new IllegalArgumentException("Every table row must start and end with |");
        }
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 1; index < line.length() - 1; index++) {
            char value = line.charAt(index);
            if (value == '|' && !escaped) {
                cells.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(value);
            if (value == '\\' && !escaped) {
                escaped = true;
            } else {
                escaped = false;
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private String documentInput(AntimicrobialDocument document, List<RetrievedChunk> chunks) {
        return """
                Document metadata:
                document_id: %s
                title: %s
                authors: %s
                publication_year: %s
                journal: %s
                doi: %s

                All chunks in original order:
                %s
                """.formatted(
                document.documentId(),
                firstNonBlank(document.title(), ""),
                String.join(", ", document.authors() == null ? List.of() : document.authors()),
                document.publicationYear() == null ? "" : document.publicationYear(),
                firstNonBlank(document.journal(), ""),
                firstNonBlank(document.doi(), ""),
                renderChunks(chunks));
    }

    private String renderChunks(List<RetrievedChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            builder.append("\n--- chunk_id=")
                    .append(firstNonBlank(chunk.chunkId(), ""))
                    .append("; section=")
                    .append(firstNonBlank(chunk.sectionPath(), ""))
                    .append(" ---\n")
                    .append(firstNonBlank(chunk.text(), ""));
        }
        return builder.toString();
    }

    private void saveResult(UUID experimentId,
                            AntimicrobialDocument document,
                            AntimicrobialResultStatus status,
                            Boolean relevant,
                            Integer chunkCount,
                            String reason,
                            String outputPath,
                            String errorMessage,
                            Instant startedAt,
                            Instant finishedAt) {
        evaluationRepository.upsertAntimicrobialResult(new AntimicrobialPaperResult(
                experimentId,
                document.documentId(),
                document.title(),
                status,
                relevant,
                chunkCount,
                reason,
                outputPath,
                errorMessage,
                startedAt,
                finishedAt,
                null,
                Instant.now()
        ));
    }

    private AntimicrobialSummaryMetrics summarizeResults(List<AntimicrobialPaperResult> results) {
        List<AntimicrobialPaperResult> safe = results == null ? List.of() : results;
        return new AntimicrobialSummaryMetrics(
                safe.size(),
                (int) safe.stream().filter(result -> Boolean.TRUE.equals(result.relevant())).count(),
                (int) safe.stream().filter(result -> result.status() == AntimicrobialResultStatus.IRRELEVANT).count(),
                (int) safe.stream().filter(result -> result.status() == AntimicrobialResultStatus.SUMMARIZED).count(),
                (int) safe.stream().filter(result -> result.status() == AntimicrobialResultStatus.NO_CHUNKS).count(),
                (int) safe.stream().filter(result -> result.status() == AntimicrobialResultStatus.FAILED).count()
        );
    }

    private void writeExperimentSummary(Path reportRoot,
                                        UUID experimentId,
                                        RagEvaluationMetrics metrics) throws IOException {
        AntimicrobialSummaryMetrics summary = metrics.antimicrobialSummary();
        StringBuilder usageRows = new StringBuilder();
        for (ModelUsageMetric usage : metrics.modelUsage()) {
            usageRows.append("| ").append(usage.phase())
                    .append(" | ").append(usage.model())
                    .append(" | ").append(usage.estimatedInputTokens())
                    .append(" | ").append(usage.providerInputTokens())
                    .append(" | ").append(usage.providerOutputTokens())
                    .append(" | ").append(usage.providerTotalTokens())
                    .append(" | ").append(usage.calls())
                    .append(" | ").append(usage.elapsedMs())
                    .append(" |\n");
        }
        String markdown = """
                # 100篇抑菌化合物文献总结实验

                - 实验ID：%s
                - 总文献数：%d
                - 判定相关：%d
                - 判定无关：%d
                - 成功总结：%d
                - 缺少chunks：%d
                - 处理失败：%d
                - 总耗时：%d ms

                | 阶段 | 模型 | 估算输入tokens | Provider输入tokens | Provider输出tokens | Provider总tokens | 调用次数 | 阶段耗时ms |
                | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
                %s""".formatted(
                experimentId,
                summary.totalDocuments(),
                summary.relevantDocuments(),
                summary.irrelevantDocuments(),
                summary.summarizedDocuments(),
                summary.noChunksDocuments(),
                summary.failedDocuments(),
                metrics.totalElapsedMs(),
                usageRows);
        Files.writeString(reportRoot.resolve("experiment-summary.md"),
                markdown.stripTrailing() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        return first >= 0 && last > first ? trimmed.substring(first, last + 1) : trimmed;
    }

    private boolean isNonRetryableModelError(Exception error) {
        Throwable current = error;
        while (current != null) {
            String value = current.getMessage();
            if (value != null) {
                String lower = value.toLowerCase(Locale.ROOT);
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

    private long estimateTokens(String text) {
        if (tokenCountEstimator == null) {
            return text == null ? 0L : Math.max(1L, text.length() / 4L);
        }
        return tokenCountEstimator.estimateTokenCountInText(text);
    }

    private String chatModelName() {
        return modelProperties == null || modelProperties.getChatModel() == null
                ? "qwen-chat"
                : firstNonBlank(modelProperties.getChatModel().getModelName(), "qwen-chat");
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ClassificationOutput {
        public Boolean relevant;
        public String reason;
        public double confidence;
    }

    private static class ExperimentTelemetry {
        private final Map<String, UsageAccumulator> usage = new LinkedHashMap<>();

        private void recordChat(String phase,
                                String model,
                                ChatResponse response,
                                long estimatedInputTokens,
                                long elapsedMs) {
            TokenUsage tokenUsage = response == null ? null : response.tokenUsage();
            String key = phase + "|" + model;
            UsageAccumulator accumulator = usage.computeIfAbsent(key,
                    ignored -> new UsageAccumulator(phase, model));
            accumulator.estimatedInputTokens += Math.max(0L, estimatedInputTokens);
            accumulator.providerInputTokens += tokenValue(
                    tokenUsage == null ? null : tokenUsage.inputTokenCount());
            accumulator.providerOutputTokens += tokenValue(
                    tokenUsage == null ? null : tokenUsage.outputTokenCount());
            accumulator.providerTotalTokens += tokenValue(
                    tokenUsage == null ? null : tokenUsage.totalTokenCount());
            accumulator.elapsedMs += Math.max(0L, elapsedMs);
            accumulator.calls++;
        }

        private List<ModelUsageMetric> toMetrics() {
            return usage.values().stream()
                    .map(value -> new ModelUsageMetric(
                            value.phase,
                            value.model,
                            value.estimatedInputTokens,
                            value.providerInputTokens,
                            value.providerOutputTokens,
                            value.providerTotalTokens,
                            value.elapsedMs,
                            value.calls
                    ))
                    .toList();
        }

        private long tokenValue(Integer value) {
            return value == null ? 0L : value.longValue();
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
}
