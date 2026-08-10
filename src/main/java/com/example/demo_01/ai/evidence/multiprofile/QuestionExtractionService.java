package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceConfigScope;
import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ClassificationStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.EvidencePage;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.DryRunRequest;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.DryRunResult;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionCandidate;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionDocumentStatus;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunAccepted;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunDocumentPage;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunRecord;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunRequest;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionSourceType;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.stage.CohortService;
import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class QuestionExtractionService {

    private static final List<ClassificationStatus> DEFAULT_INCLUDE_STATUSES = List.of(
            ClassificationStatus.SUPPORTED, ClassificationStatus.UNCERTAIN);

    @Resource
    private QuestionExtractionRepository repository;

    @Resource
    private MultiProfileEvidenceRepository multiProfileRepository;

    @Resource
    private MultiProfileEvidenceService multiProfileEvidenceService;

    @Resource
    private MultiProfileEvidencePersistenceService persistenceService;

    @Resource
    private EvidenceProfileRegistry profileRegistry;

    @Resource
    private MultiProfileOutputValidator outputValidator;

    @Resource
    private EvidenceRepository evidenceRepository;

    @Resource
    private EvidenceConfigScope configScope;

    @Resource
    private CohortService cohortService;

    @Resource
    private EvidenceProperties properties;

    @Resource
    private DashScopeModelProperties modelProperties;

    @Resource
    @Qualifier("multiProfileEvidenceTaskExecutor")
    private TaskExecutor documentExecutor;

    @Resource
    @Qualifier("multiProfileEvidenceBatchTaskExecutor")
    private TaskExecutor batchExecutor;

    public ExtractionRunAccepted submit(ExtractionRunRequest request) {
        if (request == null || request.questionId() == null || request.questionId().isBlank()) {
            throw new IllegalArgumentException("questionId is required");
        }
        if (request.sourceType() == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
        profileRegistry.require(request.questionId());

        List<ClassificationStatus> includeStatuses = resolveIncludeStatuses(request);
        List<ExtractionCandidate> candidates = resolveCandidates(request, includeStatuses);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No documents matched the extraction source for "
                    + request.questionId());
        }

        EvidenceProperties resolved = configScope.resolve(request.overrides());
        String configSnapshot = configScope.snapshot(resolved);
        String configHash = multiProfileEvidenceService.hashExtractionConfig(configSnapshot);
        String inputHash = inputHash(candidates);
        String modelName = modelName();

        ExtractionRunRecord active = repository.findActiveRun(request.questionId(), inputHash)
                .orElse(null);
        if (active != null) {
            return new ExtractionRunAccepted(
                    active.runId(), active.questionId(), active.status(),
                    active.totalDocuments(), true);
        }
        if (!request.force()) {
            ExtractionRunRecord reusable = repository.findReusableRun(
                    request.questionId(), inputHash, configHash, modelName).orElse(null);
            if (reusable != null) {
                return new ExtractionRunAccepted(
                        reusable.runId(), reusable.questionId(), reusable.status(),
                        reusable.totalDocuments(), true);
            }
        }

        UUID runId = UUID.randomUUID();
        ExtractionRunRecord run = new ExtractionRunRecord(
                runId,
                request.questionId(),
                request.label(),
                request.sourceType(),
                request.classificationBatchId(),
                request.sourceExperimentId(),
                request.cohortId(),
                includeStatuses,
                MultiProfileEvidenceModels.PROFILE_VERSION,
                inputHash,
                configHash,
                configSnapshot,
                modelName,
                request.force(),
                MultiProfileEvidenceModels.BatchStatus.QUEUED,
                candidates.size(),
                0, 0, 0, 0, 0,
                null, null, null, null, null, null, null);
        repository.insertRun(run, candidates);
        batchExecutor.execute(() -> runExtraction(run, candidates, resolved));
        return new ExtractionRunAccepted(
                runId, request.questionId(),
                MultiProfileEvidenceModels.BatchStatus.QUEUED, candidates.size(), false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverIncompleteRuns() {
        try {
            for (ExtractionRunRecord run : repository.findRecoverableRuns()) {
                List<ExtractionCandidate> candidates = reloadCandidates(run);
                if (candidates.size() != run.totalDocuments()) {
                    repository.failRun(run.runId(),
                            "Cannot resume extraction run because the document set changed");
                    continue;
                }
                EvidenceProperties bound;
                try {
                    bound = configScope.fromSnapshot(run.configSnapshotJson());
                } catch (Exception e) {
                    log.warn("Falling back to global evidence config for resume of run {}: {}",
                            run.runId(), e.getMessage());
                    bound = configScope.resolve(null);
                }
                EvidenceProperties finalBound = bound;
                batchExecutor.execute(() -> runExtraction(run, candidates, finalBound));
            }
        } catch (Exception e) {
            log.warn("Unable to inspect recoverable question extraction runs: {}", e.getMessage());
        }
    }

    public ExtractionRunRecord requireRun(UUID runId) {
        return repository.findRun(runId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR,
                        "Question extraction run not found: " + runId));
    }

    public ExtractionRunDocumentPage findDocuments(UUID runId, ExtractionDocumentStatus status,
                                                   int page, int size) {
        requireRun(runId);
        return repository.findDocuments(runId, status, page, size);
    }

    public EvidencePage findEvidence(UUID runId, UUID documentId, ReviewStatus reviewStatus,
                                     int page, int size) {
        requireRun(runId);
        return repository.findEvidence(runId, documentId, reviewStatus, page, size);
    }

    public DryRunResult dryRun(UUID documentId, DryRunRequest request) {
        if (request == null || request.questionId() == null || request.questionId().isBlank()) {
            throw new IllegalArgumentException("questionId is required");
        }
        var profile = profileRegistry.require(request.questionId());
        SourceDocument document = multiProfileRepository.findDocument(documentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Document not found: " + documentId));
        EvidenceProperties resolved = configScope.resolve(request.overrides());
        String snapshot = configScope.snapshot(resolved);
        long started = System.nanoTime();
        try {
            List<EvidenceChunk> chunks = evidenceRepository.findDocumentChunks(documentId);
            List<ValidatedEvidenceRow> rows = configScope.call(resolved, () ->
                    multiProfileEvidenceService.extractQuestion(
                            UUID.randomUUID(), document, chunks, request.questionId()));
            List<List<String>> cells = rows.stream().map(ValidatedEvidenceRow::cells).toList();
            List<List<String>> anchors = rows.stream()
                    .map(row -> row.anchors().stream()
                            .map(anchor -> anchor.chunkId() + " | " + anchor.exactQuote())
                            .toList())
                    .toList();
            String markdown = rows.isEmpty()
                    ? ""
                    : outputValidator.renderMarkdown(profile, rows);
            return new DryRunResult(
                    documentId, document.title(), request.questionId(),
                    chunks.size(), chunks.size(), rows.size(),
                    (System.nanoTime() - started) / 1_000_000L,
                    profile.headers(),
                    cells, anchors, List.of(), markdown, snapshot, null);
        } catch (Exception e) {
            return new DryRunResult(
                    documentId, document.title(), request.questionId(),
                    0, 0, 0, (System.nanoTime() - started) / 1_000_000L,
                    profile.headers(),
                    List.of(), List.of(), List.of(), "", snapshot, message(e));
        }
    }

    private void runExtraction(ExtractionRunRecord run,
                               List<ExtractionCandidate> candidates,
                               EvidenceProperties resolved) {
        repository.markRunRunning(run.runId());
        try {
            Set<UUID> processIds = Set.copyOf(repository.findDocumentsToProcess(run.runId()));
            List<ExtractionCandidate> remaining = candidates.stream()
                    .filter(candidate -> processIds.contains(candidate.document().documentId()))
                    .toList();
            int concurrency = Math.max(1, resolved.getAsyncThreads());
            for (int start = 0; start < remaining.size(); start += concurrency) {
                int end = Math.min(remaining.size(), start + concurrency);
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (ExtractionCandidate candidate : remaining.subList(start, end)) {
                    futures.add(CompletableFuture.runAsync(
                            () -> processDocument(run, candidate, resolved),
                            command -> documentExecutor.execute(command)));
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                repository.refreshRunCounts(run.runId());
            }
            Path root = runRoot(run.runId());
            repository.finishRun(run.runId(), root.toString());
        } catch (Exception e) {
            repository.failRun(run.runId(), message(e));
            log.error("Question extraction run {} failed: {}", run.runId(), message(e), e);
        }
    }

    private void processDocument(ExtractionRunRecord run,
                                 ExtractionCandidate candidate,
                                 EvidenceProperties resolved) {
        SourceDocument document = candidate.document();
        repository.markDocumentRunning(run.runId(), document.documentId());
        configScope.run(resolved, () -> {
            List<EvidenceChunk> chunks;
            try {
                chunks = evidenceRepository.findDocumentChunks(document.documentId());
            } catch (Exception e) {
                repository.finishDocument(run.runId(), document.documentId(),
                        ExtractionDocumentStatus.FAILED, null, 0, null, message(e));
                return;
            }
            if (chunks.isEmpty()) {
                repository.finishDocument(run.runId(), document.documentId(),
                        ExtractionDocumentStatus.NO_CHUNKS, 0, 0, null, "No chunks available");
                return;
            }
            try {
                List<ValidatedEvidenceRow> rows = multiProfileEvidenceService.extractQuestion(
                        run.runId(), document, chunks, run.questionId());
                Path outputPath = runRoot(run.runId()).resolve("papers")
                        .resolve(document.documentId().toString())
                        .resolve(run.questionId() + ".md");
                multiProfileEvidenceService.writeEvidenceMarkdown(
                        outputPath, run.questionId(), rows);
                ClassificationStatus classificationStatus =
                        candidate.classificationStatus() == null
                                ? ClassificationStatus.NOT_CLASSIFIED
                                : candidate.classificationStatus();
                persistenceService.replaceEvidence(
                        run.classificationBatchId(), run.runId(), document.documentId(),
                        run.questionId(), classificationStatus, rows,
                        run.inputHash(), run.configHash(), run.modelName());
                repository.finishDocument(
                        run.runId(), document.documentId(),
                        rows.isEmpty()
                                ? ExtractionDocumentStatus.NO_EVIDENCE
                                : ExtractionDocumentStatus.COMPLETED,
                        chunks.size(), rows.size(),
                        rows.isEmpty() ? null : outputPath.toString(), null);
            } catch (Exception e) {
                repository.finishDocument(run.runId(), document.documentId(),
                        ExtractionDocumentStatus.FAILED, chunks.size(), 0, null, message(e));
                log.warn("Question {} extraction failed for document {}: {}",
                        run.questionId(), document.documentId(), message(e), e);
            }
        });
    }

    private List<ExtractionCandidate> resolveCandidates(ExtractionRunRequest request,
                                                        List<ClassificationStatus> includeStatuses) {
        return switch (request.sourceType()) {
            case CLASSIFICATION_RUN -> {
                if (request.classificationBatchId() == null) {
                    throw new IllegalArgumentException(
                            "classificationBatchId is required for CLASSIFICATION_RUN");
                }
                multiProfileRepository.findBatch(request.classificationBatchId())
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.NOT_FOUND_ERROR,
                                "Classification batch not found: "
                                        + request.classificationBatchId()));
                List<MultiProfileEvidenceModels.QuestionMatchRecord> matches =
                        multiProfileRepository.findMatchesForQuestion(
                                request.classificationBatchId(), request.questionId(),
                                includeStatuses);
                List<UUID> documentIds = matches.stream()
                        .map(MultiProfileEvidenceModels.QuestionMatchRecord::documentId)
                        .toList();
                List<SourceDocument> documents =
                        multiProfileRepository.findDocumentsByIds(documentIds);
                yield matches.stream()
                        .map(match -> {
                            SourceDocument document = documents.stream()
                                    .filter(item -> item.documentId().equals(match.documentId()))
                                    .findFirst()
                                    .orElse(null);
                            if (document == null) {
                                return null;
                            }
                            return new ExtractionCandidate(
                                    document, match.classificationStatus());
                        })
                        .filter(Objects::nonNull)
                        .toList();
            }
            case EXPERIMENT -> {
                UUID experimentId = request.sourceExperimentId() == null
                        ? MultiProfileEvidenceModels.DEFAULT_SOURCE_EXPERIMENT_ID
                        : request.sourceExperimentId();
                yield multiProfileRepository.findSourceDocuments(experimentId).stream()
                        .map(document -> new ExtractionCandidate(
                                document, ClassificationStatus.NOT_CLASSIFIED))
                        .toList();
            }
            case DOCUMENT_IDS -> {
                if (request.documentIds() == null || request.documentIds().isEmpty()) {
                    throw new IllegalArgumentException(
                            "documentIds is required for DOCUMENT_IDS");
                }
                yield multiProfileRepository.findDocumentsByIds(request.documentIds()).stream()
                        .map(document -> new ExtractionCandidate(
                                document, ClassificationStatus.NOT_CLASSIFIED))
                        .toList();
            }
            case COHORT -> {
                if (request.cohortId() == null) {
                    throw new IllegalArgumentException("cohortId is required for COHORT");
                }
                yield multiProfileRepository.findDocumentsByIds(
                                cohortService.findDocumentIds(request.cohortId()))
                        .stream()
                        .map(document -> new ExtractionCandidate(
                                document, ClassificationStatus.NOT_CLASSIFIED))
                        .toList();
            }
        };
    }

    private List<ExtractionCandidate> reloadCandidates(ExtractionRunRecord run) {
        ExtractionRunRequest synthetic = new ExtractionRunRequest(
                run.questionId(), run.label(), run.sourceType(),
                run.classificationBatchId(), run.sourceExperimentId(),
                run.cohortId(), null, run.includeStatuses(), null, true);
        if (run.sourceType() == ExtractionSourceType.DOCUMENT_IDS) {
            List<UUID> ids = repository.findDocuments(run.runId(), null, 0, 10_000)
                    .items().stream()
                    .map(QuestionExtractionModels.ExtractionRunDocument::documentId)
                    .toList();
            synthetic = new ExtractionRunRequest(
                    run.questionId(), run.label(), run.sourceType(),
                    run.classificationBatchId(), run.sourceExperimentId(),
                    run.cohortId(), ids, run.includeStatuses(), null, true);
        }
        return resolveCandidates(synthetic, resolveIncludeStatuses(synthetic));
    }

    private List<ClassificationStatus> resolveIncludeStatuses(ExtractionRunRequest request) {
        if (request.includeStatuses() == null || request.includeStatuses().isEmpty()) {
            return DEFAULT_INCLUDE_STATUSES;
        }
        return List.copyOf(request.includeStatuses());
    }

    private Path runRoot(UUID runId) {
        return Path.of(properties.getOutputRoot()).toAbsolutePath().normalize()
                .resolve("question-extractions").resolve(runId.toString());
    }

    private String inputHash(List<ExtractionCandidate> candidates) {
        return sha256(candidates.stream()
                .map(candidate -> candidate.document().documentId().toString())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(""));
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String modelName() {
        return modelProperties == null || modelProperties.getChatModel() == null
                ? null : modelProperties.getChatModel().getModelName();
    }

    private String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String value = error.getMessage();
        return value == null || value.isBlank()
                ? error.getClass().getSimpleName() : value;
    }
}
