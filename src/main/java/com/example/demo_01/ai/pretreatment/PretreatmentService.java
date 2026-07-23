package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactDocument;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactScan;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmJudgment;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmLabel;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentResult;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentMode;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.SkippedArtifact;
import com.example.demo_01.ai.pretreatment.PretreatmentQualityGate.QualityResult;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
    private PretreatmentTitleMetadataResolver titleMetadataResolver;

    @Resource
    private PretreatmentLlmJudge llmJudge;

    @Resource
    private PretreatmentRepository repository;

    @Resource
    private PretreatmentReportWriter reportWriter;

    @Resource
    private RagVectorIngestionService ragVectorIngestionService;

    public PretreatmentRunSummary runCli() {
        PretreatmentMode mode = parseMode(properties.getCli().getMode());
        return switch (mode) {
            case scan -> scan();
            case apply -> apply();
        };
    }

    public PretreatmentRunSummary scan() {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        Path outputDir = Path.of(properties.getOutputRoot()).resolve(runId.toString()).toAbsolutePath().normalize();
        repository.insertRun(runId, PretreatmentMode.scan, repository.configJson(properties), outputDir.toString(), startedAt);
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
            PretreatmentRunSummary summary = summary(runId, PretreatmentMode.scan, outputDir, scan, results, 0, startedAt);
            reportWriter.write(outputDir, summary, results);
            repository.completeRun(summary);
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
            int removed = 0;
            for (UUID documentId : rejectedIds) {
                if (!properties.getCli().isDryRun()) {
                    ragVectorIngestionService.removeDocument(documentId);
                    removed++;
                }
            }
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
                    removed,
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
        if (titleMetadataResolver != null) {
            metadata = titleMetadataResolver.resolve(metadata);
        }
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
