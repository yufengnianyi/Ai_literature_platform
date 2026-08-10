package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactDocument;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactScan;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FilterRunAccepted;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmJudgment;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmLabel;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentResult;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentPage;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentMode;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunRecord;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunStatus;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.SkippedArtifact;
import com.example.demo_01.ai.pretreatment.PretreatmentQualityGate.QualityResult;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import com.example.demo_01.ai.stage.CohortService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class PretreatmentService {

    @Resource
    private PretreatmentProperties properties;

    @Resource
    private PretreatmentArtifactScanner artifactScanner;

    @Resource
    private PretreatmentQualityGate qualityGate;

    @Resource
    private PretreatmentLlmJudge llmJudge;

    @Resource
    private PretreatmentRepository repository;

    @Resource
    private PretreatmentReportWriter reportWriter;

    @Resource
    private RagVectorIngestionService ragVectorIngestionService;

    @Resource
    private CohortService cohortService;

    @Resource
    @Qualifier("preprocessTaskExecutor")
    private TaskExecutor taskExecutor;

    public PretreatmentRunSummary runCli() {
        PretreatmentMode mode = parseMode(properties.getCli().getMode());
        return switch (mode) {
            case scan -> scan();
            case apply -> apply();
        };
    }

    public PretreatmentRunSummary scan() {
        return scan(UUID.randomUUID(), null, null, true);
    }

    public FilterRunAccepted submitFilterRun() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        Path outputDir = Path.of(properties.getOutputRoot()).resolve(runId.toString()).toAbsolutePath().normalize();
        repository.insertRun(runId, PretreatmentMode.scan, repository.configJson(properties),
                outputDir.toString(), startedAt);
        taskExecutor.execute(() -> {
            try {
                scan(runId, outputDir, startedAt, false);
            } catch (Exception e) {
                log.warn("PreTreatment REST run {} failed: {}", runId, rootMessage(e), e);
            }
        });
        return new FilterRunAccepted(runId, PretreatmentRunStatus.RUNNING);
    }

    public PretreatmentRunRecord requireRun(UUID runId) {
        PretreatmentRunRecord run = repository.findRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("PreTreatment run not found: " + runId);
        }
        return run;
    }

    public PretreatmentDocumentPage findDocuments(UUID runId, FinalDecision finalDecision,
                                                  int page, int size) {
        requireRun(runId);
        return repository.findDocuments(runId, finalDecision, page, size);
    }

    public PretreatmentRunSummary garbageCollectRejectedVectors(UUID runId, boolean dryRun) {
        PretreatmentRunRecord run = requireRun(runId);
        List<UUID> rejectedIds = repository.findDocumentIds(runId, FinalDecision.REJECTED);
        int removed = 0;
        if (!dryRun) {
            for (UUID documentId : rejectedIds) {
                ragVectorIngestionService.removeDocument(documentId);
                removed++;
            }
        }
        return new PretreatmentRunSummary(
                runId,
                PretreatmentMode.apply,
                run.outputDir(),
                rejectedIds.size(),
                rejectedIds.size(),
                0,
                rejectedIds.size(),
                0,
                0,
                removed,
                dryRun,
                run.startedAt() == null ? Instant.now() : run.startedAt(),
                Instant.now());
    }

    private PretreatmentRunSummary scan(UUID runId, Path outputDir, Instant startedAt,
                                        boolean insertRun) {
        Instant actualStartedAt = startedAt == null ? Instant.now() : startedAt;
        Path actualOutputDir = outputDir == null
                ? Path.of(properties.getOutputRoot()).resolve(runId.toString()).toAbsolutePath().normalize()
                : outputDir;
        if (insertRun) {
            repository.insertRun(runId, PretreatmentMode.scan, repository.configJson(properties),
                    actualOutputDir.toString(), actualStartedAt);
        }
        try {
            ArtifactScan scan = artifactScanner.scan(Path.of(properties.getArtifactRoot()), properties.getMaxDocuments());
            List<PretreatmentDocumentResult> results = new ArrayList<>();
            for (SkippedArtifact skipped : scan.skipped()) {
                results.add(skippedResult(runId, skipped));
            }
            for (ArtifactDocument document : scan.documents()) {
                PretreatmentDocumentResult result = screenDocument(runId, document);
                results.add(result);
                repository.insertResult(result);
            }
            for (PretreatmentDocumentResult result : results) {
                if (result.finalDecision() == FinalDecision.SKIPPED) {
                    repository.insertResult(result);
                }
            }
            PretreatmentRunSummary summary = summary(runId, PretreatmentMode.scan, actualOutputDir,
                    scan, results, 0, actualStartedAt);
            reportWriter.write(actualOutputDir, summary, results);
            repository.completeRun(summary);
            publishFilterCohorts(runId, results);
            return summary;
        } catch (Exception ex) {
            repository.failRun(runId, "PRETREATMENT_SCAN", rootMessage(ex));
            throw ex;
        }
    }

    public PretreatmentRunSummary apply() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        Path outputRoot = Path.of(properties.getOutputRoot()).toAbsolutePath().normalize();
        Path applyRunDir = reportWriter.resolveApplyRunDir(outputRoot, properties.getCli().getApplyRunId());
        repository.insertRun(runId, PretreatmentMode.apply, repository.configJson(properties), applyRunDir.toString(), startedAt);
        try {
            List<UUID> rejectedIds = reportWriter.readRejectedIds(applyRunDir);
            Instant finishedAt = Instant.now();
            PretreatmentRunSummary summary = new PretreatmentRunSummary(
                    runId,
                    PretreatmentMode.apply,
                    applyRunDir.toString(),
                    rejectedIds.size(),
                    rejectedIds.size(),
                    0,
                    rejectedIds.size(),
                    0,
                    0,
                    0,
                    properties.getCli().isDryRun(),
                    startedAt,
                    finishedAt
            );
            repository.completeRun(summary);
            return summary;
        } catch (Exception ex) {
            repository.failRun(runId, "PRETREATMENT_APPLY", rootMessage(ex));
            throw ex;
        }
    }

    PretreatmentDocumentResult screenDocument(UUID runId,
                                              ArtifactDocument document) {
        RagDocumentMetadata metadata = document.metadata();
        QualityResult qualityResult = qualityGate.evaluate(metadata, document.chunks(), properties.getQuality());
        if (qualityResult.decision() == QualityDecision.REJECT) {
            return result(runId, document, metadata, qualityResult,
                    LlmJudgment.notRun(qualityResult.reason()), qualityResult.rejectReasonCode());
        }
        LlmJudgment judgment;
        try {
            judgment = llmJudge.judgeAbstract(
                    Path.of(properties.getPromptPath()),
                    metadata,
                    properties.getLlmMaxAttempts());
        } catch (Exception ex) {
            judgment = new LlmJudgment(LlmLabel.NOT_RUN, List.of(), "", List.of(),
                    "LLM judgment failed; manual review required: " + rootMessage(ex));
            log.warn("PreTreatment LLM judgment failed for document {}: {}", document.documentId(), rootMessage(ex));
        }
        return result(runId, document, metadata, qualityResult, judgment, "");
    }

    private PretreatmentDocumentResult result(UUID runId,
                                              ArtifactDocument document,
                                              RagDocumentMetadata metadata,
                                              QualityResult qualityResult,
                                              LlmJudgment judgment,
                                              String rejectReasonCode) {
        FinalDecision finalDecision = finalDecision(qualityResult, judgment);
        String finalRejectReasonCode = finalDecision == FinalDecision.REJECTED && (rejectReasonCode == null || rejectReasonCode.isBlank())
                ? reasonCode(judgment)
                : rejectReasonCode;
        return new PretreatmentDocumentResult(
                runId,
                document.documentId(),
                document.storageDir(),
                metadata == null ? null : metadata.title(),
                metadata == null ? null : metadata.journal(),
                metadata == null ? null : metadata.doiNormalized(),
                qualityResult == null ? null : qualityResult.decision(),
                qualityResult == null ? Map.of() : qualityResult.metrics(),
                judgment.label(),
                finalDecision,
                finalRejectReasonCode,
                judgment.taxa(),
                judgment.researchFocus(),
                judgment.evidenceChunkIds(),
                judgment.reason()
        );
    }

    private FinalDecision finalDecision(QualityResult qualityResult,
                                        LlmJudgment judgment) {
        if (qualityResult != null && qualityResult.decision() == QualityDecision.REJECT) {
            return FinalDecision.REJECTED;
        }
        if (judgment.label() == LlmLabel.RELEVANT) {
            return FinalDecision.ACCEPTED;
        }
        if (judgment.label() == LlmLabel.NOT_RELEVANT) {
            return FinalDecision.REJECTED;
        }
        return FinalDecision.REJECTED;
    }

    private PretreatmentDocumentResult skippedResult(UUID runId, SkippedArtifact skipped) {
        return new PretreatmentDocumentResult(
                runId,
                skipped.documentId(),
                skipped.storageDir(),
                null,
                null,
                null,
                QualityDecision.REJECT,
                Map.of("chunkCount", 0, "totalTextChars", 0, "averageChunkChars", 0.0,
                        "replacementCharRatio", 0.0, "shortLineRatio", 0.0),
                LlmLabel.NOT_RUN,
                FinalDecision.REJECTED,
                "MISSING_ARTIFACT",
                List.of(),
                "",
                List.of(),
                skipped.reason()
        );
    }

    private PretreatmentRunSummary summary(UUID runId,
                                           PretreatmentMode mode,
                                           Path outputDir,
                                           ArtifactScan scan,
                                           List<PretreatmentDocumentResult> results,
                                           int vectorsRemoved,
                                           Instant startedAt) {
        return new PretreatmentRunSummary(
                runId,
                mode,
                outputDir.toString(),
                scan.documents().size() + scan.skipped().size(),
                scan.documents().size(),
                count(results, FinalDecision.ACCEPTED),
                count(results, FinalDecision.REJECTED),
                0,
                count(results, FinalDecision.SKIPPED),
                vectorsRemoved,
                properties.getCli().isDryRun(),
                startedAt,
                Instant.now()
        );
    }

    private int count(List<PretreatmentDocumentResult> results, FinalDecision decision) {
        return (int) results.stream().filter(result -> result.finalDecision() == decision).count();
    }

    private void publishFilterCohorts(UUID runId, List<PretreatmentDocumentResult> results) {
        List<UUID> accepted = ids(results, FinalDecision.ACCEPTED);
        List<UUID> rejected = ids(results, FinalDecision.REJECTED);
        UUID acceptedCohortId = cohortService.create(
                "filter-accepted-" + runId,
                "PRETREATMENT",
                runId,
                accepted,
                "accepted by pretreatment");
        UUID rejectedCohortId = cohortService.create(
                "filter-rejected-" + runId,
                "PRETREATMENT",
                runId,
                rejected,
                "rejected by pretreatment");
        repository.setCohorts(runId, acceptedCohortId, rejectedCohortId);
    }

    private List<UUID> ids(List<PretreatmentDocumentResult> results, FinalDecision decision) {
        return results.stream()
                .filter(result -> result.finalDecision() == decision)
                .map(PretreatmentDocumentResult::documentId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private PretreatmentMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return PretreatmentMode.scan;
        }
        return PretreatmentMode.valueOf(mode.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String reasonCode(LlmJudgment judgment) {
        if (judgment == null || judgment.label() == null) {
            return "";
        }
        return switch (judgment.label()) {
            case NOT_RELEVANT -> "LLM_NOT_RELEVANT";
            case NOT_RUN -> "LLM_NOT_RUN";
            default -> "";
        };
    }
}
