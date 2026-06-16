package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.rag.evaluation.config.RagEvaluationProperties;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.ExperimentStatus;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RagEvaluationAcceptedResponse;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RagEvaluationExperimentRecord;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RetrievalScope;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.DEFAULT_QUESTION;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
class RagEvaluationLargeGoldSetTest {

    private static final Path LATEST_RUN_FILE = Path.of(
            "data", "rag-evaluation", "large-goldset-latest.txt");

    @Resource
    private RagEvaluationService evaluationService;

    @Resource
    private RagEvaluationRepository evaluationRepository;

    @Resource
    private RagEvaluationProperties properties;

    @Test
    void runLargeGoldSetEvaluation() throws Exception {
        int maxDocuments = Math.max(1, Integer.getInteger("rag.eval.large.max-documents", 1000));
        int timeoutMinutes = Math.max(1, Integer.getInteger("rag.eval.large.timeout-minutes", 360));
        int pollSeconds = Math.max(5, Integer.getInteger("rag.eval.large.poll-seconds", 30));

        properties.setMaxDocuments(maxDocuments);
        properties.setRetrievalScope(RetrievalScope.JUDGED_DOCUMENTS);
        properties.setJudgmentOnly(Boolean.parseBoolean(
                System.getProperty("rag.eval.large.judgment-only", "true")));

        RagEvaluationAcceptedResponse accepted = evaluationService.submit(
                "large-goldset-evaluation", DEFAULT_QUESTION, RetrievalScope.JUDGED_DOCUMENTS);
        UUID experimentId = accepted.experimentId();

        Files.createDirectories(LATEST_RUN_FILE.getParent());
        Files.writeString(LATEST_RUN_FILE,
                "experimentId=" + experimentId + System.lineSeparator()
                        + "maxDocuments=" + maxDocuments + System.lineSeparator()
                        + "retrievalScope=" + RetrievalScope.JUDGED_DOCUMENTS + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.printf("Started large gold-set evaluation: experimentId=%s, maxDocuments=%d%n",
                experimentId, maxDocuments);

        Instant deadline = Instant.now().plus(Duration.ofMinutes(timeoutMinutes));
        ExperimentStatus lastStatus = null;
        int lastJudgmentCount = -1;
        while (Instant.now().isBefore(deadline)) {
            RagEvaluationExperimentRecord experiment = evaluationRepository.findExperiment(experimentId)
                    .orElseThrow(() -> new IllegalStateException("Experiment not found: " + experimentId));
            int judgmentCount = evaluationRepository.findJudgments(experimentId).size();
            if (experiment.status() != lastStatus || judgmentCount != lastJudgmentCount) {
                System.out.printf("Progress experimentId=%s status=%s judgments=%d/%d%n",
                        experimentId, experiment.status(), judgmentCount, maxDocuments);
                lastStatus = experiment.status();
                lastJudgmentCount = judgmentCount;
            }
            if (experiment.status() == ExperimentStatus.COMPLETED) {
                System.out.printf("Completed large gold-set evaluation: experimentId=%s judgments=%d%n",
                        experimentId, judgmentCount);
                return;
            }
            if (experiment.status() == ExperimentStatus.FAILED) {
                fail("Large gold-set evaluation failed: " + experiment.errorMessage());
            }
            Thread.sleep(Duration.ofSeconds(pollSeconds));
        }

        fail("Timed out waiting for large gold-set evaluation: " + experimentId);
    }

    @Test
    void resumeLargeGoldSetEvaluation() throws Exception {
        UUID experimentId = resolveExperimentId();
        int maxDocuments = Math.max(1, Integer.getInteger("rag.eval.large.max-documents", 1000));
        properties.setMaxDocuments(maxDocuments);
        properties.setRetrievalScope(RetrievalScope.JUDGED_DOCUMENTS);
        properties.setJudgmentOnly(Boolean.parseBoolean(
                System.getProperty("rag.eval.large.judgment-only", "true")));

        int before = evaluationRepository.findJudgments(experimentId).size();
        System.out.printf("Resuming large gold-set evaluation: experimentId=%s existingJudgments=%d maxDocuments=%d%n",
                experimentId, before, maxDocuments);
        ReflectionTestUtils.invokeMethod(evaluationService, "runExperiment", experimentId);

        RagEvaluationExperimentRecord experiment = evaluationRepository.findExperiment(experimentId)
                .orElseThrow(() -> new IllegalStateException("Experiment not found: " + experimentId));
        int after = evaluationRepository.findJudgments(experimentId).size();
        System.out.printf("Resume finished: experimentId=%s status=%s judgments=%d%n",
                experimentId, experiment.status(), after);
        if (experiment.status() == ExperimentStatus.FAILED) {
            fail("Large gold-set resume failed: " + experiment.errorMessage());
        }
    }

    private UUID resolveExperimentId() throws Exception {
        String explicit = System.getProperty("rag.eval.resume.experiment-id");
        if (explicit != null && !explicit.isBlank()) {
            return UUID.fromString(explicit.trim());
        }
        String latest = Files.readString(LATEST_RUN_FILE, StandardCharsets.UTF_8);
        for (String line : latest.split("\\R")) {
            if (line.startsWith("experimentId=")) {
                return UUID.fromString(line.substring("experimentId=".length()).trim());
            }
        }
        throw new IllegalStateException("No experimentId found in " + LATEST_RUN_FILE);
    }
}
