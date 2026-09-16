package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunAccepted;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunRequest;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionSourceType;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionService;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.CandidateEnumeration;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.ExistingRagDocument;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoDocumentPage;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunAccepted;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRequest;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTextLayerStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.StructuredArtifactResult;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.TextLayerReport;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagJobStatus;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.service.RagIngestionFromArtifactService;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import com.example.demo_01.ai.stage.CohortService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PatentDemoPipelineService {

    private static final String DEFAULT_MANIFEST_PATH =
            "D:\\\\\u8bfe\u9898\\\\\u6587\u732e\u4e0b\u8f7d\\\\patents_merged_20260720\\\\patent_classified_simple.csv";
    private static final String DEFAULT_PDF_ROOT =
            "D:\\\\\u8bfe\u9898\\\\\u6587\u732e\u4e0b\u8f7d\\\\patents_merged_20260720\\\\pdf";
    private static final String DEFAULT_CATEGORY =
            "\u5316\u5b66\u519c\u836f/\u6740\u83cc\u5242/\u6d3b\u6027\u5316\u5408\u7269";
    private static final int DEFAULT_LIMIT = 50;
    private static final long DEFAULT_SEED = 20_260_902L;

    private final TaskExecutor taskExecutor;
    private final PatentCandidateEnumerator candidateEnumerator;
    private final PatentTextLayerDetector textLayerDetector;
    private final PatentPageTriageService pageTriageService;
    private final PatentStructuredArtifactService artifactService;
    private final PatentDemoRepository repository;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagIngestionFromArtifactService ragIngestionFromArtifactService;
    private final RagVectorIngestionService ragVectorIngestionService;
    private final CohortService cohortService;
    private final QuestionExtractionService questionExtractionService;
    private final ObjectMapper objectMapper;

    public PatentDemoPipelineService(@Qualifier("ragTaskExecutor") TaskExecutor taskExecutor,
                                     PatentCandidateEnumerator candidateEnumerator,
                                     PatentTextLayerDetector textLayerDetector,
                                     PatentPageTriageService pageTriageService,
                                     PatentStructuredArtifactService artifactService,
                                     PatentDemoRepository repository,
                                     RagDocumentRepository ragDocumentRepository,
                                     RagIngestionFromArtifactService ragIngestionFromArtifactService,
                                     RagVectorIngestionService ragVectorIngestionService,
                                     CohortService cohortService,
                                     QuestionExtractionService questionExtractionService,
                                     ObjectMapper objectMapper) {
        this.taskExecutor = taskExecutor;
        this.candidateEnumerator = candidateEnumerator;
        this.textLayerDetector = textLayerDetector;
        this.pageTriageService = pageTriageService;
        this.artifactService = artifactService;
        this.repository = repository;
        this.ragDocumentRepository = ragDocumentRepository;
        this.ragIngestionFromArtifactService = ragIngestionFromArtifactService;
        this.ragVectorIngestionService = ragVectorIngestionService;
        this.cohortService = cohortService;
        this.questionExtractionService = questionExtractionService;
        this.objectMapper = objectMapper;
    }

    public PatentDemoRunAccepted submit(PatentDemoRunRequest request) {
        ResolvedRequest resolved = resolve(request);
        UUID runId = UUID.randomUUID();
        repository.insertRun(
                runId,
                resolved.manifestPath().toString(),
                resolved.pdfRoot().toString(),
                resolved.category(),
                resolved.limit(),
                resolved.seed(),
                resolved.runExtraction(),
                resolved.force(),
                resolved.publicationNumbers(),
                resolved.strategyVersion(),
                resolved.discoveryMode(),
                resolved.subjectMode());
        taskExecutor.execute(() -> run(runId, resolved));
        return new PatentDemoRunAccepted(runId, PatentDemoRunStatus.QUEUED, resolved.limit());
    }

    public PatentDemoRunRecord requireRun(UUID runId) {
        return repository.findRun(runId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Patent demo run not found: " + runId));
    }

    public PatentDemoDocumentPage findDocuments(UUID runId, int page, int size) {
        requireRun(runId);
        return repository.findDocuments(runId, page, size);
    }

    private void run(UUID runId, ResolvedRequest request) {
        RunCounters counters = new RunCounters();
        List<UUID> ingestedDocumentIds = new ArrayList<>();
        UUID cohortId = null;
        UUID extractionRunId = null;
        String runError = null;
        repository.markRunRunning(runId);
        try {
            CandidateEnumeration enumeration = candidateEnumerator.enumerate(
                    request.manifestPath(), request.pdfRoot(), request.category(),
                    request.limit(), request.seed(), request.publicationNumbers());
            counters.totalCandidates = enumeration.totalCandidates();
            updateProgress(runId, counters, null, null, null);

            for (PatentCandidate candidate : enumeration.selectedCandidates()) {
                processCandidate(runId, request, candidate, counters, ingestedDocumentIds);
                updateProgress(runId, counters, null, null, null);
            }

            if (!ingestedDocumentIds.isEmpty()) {
                cohortId = cohortService.create(
                        "patent-demo-q1-" + runId,
                        "PATENT_DEMO",
                        runId,
                        ingestedDocumentIds,
                        "patent demo q1 selected text-layer patents");
                if (request.runExtraction()) {
                    try {
                        ExtractionRunAccepted accepted = questionExtractionService.submit(
                                extractionRequest(runId, cohortId, request.force()));
                        extractionRunId = accepted.runId();
                    } catch (RuntimeException e) {
                        runError = "Q1 extraction submission failed: " + rootMessage(e);
                    }
                }
            }
            updateProgress(runId, counters, cohortId, extractionRunId, null);
            repository.finishRun(runId, finishStatus(counters, runError), runError);
        } catch (RuntimeException e) {
            repository.finishRun(runId, PatentDemoRunStatus.FAILED, rootMessage(e));
        }
    }

    private void processCandidate(UUID runId,
                                  ResolvedRequest request,
                                  PatentCandidate candidate,
                                  RunCounters counters,
                                  List<UUID> ingestedDocumentIds) {
        repository.insertDocument(runId, candidate);
        repository.markDocumentRunning(runId, candidate.publicationNumber());
        UUID documentId = null;
        boolean documentExists = false;
        boolean documentChanged = false;
        int selectedPageCount = 0;
        int chunkCount = 0;
        String failureStage = "PATENT_PROCESSING_FAILED";
        try {
            TextLayerReport textLayer = textLayerDetector.inspect(candidate.pdfPath());
            repository.updateTextLayer(runId, candidate.publicationNumber(), textLayer);
            if (textLayer.status() != PatentTextLayerStatus.TEXT_LAYER_USABLE) {
                counters.skippedDocuments++;
                repository.markDocumentSkipped(runId, candidate.publicationNumber(),
                        textLayer.status().name(), 0);
                return;
            }
            counters.textLayerPassed++;

            PatentTriageResult triage = pageTriageService.triage(candidate.pdfPath());
            repository.replacePages(runId, candidate.publicationNumber(), triage.pages());
            if (triage.selectedPages().isEmpty()) {
                counters.skippedDocuments++;
                repository.markDocumentSkipped(runId, candidate.publicationNumber(),
                        "NO_Q1_RELEVANT_PAGES", 0);
                return;
            }
            if (!request.v3()
                    && triage.selectedPages().stream().noneMatch(page -> page.roles().contains(
                    PatentDemoModels.PatentPageRole.Q1_ACTIVITY))) {
                counters.skippedDocuments++;
                repository.markDocumentSkipped(runId, candidate.publicationNumber(),
                        "NO_Q1_ASSAY_EVIDENCE", 0);
                return;
            }

            selectedPageCount = triage.selectedPages().size();
            Optional<ExistingRagDocument> existing = existingDocument(candidate);
            documentExists = existing.isPresent();
            documentId = existing.map(ExistingRagDocument::documentId).orElseGet(UUID::randomUUID);
            if (!request.force() && existing.isPresent()
                    && existing.get().status() == RagDocumentStatus.COMPLETED
                    && hasVectorChunks(documentId)) {
                repository.markDocumentIngested(runId, candidate.publicationNumber(),
                        documentId, triage.selectedPages().size(),
                        Math.toIntExact(repository.countVectorChunks(documentId)));
                ingestedDocumentIds.add(documentId);
                counters.ingestedDocuments++;
                return;
            }

            failureStage = "ARTIFACT_WRITE_FAILED";
            StructuredArtifactResult artifact = artifactService.write(runId, documentId, candidate, triage);
            chunkCount = artifact.chunkCount();
            if (request.v3() && artifact.discoveryOutcome()
                    == PatentDemoModels.PatentDiscoveryOutcome.NO_EVIDENCE_FOUND) {
                counters.skippedDocuments++;
                repository.markDocumentSkipped(runId, candidate.publicationNumber(),
                        "NO_Q1_ASSAY_EVIDENCE", selectedPageCount);
                return;
            }
            if (request.v3() && artifact.discoveryOutcome()
                    == PatentDemoModels.PatentDiscoveryOutcome.UNRESOLVED) {
                counters.skippedDocuments++;
                repository.markDocumentSkipped(runId, candidate.publicationNumber(),
                        "Q1_DISCOVERY_UNRESOLVED", selectedPageCount);
                return;
            }
            if (existing.isPresent() && existing.get().storageRoot() != null
                    && Path.of(existing.get().storageRoot()).toAbsolutePath().normalize()
                    .equals(artifact.storageDir().toAbsolutePath().normalize())) {
                throw new IllegalStateException("Replacement artifact must use a new storage directory");
            }
            failureStage = existing.isPresent() ? "RAG_REBUILD_FAILED" : "RAG_INGESTION_FAILED";
            // The complete replacement artifact must exist before changing the live document or indexes.
            if (existing.isPresent()) {
                documentChanged = true;
                repository.updateRagDocumentStorageRoot(documentId, artifact.storageDir().toString());
            } else {
                ragDocumentRepository.insertInitial(
                        documentId,
                        candidate.pdfPath().getFileName().toString(),
                        artifact.storageDir().toString(),
                        RagDocumentStatus.QUEUED);
                documentExists = true;
                documentChanged = true;
            }
            ragDocumentRepository.markProcessing(
                    documentId, artifact.metadata(), artifact.pdfSha256(), artifact.canonicalKey());
            ragDocumentRepository.updatePreprocessState(documentId, PreprocessStatus.COMPLETED);

            if (existing.isPresent()) {
                ragVectorIngestionService.removeDocument(documentId);
            }
            var ingestion = ragIngestionFromArtifactService.ingestDocument(documentId, null);
            if (ingestion.status() != RagJobStatus.COMPLETED) {
                throw new IllegalStateException("RAG_INGESTION_" + ingestion.status());
            }
            ragDocumentRepository.markCompleted(
                    documentId, artifact.metadata(), artifact.pdfSha256(), artifact.canonicalKey());
            repository.markDocumentIngested(runId, candidate.publicationNumber(),
                    documentId, triage.selectedPages().size(), artifact.chunkCount());
            ingestedDocumentIds.add(documentId);
            counters.ingestedDocuments++;
        } catch (RuntimeException | java.io.IOException e) {
            counters.failedDocuments++;
            String error = failureStage + ": " + rootMessage(e);
            if (documentChanged) {
                try {
                    ragDocumentRepository.markFailed(documentId);
                } catch (RuntimeException stateError) {
                    error += "; failed to mark RAG document FAILED: " + rootMessage(stateError);
                }
            }
            if (documentExists) {
                repository.markDocumentFailed(runId, candidate.publicationNumber(),
                        documentId, selectedPageCount, chunkCount, error);
            } else {
                repository.markDocumentFailed(runId, candidate.publicationNumber(), error);
            }
        }
    }

    private Optional<ExistingRagDocument> existingDocument(PatentCandidate candidate) {
        return repository.findCanonicalRagDocumentByPublicationNumber(candidate.publicationNumber());
    }

    private boolean hasVectorChunks(UUID documentId) {
        try {
            return repository.countVectorChunks(documentId) > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ExtractionRunRequest extractionRequest(UUID runId, UUID cohortId, boolean force) {
        return new ExtractionRunRequest(
                "Q1",
                "patent-demo-q1-" + runId,
                ExtractionSourceType.COHORT,
                null,
                null,
                cohortId,
                null,
                null,
                extractionOverrides(),
                force
        );
    }

    private ObjectNode extractionOverrides() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("chunkBatchSize", 8);
        root.put("maxSinglePassChunks", 40);
        root.put("maxSinglePassChars", 80_000);
        root.putObject("table").put("enabled", true);

        ObjectNode agents = root.putObject("agents");
        agents.putObject("retriever").put("onDemandEnabled", false);
        ObjectNode verifier = agents.putObject("verifier");
        verifier.put("enabled", true);
        verifier.put("dropInvalidRows", false);
        agents.putObject("coverage").put("enabled", false);
        ObjectNode reconciler = agents.putObject("reconciler");
        reconciler.put("entityLinkingEnabled", false);
        reconciler.put("enqueueUnknownAsCandidates", false);
        return root;
    }

    private void updateProgress(UUID runId,
                                RunCounters counters,
                                UUID cohortId,
                                UUID extractionRunId,
                                Integer evidenceRows) {
        repository.updateRunProgress(
                runId,
                counters.totalCandidates,
                counters.textLayerPassed,
                counters.ingestedDocuments,
                counters.skippedDocuments,
                counters.failedDocuments,
                cohortId,
                extractionRunId,
                evidenceRows);
    }

    private PatentDemoRunStatus finishStatus(RunCounters counters, String runError) {
        if (runError != null && !runError.isBlank()) {
            return counters.ingestedDocuments > 0
                    ? PatentDemoRunStatus.PARTIAL_FAILED
                    : PatentDemoRunStatus.FAILED;
        }
        if (counters.failedDocuments > 0) {
            return counters.ingestedDocuments > 0
                    ? PatentDemoRunStatus.PARTIAL_FAILED
                    : PatentDemoRunStatus.FAILED;
        }
        return PatentDemoRunStatus.COMPLETED;
    }

    private ResolvedRequest resolve(PatentDemoRunRequest request) {
        String manifestPath = firstNonBlank(request == null ? null : request.manifestPath(),
                DEFAULT_MANIFEST_PATH);
        String pdfRoot = firstNonBlank(request == null ? null : request.pdfRoot(), DEFAULT_PDF_ROOT);
        String category = firstNonBlank(request == null ? null : request.category(), DEFAULT_CATEGORY);
        int limit = request == null || request.limit() == null || request.limit() <= 0
                ? DEFAULT_LIMIT
                : request.limit();
        Long seed = request == null || request.seed() == null ? DEFAULT_SEED : request.seed();
        boolean runExtraction = request == null || request.runExtraction() == null || request.runExtraction();
        boolean force = request != null && request.force() != null && request.force();
        List<String> publicationNumbers = request == null || request.publicationNumbers() == null
                ? List.of() : request.publicationNumbers();
        if (publicationNumbers.stream().anyMatch(number -> number == null || number.isBlank())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "publicationNumbers must contain nonblank values");
        }
        publicationNumbers = publicationNumbers.stream().map(String::strip).distinct().toList();
        String strategyVersion = firstNonBlank(request == null ? null : request.strategyVersion(), "legacy");
        String discoveryMode = firstNonBlank(request == null ? null : request.discoveryMode(), "balanced");
        String subjectMode = firstNonBlank(request == null ? null : request.subjectMode(), "rules");
        return new ResolvedRequest(
                Path.of(manifestPath).toAbsolutePath().normalize(),
                Path.of(pdfRoot).toAbsolutePath().normalize(),
                category,
                limit,
                seed,
                runExtraction,
                force,
                publicationNumbers,
                strategyVersion,
                discoveryMode,
                subjectMode);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String rootMessage(Exception e) {
        Throwable cursor = e;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record ResolvedRequest(
            Path manifestPath,
            Path pdfRoot,
            String category,
            int limit,
            Long seed,
            boolean runExtraction,
            boolean force,
            List<String> publicationNumbers,
            String strategyVersion,
            String discoveryMode,
            String subjectMode
    ) {
        private boolean v3() {
            return "v3".equalsIgnoreCase(strategyVersion);
        }
    }

    private static final class RunCounters {
        private int totalCandidates;
        private int textLayerPassed;
        private int ingestedDocuments;
        private int skippedDocuments;
        private int failedDocuments;
    }
}
