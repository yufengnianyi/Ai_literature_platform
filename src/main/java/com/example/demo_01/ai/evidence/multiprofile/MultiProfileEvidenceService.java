package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.example.demo_01.ai.stage.CohortService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class MultiProfileEvidenceService {

    @Resource
    private MultiProfileEvidenceRepository repository;

    @Resource
    private EvidenceRepository evidenceRepository;

    @Resource
    private EvidenceProfileRegistry profileRegistry;

    @Resource
    private MultiProfileOutputValidator outputValidator;

    @Resource
    private MultiProfileEvidencePersistenceService persistenceService;

    @Resource
    private MultiProfileEvidenceExportService exportService;

    @Resource
    private EvidenceExtractionAgent extractionAgent;

    @Resource
    private EvidenceVerifierAgent verifierAgent;

    @Resource
    private EvidenceCoverageAgent coverageAgent;

    @Resource
    private EvidenceRetrievalAgent retrievalAgent;

    @Resource
    private EvidenceReconcilerAgent reconcilerAgent;

    @Resource
    private com.example.demo_01.ai.evidence.table.TableContextService tableContextService;

    @Resource
    private EvidenceAgentTelemetryService telemetryService;

    @Resource
    private CohortService cohortService;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private EvidenceProperties properties;

    @Resource
    private com.example.demo_01.ai.evidence.config.EvidenceConfigScope configScope;

    @Resource
    private DashScopeModelProperties modelProperties;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    @Qualifier("multiProfileEvidenceTaskExecutor")
    private TaskExecutor documentExecutor;

    @Resource
    @Qualifier("multiProfileEvidenceBatchTaskExecutor")
    private TaskExecutor batchExecutor;

    public BatchAcceptedResponse submit(BatchRequest request) {
        SourceSelection source = resolveSource(request);
        UUID sourceExperimentId = source.sourceExperimentId();
        boolean force = request != null && request.force();
        boolean runExtraction = request == null || request.resolvedRunExtraction();
        Integer expectedDocuments = request == null ? null : request.expectedDocuments();
        BatchRecord active = sourceExperimentId == null
                ? null : repository.findActiveBatch(sourceExperimentId).orElse(null);
        if (active != null) {
            return new BatchAcceptedResponse(
                    active.batchId(), active.status(), active.totalDocuments(), true);
        }

        List<SourceDocument> documents = source.documents();
        if (documents.isEmpty()) {
            throw new IllegalStateException("Multi-profile source has no completed canonical documents");
        }
        if (expectedDocuments != null && documents.size() != expectedDocuments) {
            throw new IllegalStateException("Multi-profile source experiment must contain exactly "
                    + expectedDocuments + " completed canonical documents: "
                    + sourceExperimentId + " returned " + documents.size());
        }
        String sourceHash = sourceHash(documents);
        String classificationConfigHash = classificationConfigHash();
        String extractionConfigHash = extractionConfigHash();
        String legacyPromptHash = legacyPromptHash(classificationConfigHash, extractionConfigHash);
        String modelName = modelName();
        if (!force) {
            BatchRecord reusable = repository.findReusableBatch(
                    sourceExperimentId, sourceHash, MultiProfileEvidenceModels.PROFILE_VERSION,
                    classificationConfigHash, legacyPromptHash, modelName)
                    .orElse(null);
            if (reusable != null
                    && (!runExtraction || reusable.runExtraction())) {
                return new BatchAcceptedResponse(
                        reusable.batchId(), reusable.status(), reusable.totalDocuments(), true);
            }
        }

        UUID batchId = UUID.randomUUID();
        BatchRecord batch = new BatchRecord(
                batchId, source.sourceType(), sourceExperimentId, source.sourcePretreatmentRunId(),
                sourceHash, MultiProfileEvidenceModels.PROFILE_VERSION,
                legacyPromptHash, classificationConfigHash, extractionConfigHash, runExtraction,
                modelName, force, BatchStatus.QUEUED, documents.size(),
                0, 0, 0, 0, 0, 0,
                null, null, null, null, null, null, null);
        repository.insertBatch(batch, documents);
        batchExecutor.execute(() -> runBatch(batch, documents));
        return new BatchAcceptedResponse(batchId, BatchStatus.QUEUED, documents.size(), false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverIncompleteBatches() {
        try {
            for (BatchRecord batch : repository.findRecoverableBatches()) {
                List<SourceDocument> documents = reloadSourceDocuments(batch);
                if (documents.size() != batch.totalDocuments()) {
                    repository.failBatch(batch.batchId(),
                            "Cannot resume batch because the source document set changed");
                    continue;
                }
                batchExecutor.execute(() -> runBatch(batch, documents));
            }
        } catch (Exception e) {
            log.warn("Unable to inspect recoverable multi-profile evidence batches: {}", message(e));
        }
    }

    public BatchRecord requireBatch(UUID batchId) {
        return repository.findBatch(batchId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR,
                        "Multi-profile evidence batch not found: " + batchId));
    }

    public DocumentPage findDocuments(UUID batchId, String questionId,
                                      ClassificationStatus classificationStatus,
                                      ProfileExtractionStatus extractionStatus,
                                      int page, int size) {
        requireBatch(batchId);
        if (questionId != null && !questionId.isBlank()) {
            profileRegistry.require(questionId);
        }
        return repository.findDocuments(
                batchId, questionId, classificationStatus, extractionStatus, page, size);
    }

    public EvidencePage findEvidence(UUID batchId, String questionId, UUID documentId,
                                     ReviewStatus reviewStatus, int page, int size) {
        requireBatch(batchId);
        if (questionId != null && !questionId.isBlank()) {
            profileRegistry.require(questionId);
        }
        return repository.findEvidence(batchId, questionId, documentId, reviewStatus, page, size);
    }

    public Path exportPath(UUID batchId) {
        BatchRecord batch = requireBatch(batchId);
        Path path = batch.outputPath() == null || batch.outputPath().isBlank()
                ? batchRoot(batchId).resolve("evidence-summary.xlsx")
                : Path.of(batch.outputPath());
        if (!Files.isRegularFile(path)) {
            exportService.generate(batchId, path);
        }
        return path;
    }

    private void runBatch(BatchRecord batch, List<SourceDocument> documents) {
        repository.markBatchRunning(batch.batchId());
        try {
            Set<UUID> processIds = Set.copyOf(
                    repository.findDocumentsToProcess(batch.batchId()));
            List<SourceDocument> remaining = documents.stream()
                    .filter(document -> processIds.contains(document.documentId()))
                    .toList();
            int concurrency = Math.max(1, properties.getAsyncThreads());
            for (int start = 0; start < remaining.size(); start += concurrency) {
                int end = Math.min(remaining.size(), start + concurrency);
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (SourceDocument document : remaining.subList(start, end)) {
                    futures.add(CompletableFuture.runAsync(
                            () -> processDocument(batch, document),
                            command -> documentExecutor.execute(command)));
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                repository.refreshBatchCounts(batch.batchId());
            }
            Path workbook = batchRoot(batch.batchId()).resolve("evidence-summary.xlsx");
            exportService.generate(batch.batchId(), workbook);
            repository.finishBatch(batch.batchId(), workbook.toString());
            publishClassificationCohorts(batch.batchId());
        } catch (Exception e) {
            repository.failBatch(batch.batchId(), message(e));
            log.error("Multi-profile evidence batch {} failed: {}",
                    batch.batchId(), message(e), e);
        }
    }

    private void processDocument(BatchRecord batch, SourceDocument document) {
        repository.markDocumentRunning(batch.batchId(), document.documentId());
        List<EvidenceChunk> chunks;
        try {
            chunks = evidenceRepository.findDocumentChunks(document.documentId());
        } catch (Exception e) {
            failClassification(batch.batchId(), document, "Failed to load chunks: " + message(e));
            repository.finishDocument(batch.batchId(), document.documentId(),
                    DocumentStatus.FAILED, null, message(e));
            return;
        }
        if (chunks.isEmpty()) {
            failClassification(batch.batchId(), document, "No chunks available");
            repository.finishDocument(batch.batchId(), document.documentId(),
                    DocumentStatus.NO_CHUNKS, 0, "No chunks available");
            return;
        }

        List<ClassifiedQuestion> classifications;
        try {
            classifications = classify(document, chunks);
            for (ClassifiedQuestion classification : classifications) {
                repository.upsertMatch(
                        batch.batchId(), document.documentId(), classification,
                        batch.runExtraction());
            }
        } catch (Exception e) {
            failClassification(batch.batchId(), document, message(e));
            repository.finishDocument(batch.batchId(), document.documentId(),
                    DocumentStatus.FAILED, chunks.size(), message(e));
            return;
        }

        if (batch.runExtraction()) {
            for (ClassifiedQuestion classification : classifications) {
                if (!shouldExtract(classification.status())) {
                    continue;
                }
                extractProfile(batch, document, chunks, classification);
            }
        }
        List<QuestionMatchRecord> finalMatches =
                repository.findMatches(batch.batchId(), document.documentId());
        boolean failed = finalMatches.stream().anyMatch(match ->
                match.classificationStatus() == ClassificationStatus.FAILED
                        || match.extractionStatus() == ProfileExtractionStatus.FAILED);
        repository.finishDocument(batch.batchId(), document.documentId(),
                failed ? DocumentStatus.PARTIAL_FAILED : DocumentStatus.COMPLETED,
                chunks.size(), failed ? "One or more question profiles failed" : null);
    }

    boolean shouldExtract(ClassificationStatus status) {
        return status == ClassificationStatus.SUPPORTED
                || status == ClassificationStatus.UNCERTAIN;
    }

    private List<ClassifiedQuestion> classify(SourceDocument document, List<EvidenceChunk> chunks) {
        List<List<ClassifiedQuestion>> outputs = new ArrayList<>();
        for (List<EvidenceChunk> modelChunks : modelBatches(chunks)) {
            outputs.add(callClassificationModel(document, modelChunks));
        }
        return outputValidator.mergeClassifications(profileRegistry, outputs);
    }

    private List<ClassifiedQuestion> callClassificationModel(
            SourceDocument document, List<EvidenceChunk> chunks) {
        String systemPrompt = PromptResources.load(
                PromptCatalog.EVIDENCE_MULTI_PROFILE_CLASSIFICATION_SYSTEM);
        String baseUserMessage = classificationInput(document, chunks);
        Exception lastError = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            String userMessage = retryMessage(baseUserMessage, lastError);
            try {
                String raw = responseText(chatClient.chatStandard(
                        SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
                return outputValidator.parseClassification(raw, profileRegistry, chunks);
            } catch (Exception e) {
                lastError = e;
                log.warn("Multi-profile classification attempt {}/{} failed for document {}: {}",
                        attempt, properties.getMaxAttempts(), document.documentId(), message(e));
            }
        }
        throw new IllegalStateException("Multi-profile classification failed after "
                + properties.getMaxAttempts() + " attempts", lastError);
    }

    private void extractProfile(BatchRecord batch,
                                SourceDocument document,
                                List<EvidenceChunk> chunks,
                                ClassifiedQuestion classification) {
        String questionId = classification.questionId();
        repository.markExtractionRunning(batch.batchId(), document.documentId(), questionId);
        try {
            List<ValidatedEvidenceRow> rows = extractQuestion(
                    batch.batchId(), document, chunks, questionId);
            Path outputPath = documentOutputPath(batch.batchId(), document.documentId(), questionId);
            if (rows.isEmpty()) {
                Files.deleteIfExists(outputPath);
            } else {
                EvidenceProfile profile = profileRegistry.require(questionId);
                writeAtomically(outputPath, outputValidator.renderMarkdown(profile, rows));
            }
            persistenceService.replaceEvidence(
                    batch.batchId(), document.documentId(), questionId,
                    classification.status(), rows, batch.sourceHash(),
                    batch.extractionConfigHash() == null
                            ? batch.promptHash() : batch.extractionConfigHash(),
                    batch.modelName());
            repository.finishExtraction(
                    batch.batchId(), document.documentId(), questionId,
                    rows.isEmpty()
                            ? ProfileExtractionStatus.NO_EVIDENCE
                            : ProfileExtractionStatus.COMPLETED,
                    rows.size(), rows.isEmpty() ? null : outputPath.toString(), null);
        } catch (Exception e) {
            repository.finishExtraction(
                    batch.batchId(), document.documentId(), questionId,
                    ProfileExtractionStatus.FAILED, 0, null, message(e));
            log.warn("Evidence extraction failed for document {} profile {}: {}",
                    document.documentId(), questionId, message(e), e);
        }
    }

    /**
     * Shared extraction pipeline for both legacy multi-profile batches and stage-4
     * single-question runs. Honours {@link com.example.demo_01.ai.evidence.config.EvidenceConfigScope}
     * overrides bound on the current thread.
     */
    public List<ValidatedEvidenceRow> extractQuestion(UUID scopeId,
                                                      SourceDocument document,
                                                      List<EvidenceChunk> chunks,
                                                      String questionId) {
        EvidenceProperties active = configScope.current();
        EvidenceProfile profile = profileRegistry.require(questionId);
        List<EvidenceChunk> profileChunks = tableContextService.augment(
                scopeId, document.documentId(), profile, chunks);
        Map<String, ValidatedEvidenceRow> unique = new LinkedHashMap<>();
        List<List<EvidenceChunk>> batches = telemetryService.timed(
                scopeId, document.documentId(), questionId, "retriever",
                1, active.getAgents().getRetriever().isOnDemandEnabled() ? 1 : 0, 0,
                telemetryService.detail("onDemand",
                        active.getAgents().getRetriever().isOnDemandEnabled()),
                () -> retrievalAgent.modelBatches(profile, profileChunks));

        for (List<EvidenceChunk> modelChunks : batches) {
            List<ValidatedEvidenceRow> extracted = telemetryService.timed(
                    scopeId, document.documentId(), questionId, "extractor",
                    1, 1, 0,
                    telemetryService.detail("chunkCount", modelChunks.size()),
                    () -> extractionAgent.extract(document, profile, modelChunks));
            for (ValidatedEvidenceRow row : extracted) {
                unique.putIfAbsent(row.fingerprint(), row);
            }
        }

        List<ValidatedEvidenceRow> extractedRows = List.copyOf(unique.values());
        final List<ValidatedEvidenceRow> toVerify = extractedRows;
        List<ValidatedEvidenceRow> verifiedRows = telemetryService.timed(
                scopeId, document.documentId(), questionId, "verifier",
                1, active.getAgents().getVerifier().isEnabled() ? 1 : 0, 0,
                telemetryService.detail("rowCount", toVerify.size()),
                () -> verifierAgent.verify(profile, toVerify, profileChunks));
        final List<ValidatedEvidenceRow> toCover = verifiedRows;
        List<ValidatedEvidenceRow> coveredRows = telemetryService.timed(
                scopeId, document.documentId(), questionId, "coverage",
                1, active.getAgents().getCoverage().isEnabled() ? 1 : 0, 0,
                telemetryService.detail("rowCountBefore", toCover.size()),
                () -> coverageAgent.recover(
                        scopeId, document, profile, profileChunks, toCover));
        final List<ValidatedEvidenceRow> toReconcile = coveredRows;
        return telemetryService.timed(
                scopeId, document.documentId(), questionId, "reconciler",
                1, 0, 0,
                telemetryService.detail("rowCount", toReconcile.size()),
                () -> reconcilerAgent.reconcile(document, profile, toReconcile));
    }

    private List<List<EvidenceChunk>> modelBatches(List<EvidenceChunk> chunks) {
        int totalChars = chunks.stream().mapToInt(chunk -> value(chunk.text()).length()).sum();
        if (chunks.size() <= properties.getMaxSinglePassChunks()
                && totalChars <= properties.getMaxSinglePassChars()) {
            return List.of(mergeChunks(contextChunks(chunks), chunks));
        }
        int batchSize = Math.max(1, properties.getChunkBatchSize());
        List<EvidenceChunk> sharedContext = contextChunks(chunks);
        List<List<EvidenceChunk>> batches = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            int adjacentStart = Math.max(0, start - 1);
            int adjacentEnd = Math.min(chunks.size(), end + 1);
            batches.add(mergeChunks(
                    sharedContext, chunks.subList(adjacentStart, adjacentEnd)));
        }
        return List.copyOf(batches);
    }

    private List<EvidenceChunk> contextChunks(List<EvidenceChunk> chunks) {
        List<EvidenceChunk> context = chunks.stream()
                .filter(chunk -> {
                    String section = value(chunk.sectionPath()).toLowerCase(Locale.ROOT);
                    return section.contains("abstract") || section.contains("摘要")
                            || section.contains("method") || section.contains("材料")
                            || section.contains("方法") || section.contains("result")
                            || section.contains("结果");
                })
                .limit(6)
                .toList();
        return context.isEmpty() ? chunks.stream().limit(2).toList() : context;
    }

    private List<EvidenceChunk> mergeChunks(List<EvidenceChunk> context, List<EvidenceChunk> batch) {
        Map<String, EvidenceChunk> merged = new LinkedHashMap<>();
        for (EvidenceChunk chunk : context) {
            merged.put(chunkKey(chunk), chunk);
        }
        for (EvidenceChunk chunk : batch) {
            merged.put(chunkKey(chunk), chunk);
        }
        return List.copyOf(merged.values());
    }

    private String classificationInput(SourceDocument document, List<EvidenceChunk> chunks) {
        StringBuilder questions = new StringBuilder();
        for (EvidenceProfile profile : profileRegistry.all()) {
            questions.append("- ").append(profile.questionId()).append(" ")
                    .append(profile.title()).append(": ").append(profile.scope()).append('\n');
        }
        return """
                Questions:
                %s

                %s

                Supplied chunks:
                %s
                """.formatted(questions, documentMetadata(document), renderChunks(chunks));
    }

    private String documentMetadata(SourceDocument document) {
        return """
                Document metadata:
                - document_id: %s
                - title: %s
                - authors: %s
                - publication_year: %s
                - journal: %s
                - doi: %s
                """.formatted(
                document.documentId(), value(document.title()),
                String.join(", ", document.authors() == null ? List.of() : document.authors()),
                document.publicationYear() == null ? "" : document.publicationYear(),
                value(document.journal()), value(document.doi()));
    }

    private String renderChunks(List<EvidenceChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (EvidenceChunk chunk : chunks) {
            builder.append("\n--- chunk_id=").append(value(chunk.chunkId()))
                    .append("; section=").append(value(chunk.sectionPath()))
                    .append(" ---\n").append(value(chunk.text()));
        }
        return builder.toString();
    }

    private void failClassification(UUID batchId, SourceDocument document, String error) {
        for (EvidenceProfile profile : profileRegistry.all()) {
            repository.upsertMatch(batchId, document.documentId(), new ClassifiedQuestion(
                    profile.questionId(), ClassificationStatus.FAILED, 0, error, List.of()));
        }
    }

    private String retryMessage(String base, Exception error) {
        if (error == null) {
            return base;
        }
        return base + "\n\nPrevious output failed validation: " + message(error)
                + """

                Return the complete corrected JSON object. Rebuild the failing row rather than
                repeating it. Every cells array must have exactly the same number of items as the
                supplied headers. Copy every exactQuote as one continuous, character-for-character
                passage from the cited chunk; do not paraphrase, join passages, or repair its text.
                Delete any row that cannot satisfy these rules. Returning {"rows":[]} is valid.
                """;
    }

    private Path batchRoot(UUID batchId) {
        return Path.of(properties.getOutputRoot()).toAbsolutePath().normalize()
                .resolve("multi-profile").resolve(batchId.toString());
    }

    private Path documentOutputPath(UUID batchId, UUID documentId, String questionId) {
        return batchRoot(batchId).resolve("papers")
                .resolve(documentId.toString()).resolve(questionId + ".md");
    }

    private void writeAtomically(Path outputPath, String content) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Path temporary = Files.createTempFile(outputPath.getParent(), "evidence-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, outputPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String classificationConfigHash() {
        return sha256(
                PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_CLASSIFICATION_SYSTEM)
                        + "\n" + profileRegistry.all());
    }

    private String extractionConfigHash() {
        return sha256(
                PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_EXTRACTION_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_Q1_PROMPT_ONLY_MARKDOWN_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_VERIFY_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_COVERAGE_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_RETRIEVAL_SYSTEM)
                        + "\n" + profileRegistry.all()
                        + "\nagents="
                        + "constrained=" + properties.getAgents().getConstrainedDecoding().isEnabled()
                        + ";q1PromptOnlyMarkdown="
                        + properties.getQ1().getPromptOnlyMarkdown().isEnabled()
                        + ";verifier=" + properties.getAgents().getVerifier().isEnabled()
                        + ";coverage=" + properties.getAgents().getCoverage().isEnabled()
                        + ";retriever=" + properties.getAgents().getRetriever().isOnDemandEnabled()
                        + ";reconciler=" + properties.getAgents().getReconciler().isEntityLinkingEnabled()
                        + ";table=" + properties.getTable().isEnabled()
                        + ":enabledQuestionIds=" + properties.getTable().getEnabledQuestionIds()
                        + ":llmSelect=" + properties.getTable().isLlmSelect()
                        + ":maxTables=" + properties.getTable().getMaxTables());
    }

    /** Combined hash retained in {@code prompt_hash} for pre-V31 tooling compatibility. */
    private String legacyPromptHash(String classificationConfigHash, String extractionConfigHash) {
        return sha256(classificationConfigHash + "\n" + extractionConfigHash);
    }

    /** Exposed for stage-4 runs that hash a resolved {@link EvidenceProperties} snapshot. */
    public String hashExtractionConfig(String configSnapshotJson) {
        return sha256(
                PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_EXTRACTION_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_Q1_PROMPT_ONLY_MARKDOWN_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_VERIFY_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_COVERAGE_SYSTEM)
                        + "\n"
                        + PromptResources.load(PromptCatalog.EVIDENCE_MULTI_PROFILE_RETRIEVAL_SYSTEM)
                        + "\n" + profileRegistry.all()
                        + "\n" + configSnapshotJson);
    }

    public void writeEvidenceMarkdown(Path outputPath, String questionId,
                                      List<ValidatedEvidenceRow> rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            Files.deleteIfExists(outputPath);
            return;
        }
        EvidenceProfile profile = profileRegistry.require(questionId);
        writeAtomically(outputPath, outputValidator.renderMarkdown(profile, rows));
    }

    private String sourceHash(List<SourceDocument> documents) {
        return sha256(documents.stream()
                .map(document -> document.documentId().toString())
                .reduce((left, right) -> left + "\n" + right).orElse(""));
    }

    private SourceSelection resolveSource(BatchRequest request) {
        if (request != null && request.cohortId() != null) {
            List<UUID> ids = cohortService.findDocumentIds(request.cohortId());
            return new SourceSelection(
                    ClassificationSourceType.COHORT,
                    null,
                    null,
                    repository.findDocumentsByIds(ids));
        }
        if (request != null && request.pretreatmentRunId() != null) {
            return new SourceSelection(
                    ClassificationSourceType.PRETREATMENT,
                    null,
                    request.pretreatmentRunId(),
                    repository.findSourceDocumentsFromPretreatment(request.pretreatmentRunId()));
        }
        if (request == null || request.sourceExperimentId() == null) {
            UUID pretreatmentRunId = repository.findLatestAcceptedPretreatmentRun().orElse(null);
            if (pretreatmentRunId != null) {
                List<SourceDocument> documents =
                        repository.findSourceDocumentsFromPretreatment(pretreatmentRunId);
                if (!documents.isEmpty()) {
                    return new SourceSelection(
                            ClassificationSourceType.PRETREATMENT,
                            null,
                            pretreatmentRunId,
                            documents);
                }
            }
        }
        UUID sourceExperimentId = request == null || request.sourceExperimentId() == null
                ? MultiProfileEvidenceModels.DEFAULT_SOURCE_EXPERIMENT_ID
                : request.sourceExperimentId();
        return new SourceSelection(
                ClassificationSourceType.EXPERIMENT,
                sourceExperimentId,
                null,
                repository.findSourceDocuments(sourceExperimentId));
    }

    private List<SourceDocument> reloadSourceDocuments(BatchRecord batch) {
        return switch (batch.sourceType()) {
            case PRETREATMENT -> repository.findSourceDocumentsFromPretreatment(
                    batch.sourcePretreatmentRunId());
            case COHORT, EXPERIMENT -> {
                if (batch.sourceExperimentId() == null) {
                    yield List.of();
                }
                yield repository.findSourceDocuments(batch.sourceExperimentId());
            }
        };
    }

    private void publishClassificationCohorts(UUID batchId) {
        for (EvidenceProfile profile : profileRegistry.all()) {
            List<UUID> supported = repository.findMatchesForQuestion(
                            batchId, profile.questionId(), List.of(ClassificationStatus.SUPPORTED))
                    .stream()
                    .map(QuestionMatchRecord::documentId)
                    .toList();
            cohortService.create(
                    profile.questionId() + "-supported-" + batchId,
                    "CLASSIFICATION",
                    batchId,
                    supported,
                    profile.questionId() + " supported by classification");
        }
    }

    private record SourceSelection(
            ClassificationSourceType sourceType,
            UUID sourceExperimentId,
            UUID sourcePretreatmentRunId,
            List<SourceDocument> documents
    ) {
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String responseText(ChatResponse response) {
        if (response == null || response.aiMessage() == null
                || response.aiMessage().text() == null) {
            throw new IllegalArgumentException("Model returned no text");
        }
        return response.aiMessage().text().trim();
    }

    private String modelName() {
        return modelProperties == null || modelProperties.getChatModel() == null
                ? null : modelProperties.getChatModel().getModelName();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize prompt JSON", e);
        }
    }

    private String chunkKey(EvidenceChunk chunk) {
        return value(chunk.chunkId()) + "\u001f" + value(chunk.text());
    }

    private String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String value = error.getMessage();
        return value == null || value.isBlank()
                ? error.getClass().getSimpleName() : value;
    }

    private String value(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
