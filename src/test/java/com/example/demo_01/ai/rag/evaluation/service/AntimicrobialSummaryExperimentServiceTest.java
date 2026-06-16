package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactLoader;
import com.example.demo_01.ai.rag.evaluation.config.RagEvaluationProperties;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository.AntimicrobialDocument;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AntimicrobialSummaryExperimentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void submitShouldRequireExactlyOneHundredSourceDocuments() {
        TestContext context = context();
        when(context.repository.findAntimicrobialSourceDocuments(any()))
                .thenReturn(documents(99));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> context.service.submit("user-1"));

        assertTrue(error.getMessage().contains("exactly 100"));
        verify(context.repository, never()).insertExperiment(any(), any(), any(), any(), any());
    }

    @Test
    void submitShouldPersistFixedCorpusAndPendingRows() {
        TestContext context = context();
        List<AntimicrobialDocument> documents = documents(100);
        when(context.repository.findAntimicrobialSourceDocuments(any())).thenReturn(documents);

        RagEvaluationAcceptedResponse response = context.service.submit("user-1");

        assertEquals(ExperimentStatus.QUEUED, response.status());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
        verify(context.repository).insertExperiment(eq(response.experimentId()), eq("user-1"),
                eq(AntimicrobialSummaryExperimentService.EXPERIMENT_QUESTION),
                configCaptor.capture(), contains(response.experimentId().toString()));
        assertEquals(ExperimentPhase.ANTIMICROBIAL_PAPER_SUMMARY.name(),
                configCaptor.getValue().get("phase"));
        assertEquals(100, ((List<?>) configCaptor.getValue().get("documentIds")).size());
        verify(context.repository, times(100)).upsertAntimicrobialResult(any());
        verify(context.taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void unrelatedDocumentShouldSkipTableSynthesis() {
        TestContext context = context();
        UUID experimentId = UUID.randomUUID();
        AntimicrobialDocument document = document(1);
        when(context.reviewRepository.findAllChunksByDocumentId(document.documentId()))
                .thenReturn(List.of(chunk(document, "chunk-1", "host resistance only")));
        when(context.reasoningChatClient.chatStandard(any(), any()))
                .thenReturn(response("""
                        {"relevant":false,"reason":"No compound activity assay.","confidence":0.9}
                        """, 10, 2));
        when(context.repository.findAntimicrobialResults(experimentId))
                .thenReturn(List.of(result(experimentId, document, AntimicrobialResultStatus.IRRELEVANT, false)));

        ReflectionTestUtils.invokeMethod(context.service, "runExperiment",
                experimentId, List.of(document), tempDir.resolve(experimentId.toString()));

        verify(context.reasoningChatClient, never()).chatCore(any(), any());
        ArgumentCaptor<AntimicrobialPaperResult> resultCaptor =
                ArgumentCaptor.forClass(AntimicrobialPaperResult.class);
        verify(context.repository).upsertAntimicrobialResult(resultCaptor.capture());
        assertEquals(AntimicrobialResultStatus.IRRELEVANT, resultCaptor.getValue().status());
        assertEquals(1, resultCaptor.getValue().chunkCount());
        verify(context.repository).completeExperiment(eq(experimentId), any());
    }

    @Test
    void missingChunksShouldNotCallAnyModel() {
        TestContext context = context();
        UUID experimentId = UUID.randomUUID();
        AntimicrobialDocument document = document(1);
        when(context.reviewRepository.findAllChunksByDocumentId(document.documentId()))
                .thenReturn(List.of());
        when(context.repository.findAntimicrobialResults(experimentId))
                .thenReturn(List.of(result(experimentId, document, AntimicrobialResultStatus.NO_CHUNKS, null)));

        ReflectionTestUtils.invokeMethod(context.service, "runExperiment",
                experimentId, List.of(document), tempDir.resolve(experimentId.toString()));

        verifyNoInteractions(context.reasoningChatClient);
        ArgumentCaptor<AntimicrobialPaperResult> resultCaptor =
                ArgumentCaptor.forClass(AntimicrobialPaperResult.class);
        verify(context.repository).upsertAntimicrobialResult(resultCaptor.capture());
        assertEquals(AntimicrobialResultStatus.NO_CHUNKS, resultCaptor.getValue().status());
    }

    @Test
    void relevantDocumentShouldRetryInvalidTableAndAggregateProviderUsage() throws Exception {
        TestContext context = context();
        context.properties.setAntimicrobialSummaryMaxAttempts(2);
        UUID experimentId = UUID.randomUUID();
        AntimicrobialDocument document = document(1);
        List<RetrievedChunk> chunks = List.of(
                chunk(document, "chunk-1", "first evidence"),
                chunk(document, "chunk-2", "second evidence"));
        when(context.reviewRepository.findAllChunksByDocumentId(document.documentId()))
                .thenReturn(chunks);
        when(context.reasoningChatClient.chatStandard(any(), any()))
                .thenReturn(response("""
                        {"relevant":true,"reason":"Reports oomycete activity.","confidence":0.95}
                        """, 20, 4));
        String validTable = validTable("compound A", "EC50 = 1 μg/mL \\| 24 h");
        when(context.reasoningChatClient.chatCore(any(), any()))
                .thenReturn(response("explanation before table", 30, 5))
                .thenReturn(response(validTable, 40, 6));
        when(context.repository.findAntimicrobialResults(experimentId))
                .thenReturn(List.of(result(experimentId, document, AntimicrobialResultStatus.SUMMARIZED, true)));

        Path reportRoot = tempDir.resolve(experimentId.toString());
        ReflectionTestUtils.invokeMethod(context.service, "runExperiment",
                experimentId, List.of(document), reportRoot);

        verify(context.reasoningChatClient, times(2)).chatCore(any(), any());
        Path paperPath = reportRoot.resolve("papers").resolve(document.documentId() + ".md");
        assertTrue(Files.isRegularFile(paperPath));
        assertEquals(validTable, Files.readString(paperPath).trim());

        ArgumentCaptor<RagEvaluationMetrics> metricsCaptor =
                ArgumentCaptor.forClass(RagEvaluationMetrics.class);
        verify(context.repository).completeExperiment(eq(experimentId), metricsCaptor.capture());
        RagEvaluationMetrics metrics = metricsCaptor.getValue();
        ModelUsageMetric classification = usage(metrics, AntimicrobialSummaryExperimentService.CLASSIFICATION_PHASE);
        ModelUsageMetric synthesis = usage(metrics, AntimicrobialSummaryExperimentService.SUMMARY_PHASE);
        assertEquals(24L, classification.providerTotalTokens());
        assertEquals(1, classification.calls());
        assertEquals(81L, synthesis.providerTotalTokens());
        assertEquals(2, synthesis.calls());
        assertTrue(Files.isRegularFile(reportRoot.resolve("experiment-summary.md")));
    }

    @Test
    void relevantDocumentShouldSendEveryChunkInOriginalOrderToBothModelCalls() {
        TestContext context = context();
        UUID experimentId = UUID.randomUUID();
        AntimicrobialDocument document = document(1);
        String first = "FIRST-" + "a".repeat(20_000);
        String second = "SECOND-" + "b".repeat(20_000);
        String third = "THIRD-" + "c".repeat(20_000);
        when(context.reviewRepository.findAllChunksByDocumentId(document.documentId()))
                .thenReturn(List.of(
                        chunk(document, "chunk-1", first),
                        chunk(document, "chunk-2", second),
                        chunk(document, "chunk-3", third)));
        when(context.reasoningChatClient.chatStandard(any(), any()))
                .thenReturn(response("""
                        {"relevant":true,"reason":"Reports oomycete activity.","confidence":0.95}
                        """, 20, 4));
        when(context.reasoningChatClient.chatCore(any(), any()))
                .thenReturn(response(validTable(null, null), 30, 5));
        when(context.repository.findAntimicrobialResults(experimentId))
                .thenReturn(List.of(result(experimentId, document, AntimicrobialResultStatus.SUMMARIZED, true)));

        ReflectionTestUtils.invokeMethod(context.service, "runExperiment",
                experimentId, List.of(document), tempDir.resolve(experimentId.toString()));

        ArgumentCaptor<UserMessage> classificationMessage = ArgumentCaptor.forClass(UserMessage.class);
        verify(context.reasoningChatClient).chatStandard(any(), classificationMessage.capture());
        assertChunkOrder(classificationMessage.getValue().singleText(), first, second, third);

        ArgumentCaptor<UserMessage> summaryMessage = ArgumentCaptor.forClass(UserMessage.class);
        verify(context.reasoningChatClient).chatCore(any(), summaryMessage.capture());
        assertChunkOrder(summaryMessage.getValue().singleText(), first, second, third);
    }

    @Test
    void nonRetryableModelErrorShouldAbortRemainingDocuments() {
        TestContext context = context();
        UUID experimentId = UUID.randomUUID();
        AntimicrobialDocument first = document(1);
        AntimicrobialDocument second = document(2);
        when(context.reviewRepository.findAllChunksByDocumentId(first.documentId()))
                .thenReturn(List.of(chunk(first, "chunk-1", "evidence")));
        when(context.reasoningChatClient.chatStandard(any(), any()))
                .thenThrow(new IllegalStateException("Unauthorized: invalid api key"));

        ReflectionTestUtils.invokeMethod(context.service, "runExperiment",
                experimentId, List.of(first, second), tempDir.resolve(experimentId.toString()));

        verify(context.reviewRepository, never()).findAllChunksByDocumentId(second.documentId());
        verify(context.repository).failExperiment(eq(experimentId),
                eq("ANTIMICROBIAL_SUMMARY_ERROR"), contains("Non-retryable model error"));
        verify(context.repository, never()).completeExperiment(any(), any());
    }

    @Test
    void markdownValidatorShouldAllowHeaderOnlyTableAndEscapedPipes() {
        TestContext context = context();
        String headerOnly = validTable(null, null);
        assertEquals(headerOnly, context.service.validateMarkdownTable(headerOnly));

        String withEscapedPipe = validTable("compound \\| label", "EC50 = 1");
        assertEquals(16, context.service.parseMarkdownRow(withEscapedPipe.lines().skip(2).findFirst().orElseThrow()).size());
        assertEquals(withEscapedPipe, context.service.validateMarkdownTable(withEscapedPipe));
    }

    @Test
    void markdownValidatorShouldRejectExtraTextAndWrongWidth() {
        TestContext context = context();
        assertThrows(IllegalArgumentException.class,
                () -> context.service.validateMarkdownTable("Explanation\n" + validTable(null, null)));

        String wrongWidth = "| " + String.join(" | ",
                AntimicrobialSummaryExperimentService.TABLE_HEADERS.subList(0, 15)) + " |\n"
                + "| " + String.join(" | ", Collections.nCopies(15, "---")) + " |";
        assertThrows(IllegalArgumentException.class,
                () -> context.service.validateMarkdownTable(wrongWidth));
    }

    private TestContext context() {
        AntimicrobialSummaryExperimentService service = new AntimicrobialSummaryExperimentService();
        RagEvaluationProperties properties = new RagEvaluationProperties();
        properties.setReportRoot(tempDir.toString());
        RagEvaluationRepository repository = mock(RagEvaluationRepository.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        PreprocessArtifactLoader artifactLoader = mock(PreprocessArtifactLoader.class);
        ReviewReasoningChatClient reasoningChatClient = mock(ReviewReasoningChatClient.class);
        DashScopeModelProperties modelProperties = new DashScopeModelProperties();
        modelProperties.getChatModel().setModelName("test-model");
        TokenCountEstimator estimator = mock(TokenCountEstimator.class);
        when(estimator.estimateTokenCountInText(anyString())).thenAnswer(invocation ->
                Math.max(1, invocation.getArgument(0, String.class).length() / 4));
        TaskExecutor taskExecutor = mock(TaskExecutor.class);

        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "evaluationRepository", repository);
        ReflectionTestUtils.setField(service, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(service, "artifactLoader", artifactLoader);
        ReflectionTestUtils.setField(service, "reasoningChatClient", reasoningChatClient);
        ReflectionTestUtils.setField(service, "modelProperties", modelProperties);
        ReflectionTestUtils.setField(service, "tokenCountEstimator", estimator);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "taskExecutor", taskExecutor);
        return new TestContext(service, properties, repository, reviewRepository,
                reasoningChatClient, taskExecutor);
    }

    private List<AntimicrobialDocument> documents(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> document(index + 1))
                .toList();
    }

    private AntimicrobialDocument document(int index) {
        return new AntimicrobialDocument(
                new UUID(0L, index),
                "Paper " + index,
                List.of("Author " + index),
                2026,
                "Journal",
                "10.1000/" + index,
                null
        );
    }

    private RetrievedChunk chunk(AntimicrobialDocument document, String chunkId, String text) {
        return new RetrievedChunk(chunkId, document.documentId(), document.title(),
                text, "Results", 0.0, "DOC_ALL");
    }

    private ChatResponse response(String text, int inputTokens, int outputTokens) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens))
                .build();
    }

    private String validTable(String compound, String activity) {
        String header = "| " + String.join(" | ", AntimicrobialSummaryExperimentService.TABLE_HEADERS) + " |";
        String separator = "| " + String.join(" | ",
                Collections.nCopies(AntimicrobialSummaryExperimentService.TABLE_HEADERS.size(), "---")) + " |";
        if (compound == null) {
            return header + System.lineSeparator() + separator;
        }
        List<String> cells = new ArrayList<>(Collections.nCopies(16, ""));
        cells.set(0, compound);
        cells.set(7, activity);
        return header + System.lineSeparator() + separator + System.lineSeparator()
                + "| " + String.join(" | ", cells) + " |";
    }

    private AntimicrobialPaperResult result(UUID experimentId,
                                             AntimicrobialDocument document,
                                             AntimicrobialResultStatus status,
                                             Boolean relevant) {
        Instant now = Instant.now();
        return new AntimicrobialPaperResult(
                experimentId, document.documentId(), document.title(), status,
                relevant, 1, "reason", null, null, now, now, now, now);
    }

    private ModelUsageMetric usage(RagEvaluationMetrics metrics, String phase) {
        return metrics.modelUsage().stream()
                .filter(value -> phase.equals(value.phase()))
                .findFirst()
                .orElseThrow();
    }

    private void assertChunkOrder(String message, String first, String second, String third) {
        int firstIndex = message.indexOf(first);
        int secondIndex = message.indexOf(second);
        int thirdIndex = message.indexOf(third);
        assertTrue(firstIndex >= 0);
        assertTrue(secondIndex > firstIndex);
        assertTrue(thirdIndex > secondIndex);
    }

    private record TestContext(
            AntimicrobialSummaryExperimentService service,
            RagEvaluationProperties properties,
            RagEvaluationRepository repository,
            ReviewRepository reviewRepository,
            ReviewReasoningChatClient reasoningChatClient,
            TaskExecutor taskExecutor
    ) {
    }
}
