package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactDocument;
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
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityStatus;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.RelevanceDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.RelevanceSource;
import com.example.demo_01.ai.pretreatment.PretreatmentQualityGate.QualityResult;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import com.example.demo_01.ai.stage.CohortService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private RagDocumentRepository ragDocumentRepository;

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
        return findDocuments(runId, finalDecision, null, null, page, size);
    }

    public PretreatmentDocumentPage findDocuments(UUID runId,
                                                  FinalDecision finalDecision,
                                                  QualityStatus qualityStatus,
                                                  RelevanceDecision relevanceDecision,
                                                  int page, int size) {
        requireRun(runId);
        return repository.findDocuments(runId, finalDecision, qualityStatus, relevanceDecision, page, size);
    }

    public PretreatmentRunSummary garbageCollectRejectedVectors(UUID runId, boolean dryRun) {
        PretreatmentRunRecord run = requireRun(runId);
        List<UUID> rejectedIds = repository.findExplicitlyNotRelevantDocumentIds(runId);
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
            List<RagDocumentRecord> documents = selectDocuments();
            List<PretreatmentDocumentResult> results = new ArrayList<>();
            for (RagDocumentRecord document : documents) {
                ArtifactDocument artifact = artifactScanner.load(document.documentId(), artifactStorageDir(document)).orElse(null);
                PretreatmentDocumentResult result = screenDocument(runId, document, artifact);
                results.add(result);
                repository.insertResult(result);
            }
            PretreatmentRunSummary summary = summary(runId, PretreatmentMode.scan, actualOutputDir,
                    results, 0, actualStartedAt);
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
        return screenDocument(runId, document.documentId(), document.storageDir(), document.metadata(), document.chunks());
    }

    List<RagDocumentRecord> selectDocuments() {
        List<RagDocumentRecord> canonicalDocuments = ragDocumentRepository.findAllCanonical();
        if (properties.getDocumentIdFiles() == null || properties.getDocumentIdFiles().isEmpty()) {
            return properties.getMaxDocuments() > 0
                    ? canonicalDocuments.stream().limit(properties.getMaxDocuments()).toList()
                    : canonicalDocuments;
        }

        LinkedHashSet<UUID> requestedIds = new LinkedHashSet<>();
        for (String file : properties.getDocumentIdFiles()) {
            if (file == null || file.isBlank()) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(Path.of(file))) {
                    String value = line.trim();
                    if (!value.isEmpty()) {
                        requestedIds.add(UUID.fromString(value));
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Failed to read pretreatment document ID file: " + file, e);
            }
        }
        if (requestedIds.isEmpty()) {
            throw new IllegalStateException("Pretreatment document ID files did not contain any document IDs");
        }

        Map<UUID, RagDocumentRecord> canonicalById = new LinkedHashMap<>();
        Map<String, RagDocumentRecord> canonicalByPdfSha = new LinkedHashMap<>();
        for (RagDocumentRecord document : canonicalDocuments) {
            canonicalById.put(document.documentId(), document);
            if (document.pdfSha256() != null && !document.pdfSha256().isBlank()) {
                canonicalByPdfSha.put(document.pdfSha256(), document);
            }
        }
        Map<UUID, RagDocumentRecord> selectedById = new LinkedHashMap<>();
        List<UUID> missingIds = new ArrayList<>();
        for (UUID requestedId : requestedIds) {
            RagDocumentRecord document = canonicalById.get(requestedId);
            if (document == null) {
                document = artifactScanner.load(requestedId,
                                Path.of(properties.getArtifactRoot()).resolve(requestedId.toString()).toString())
                        .map(ArtifactDocument::manifest)
                        .map(manifest -> canonicalByPdfSha.get(manifest.pdfSha256()))
                        .orElse(null);
            }
            if (document == null) {
                missingIds.add(requestedId);
            } else {
                selectedById.put(document.documentId(), document);
            }
        }
        if (!missingIds.isEmpty()) {
            throw new IllegalStateException("Pretreatment document ID file includes documents that cannot be resolved to a canonical record: " + missingIds);
        }
        log.info("Selected {} canonical documents from {} requested document IDs", selectedById.size(), requestedIds.size());
        return List.copyOf(selectedById.values());
    }

    private PretreatmentDocumentResult screenDocument(UUID runId,
                                                      RagDocumentRecord document,
                                                      ArtifactDocument artifact) {
        RagDocumentMetadata metadata = new RagDocumentMetadata(
                document.doiRaw(), document.doiNormalized(), document.title(), document.authors(),
                document.affiliations(), document.abstractText(), document.journal(),
                document.publicationDate(), document.publicationYear());
        return screenDocument(runId, document.documentId(), document.storageRoot(), metadata,
                artifact == null ? List.of() : artifact.chunks());
    }

    private PretreatmentDocumentResult screenDocument(UUID runId,
                                                      UUID documentId,
                                                      String storageDir,
                                                      RagDocumentMetadata metadata,
                                                      List<com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk> chunks) {
        QualityResult qualityResult = qualityGate.evaluate(metadata, chunks, properties.getQuality());
        if (qualityResult.status() == QualityStatus.UNUSABLE) {
            return result(runId, documentId, storageDir, metadata, qualityResult,
                    LlmJudgment.notRun(qualityResult.reason()), RelevanceDecision.NOT_EVALUATED,
                    RelevanceSource.NO_METADATA, "");
        }
        if (isBlank(metadata == null ? null : metadata.abstractText())) {
            return result(runId, documentId, storageDir, metadata, qualityResult,
                    new LlmJudgment(LlmLabel.POTENTIALLY_RELEVANT, List.of(), "", List.of(),
                            "Abstract is missing; retained as potentially relevant."),
                    RelevanceDecision.POTENTIALLY_RELEVANT, RelevanceSource.TITLE_ONLY, "");
        }
        LlmJudgment judgment;
        try {
            judgment = llmJudge.judgeAbstract(
                    Path.of(properties.getPromptPath()),
                    metadata,
                    properties.getLlmMaxAttempts());
        } catch (Exception ex) {
            judgment = new LlmJudgment(LlmLabel.NOT_RUN, List.of(), "", List.of(),
                    "LLM judgment failed; retained as potentially relevant: " + rootMessage(ex));
            log.warn("PreTreatment LLM judgment failed for document {}: {}", documentId, rootMessage(ex));
        }
        RelevanceSource source = isBlank(metadata == null ? null : metadata.title())
                ? RelevanceSource.ABSTRACT_ONLY : RelevanceSource.TITLE_AND_ABSTRACT;
        RelevanceDecision relevance = relevanceDecision(judgment, source);
        return result(runId, documentId, storageDir, metadata, qualityResult, judgment, relevance, source, "");
    }

    private PretreatmentDocumentResult result(UUID runId,
                                              UUID documentId,
                                              String storageDir,
                                              RagDocumentMetadata metadata,
                                              QualityResult qualityResult,
                                              LlmJudgment judgment,
                                              RelevanceDecision relevanceDecision,
                                              RelevanceSource relevanceSource,
                                              String rejectReasonCode) {
        FinalDecision finalDecision = finalDecision(qualityResult, relevanceDecision);
        String finalRejectReasonCode = finalDecision == FinalDecision.REJECTED && (rejectReasonCode == null || rejectReasonCode.isBlank())
                ? reasonCode(judgment)
                : rejectReasonCode;
        return new PretreatmentDocumentResult(
                runId,
                documentId,
                storageDir,
                metadata == null ? null : metadata.title(),
                metadata == null ? null : metadata.journal(),
                metadata == null ? null : metadata.doiNormalized(),
                qualityResult == null ? null : qualityResult.decision(),
                qualityResult == null ? null : qualityResult.status(),
                qualityResult == null ? Map.of() : qualityResult.metrics(),
                judgment.label(),
                relevanceDecision,
                relevanceSource,
                finalDecision,
                finalRejectReasonCode,
                judgment.taxa(),
                judgment.researchFocus(),
                judgment.evidenceChunkIds(),
                judgment.reason()
        );
    }

    private FinalDecision finalDecision(QualityResult qualityResult,
                                        RelevanceDecision relevanceDecision) {
        if (qualityResult != null && qualityResult.status() == QualityStatus.UNUSABLE) {
            return FinalDecision.SKIPPED;
        }
        if (relevanceDecision == RelevanceDecision.NOT_RELEVANT) {
            return FinalDecision.REJECTED;
        }
        return FinalDecision.ACCEPTED;
    }

    private PretreatmentRunSummary summary(UUID runId,
                                           PretreatmentMode mode,
                                           Path outputDir,
                                           List<PretreatmentDocumentResult> results,
                                           int vectorsRemoved,
                                           Instant startedAt) {
        return new PretreatmentRunSummary(
                runId,
                mode,
                outputDir.toString(),
                results.size(),
                results.size(),
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
        List<UUID> fullTextEvidence = results.stream()
                .filter(result -> result.finalDecision() == FinalDecision.ACCEPTED)
                .filter(result -> result.qualityStatus() == QualityStatus.FULL_TEXT_READY)
                .map(PretreatmentDocumentResult::documentId)
                .filter(Objects::nonNull)
                .toList();
        List<UUID> abstractAnalysis = results.stream()
                .filter(result -> result.finalDecision() == FinalDecision.ACCEPTED)
                .filter(result -> result.relevanceSource() == RelevanceSource.TITLE_AND_ABSTRACT
                        || result.relevanceSource() == RelevanceSource.ABSTRACT_ONLY)
                .map(PretreatmentDocumentResult::documentId)
                .filter(Objects::nonNull)
                .toList();
        List<UUID> rejected = ids(results, FinalDecision.REJECTED);
        UUID acceptedCohortId = cohortService.create(
                "filter-accepted-" + runId,
                "PRETREATMENT",
                runId,
                accepted,
                "retained as relevant or potentially relevant");
        UUID rejectedCohortId = cohortService.create(
                "filter-rejected-" + runId,
                "PRETREATMENT",
                runId,
                rejected,
                "explicitly not relevant");
        UUID abstractAnalysisCohortId = cohortService.create(
                "filter-abstract-analysis-" + runId,
                "PRETREATMENT",
                runId,
                abstractAnalysis,
                "accepted with abstract");
        UUID fullTextEvidenceCohortId = cohortService.create(
                "filter-full-text-evidence-" + runId,
                "PRETREATMENT",
                runId,
                fullTextEvidence,
                "accepted and full-text ready");
        repository.setCohorts(runId, acceptedCohortId, rejectedCohortId,
                abstractAnalysisCohortId, fullTextEvidenceCohortId, null);
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

    private RelevanceDecision relevanceDecision(LlmJudgment judgment, RelevanceSource source) {
        if (judgment == null || judgment.label() == null || judgment.label() == LlmLabel.NOT_RUN) {
            return RelevanceDecision.POTENTIALLY_RELEVANT;
        }
        if (judgment.label() == LlmLabel.RELEVANT) {
            return RelevanceDecision.RELEVANT;
        }
        if (judgment.label() == LlmLabel.POTENTIALLY_RELEVANT) {
            return RelevanceDecision.POTENTIALLY_RELEVANT;
        }
        return source == RelevanceSource.TITLE_AND_ABSTRACT
                ? RelevanceDecision.NOT_RELEVANT
                : RelevanceDecision.POTENTIALLY_RELEVANT;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String artifactStorageDir(RagDocumentRecord document) {
        if (document.storageRoot() != null && !document.storageRoot().isBlank()) {
            return document.storageRoot();
        }
        return Path.of(properties.getArtifactRoot(), document.documentId().toString()).toString();
    }
}
