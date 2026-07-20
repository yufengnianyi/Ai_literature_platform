package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactDocument;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactScan;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.JournalQuality;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmJudgment;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.LlmLabel;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentResult;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentMode;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.SkippedArtifact;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.TitleDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.TitleVectorDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentQualityGate.QualityResult;
import com.example.demo_01.ai.pretreatment.OomyceteTitleVectorMatcher.TitleMatchResult;
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
    private OomyceteTitleVectorMatcher titleVectorMatcher;

    @Resource
    private JournalQualityService journalQualityService;

    @Resource
    private JournalResolverService journalResolverService;

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
            Map<String, JournalQuality> journalQualityMap = journalQualityService.load(Path.of(properties.getJournalQualityPath()));
            List<PretreatmentDocumentResult> results = new ArrayList<>();
            for (SkippedArtifact skipped : scan.skipped()) {
                results.add(skippedResult(runId, skipped));
            }
            for (ArtifactDocument document : scan.documents()) {
                PretreatmentDocumentResult result = screenDocument(runId, document, journalQualityMap);
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
                                              ArtifactDocument document,
                                              Map<String, JournalQuality> journalQualityMap) {
        RagDocumentMetadata metadata = document.metadata();
        if (titleMetadataResolver != null) {
            metadata = titleMetadataResolver.resolve(metadata);
        }
        JournalQuality journalQuality = journalResolverService.resolve(metadata, journalQualityMap).quality();
        QualityResult qualityResult = qualityGate.evaluate(metadata, document.chunks(), properties.getQuality());
        if (qualityResult.decision() == QualityDecision.REJECT) {
            return result(runId, document, metadata, qualityResult, null, journalQuality,
                    LlmJudgment.notRun(qualityResult.reason()), qualityResult.rejectReasonCode());
        }
        TitleMatchResult titleMatch = titleVectorMatcher.match(metadata == null ? null : metadata.title(), properties.getTitleVector());
        if (titleMatch.vectorDecision() == TitleVectorDecision.REJECT_LOW_TITLE_RELEVANCE) {
            return result(runId, document, metadata, qualityResult, titleMatch, journalQuality,
                    LlmJudgment.notRun("Title vector relevance is below active threshold."), "REJECTED_LOW_TITLE_RELEVANCE");
        }
        LlmJudgment judgment;
        try {
            judgment = llmJudge.judgeAbstract(
                    Path.of(properties.getPromptPath()),
                    metadata,
                    properties.getLlmMaxAttempts());
        } catch (Exception ex) {
            judgment = new LlmJudgment(LlmLabel.UNCERTAIN, 0.0, List.of(), "", List.of(),
                    "LLM judgment failed; manual review required: " + rootMessage(ex));
            log.warn("PreTreatment LLM judgment failed for document {}: {}", document.documentId(), rootMessage(ex));
        }
        return result(runId, document, metadata, qualityResult, titleMatch, journalQuality, judgment, "");
    }

    private PretreatmentDocumentResult result(UUID runId,
                                              ArtifactDocument document,
                                              RagDocumentMetadata metadata,
                                              QualityResult qualityResult,
                                              TitleMatchResult titleMatch,
                                              JournalQuality journalQuality,
                                              LlmJudgment judgment,
                                              String rejectReasonCode) {
        double confidence = judgment.confidence() == null ? 0.0 : judgment.confidence();
        FinalDecision finalDecision = finalDecision(qualityResult, titleMatch, judgment, confidence);
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
                titleMatch == null ? null : titleMatch.titleDecision(),
                titleMatch == null ? null : titleMatch.score(),
                titleMatch == null ? null : titleMatch.bestProfileTerm(),
                titleMatch == null ? Map.of() : titleMatch.thresholdPasses(),
                titleMatch == null ? null : titleMatch.vectorDecision(),
                journalQuality.tier(),
                judgment.label(),
                confidence,
                finalDecision,
                finalRejectReasonCode,
                judgment.taxa(),
                judgment.researchFocus(),
                judgment.evidenceChunkIds(),
                judgment.reason()
        );
    }

    private FinalDecision finalDecision(QualityResult qualityResult,
                                        TitleMatchResult titleMatch,
                                        LlmJudgment judgment,
                                        double confidence) {
        if (qualityResult != null && qualityResult.decision() == QualityDecision.REJECT) {
            return FinalDecision.REJECTED;
        }
        if (titleMatch != null && titleMatch.vectorDecision() == TitleVectorDecision.REJECT_LOW_TITLE_RELEVANCE) {
            return FinalDecision.REJECTED;
        }
        if (judgment.label() == LlmLabel.PRIMARY_OOMYCETE
                && confidence >= properties.getAcceptanceConfidenceThreshold()) {
            return FinalDecision.ACCEPTED;
        }
        if (judgment.label() == LlmLabel.INCIDENTAL_MENTION || judgment.label() == LlmLabel.NOT_OOMYCETE) {
            return FinalDecision.REJECTED;
        }
        return FinalDecision.UNCERTAIN;
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
                null,
                null,
                null,
                Map.of(),
                null,
                PretreatmentModels.JournalQualityTier.UNKNOWN,
                LlmLabel.NOT_RUN,
                0.0,
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
                count(results, FinalDecision.UNCERTAIN),
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
            case INCIDENTAL_MENTION -> "LLM_INCIDENTAL_MENTION";
            case NOT_OOMYCETE -> "LLM_NOT_OOMYCETE";
            default -> "";
        };
    }
}
