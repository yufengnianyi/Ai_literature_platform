package com.example.demo_01.ai.evidence.service;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.*;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class EvidenceExtractionService {

    private static final String EVIDENCE_TYPE_DIRECTORY = "抑菌化合物";
    private static final String OUTPUT_FILENAME = "抑菌化合物信息表.md";

    @Resource
    private EvidenceRepository evidenceRepository;

    @Resource
    private RagDocumentRepository documentRepository;

    @Resource
    private MarkdownEvidenceTableParser tableParser;

    @Resource
    private CompoundNormalizationService normalizationService;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private EvidenceProperties properties;

    @Resource
    private DashScopeModelProperties modelProperties;

    @Resource(name = "evidenceTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource(name = "evidenceBatchTaskExecutor")
    private TaskExecutor batchTaskExecutor;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public ExtractionAcceptedResponse enqueue(UUID documentId) {
        return enqueue(documentId, false);
    }

    public ExtractionAcceptedResponse enqueue(UUID documentId, boolean force) {
        ensureEnabled();
        RagDocumentRecord document = canonicalCompletedDocument(documentId);
        OptionalRun active = activeRun(documentId);
        if (active != null) {
            return new ExtractionAcceptedResponse(
                    active.run().runId(), documentId, active.run().status(), active.run().skipped());
        }

        String promptHash = promptHash();
        if (!force) {
            ExtractionRunRecord fresh = freshRun(document, promptHash);
            if (fresh != null) {
                UUID runId = evidenceRepository.insertSkippedRun(null, fresh);
                return new ExtractionAcceptedResponse(runId, documentId, fresh.status(), true);
            }
        }

        UUID runId = evidenceRepository.insertRun(
                null, documentId, document.pdfSha256(), promptHash, modelName());
        taskExecutor.execute(() -> extract(runId, document));
        return new ExtractionAcceptedResponse(runId, documentId, ExtractionStatus.QUEUED, false);
    }

    public EvidenceBackfillResponse backfill(boolean force) {
        ensureEnabled();
        ExtractionBatchRecord active = evidenceRepository.findActiveBatch().orElse(null);
        if (active != null) {
            return new EvidenceBackfillResponse(
                    active.batchId(), active.status(), active.totalDocuments());
        }

        List<RagDocumentRecord> documents = documentRepository.findAllCanonicalCompleted();
        UUID batchId = UUID.randomUUID();
        evidenceRepository.insertBatch(batchId, force, documents.size());
        batchTaskExecutor.execute(() -> runBatch(batchId, documents, force));
        return new EvidenceBackfillResponse(batchId, BatchStatus.QUEUED, documents.size());
    }

    private void runBatch(UUID batchId, List<RagDocumentRecord> documents, boolean force) {
        evidenceRepository.markBatchRunning(batchId);
        String promptHash = promptHash();
        try {
            for (RagDocumentRecord document : documents) {
                processBatchDocument(batchId, document, promptHash, force);
                evidenceRepository.refreshBatchCounts(batchId);
            }
            evidenceRepository.finishBatch(batchId);
        } catch (Exception e) {
            evidenceRepository.failBatch(batchId);
            log.error("Evidence extraction batch {} failed: {}", batchId, e.getMessage(), e);
        }
    }

    private void processBatchDocument(UUID batchId,
                                      RagDocumentRecord document,
                                      String promptHash,
                                      boolean force) throws InterruptedException {
        OptionalRun active = activeRun(document.documentId());
        if (active != null) {
            evidenceRepository.attachRunToBatch(active.run().runId(), batchId);
            waitForRun(active.run().runId());
            return;
        }

        if (!force) {
            ExtractionRunRecord fresh = freshRun(document, promptHash);
            if (fresh != null) {
                evidenceRepository.insertSkippedRun(batchId, fresh);
                return;
            }
        }

        UUID runId = evidenceRepository.insertRun(
                batchId, document.documentId(), document.pdfSha256(), promptHash, modelName());
        extract(runId, document);
    }

    private void waitForRun(UUID runId) throws InterruptedException {
        while (true) {
            ExtractionRunRecord run = evidenceRepository.findRun(runId)
                    .orElseThrow(() -> new IllegalStateException("Evidence run disappeared: " + runId));
            if (run.status() != ExtractionStatus.QUEUED && run.status() != ExtractionStatus.RUNNING) {
                return;
            }
            Thread.sleep(250);
        }
    }

    void extract(UUID runId, RagDocumentRecord document) {
        evidenceRepository.markRunRunning(runId);
        try {
            List<EvidenceChunk> chunks = evidenceRepository.findDocumentChunks(document.documentId());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("No chunks found for document " + document.documentId());
            }

            List<CompoundEvidenceRow> rows = extractRows(document, chunks);
            Path outputPath = outputPath(document.documentId());
            if (rows.isEmpty()) {
                Files.deleteIfExists(outputPath);
                evidenceRepository.replaceDocumentEvidence(runId, document.documentId(), List.of());
                evidenceRepository.completeRun(runId, ExtractionStatus.NO_EVIDENCE, 0, null);
            } else {
                writeAtomically(outputPath, tableParser.render(rows));
                List<NormalizedEvidenceRow> normalized = normalizationService.normalize(
                        document.documentId(), rows);
                evidenceRepository.replaceDocumentEvidence(runId, document.documentId(), normalized);
                evidenceRepository.completeRun(
                        runId, ExtractionStatus.COMPLETED, rows.size(), outputPath.toString());
            }
            log.info("Evidence extraction {} completed for document {} with {} rows",
                    runId, document.documentId(), rows.size());
        } catch (Exception e) {
            evidenceRepository.failRun(runId, "EVIDENCE_EXTRACTION_ERROR", e.getMessage());
            log.warn("Evidence extraction {} failed for document {}: {}",
                    runId, document.documentId(), e.getMessage(), e);
        }
    }

    List<CompoundEvidenceRow> extractRows(RagDocumentRecord document, List<EvidenceChunk> chunks) {
        if (shouldUseSinglePass(chunks)) {
            return extractSinglePass(document, chunks);
        }
        return extractBatched(document, chunks);
    }

    private boolean shouldUseSinglePass(List<EvidenceChunk> chunks) {
        if (chunks.size() > properties.getMaxSinglePassChunks()) {
            return false;
        }
        int totalChars = chunks.stream()
                .mapToInt(chunk -> value(chunk.text()).length())
                .sum();
        return totalChars <= properties.getMaxSinglePassChars();
    }

    private List<CompoundEvidenceRow> extractSinglePass(
            RagDocumentRecord document,
            List<EvidenceChunk> chunks) {
        List<EvidenceChunk> modelChunks = mergeChunks(contextChunks(chunks), chunks);
        String table = callTableModel(document, modelChunks);
        return tableParser.parse(table).rows();
    }

    private List<CompoundEvidenceRow> extractBatched(RagDocumentRecord document, List<EvidenceChunk> chunks) {
        int batchSize = Math.max(1, properties.getChunkBatchSize());
        List<EvidenceChunk> sharedContext = contextChunks(chunks);
        Map<String, CompoundEvidenceRow> uniqueRows = new LinkedHashMap<>();

        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            int adjacentStart = Math.max(0, start - 1);
            int adjacentEnd = Math.min(chunks.size(), end + 1);
            List<EvidenceChunk> modelChunks = mergeChunks(
                    sharedContext, chunks.subList(adjacentStart, adjacentEnd));
            String table = callTableModel(document, modelChunks);
            for (CompoundEvidenceRow row : tableParser.parse(table).rows()) {
                uniqueRows.putIfAbsent(tableParser.fingerprint(row), row);
            }
        }
        return List.copyOf(uniqueRows.values());
    }

    private String callTableModel(RagDocumentRecord document, List<EvidenceChunk> chunks) {
        String systemPrompt = tablePrompt();
        String userMessage = documentInput(document, chunks);
        Exception lastError = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                String raw = responseText(chatClient.chatCore(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userMessage)));
                tableParser.parse(raw);
                return raw;
            } catch (Exception e) {
                lastError = e;
                log.warn("Evidence table attempt {}/{} failed for document {}: {}",
                        attempt, properties.getMaxAttempts(), document.documentId(), e.getMessage());
            }
        }
        throw new IllegalStateException(
                "Evidence table failed validation after " + properties.getMaxAttempts() + " attempts",
                lastError);
    }

    private List<EvidenceChunk> contextChunks(List<EvidenceChunk> chunks) {
        List<EvidenceChunk> context = chunks.stream()
                .filter(chunk -> {
                    String section = value(chunk.sectionPath()).toLowerCase();
                    return section.contains("abstract")
                            || section.contains("摘要")
                            || section.contains("method")
                            || section.contains("材料")
                            || section.contains("方法")
                            || section.contains("result")
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

    private String documentInput(RagDocumentRecord document, List<EvidenceChunk> chunks) {
        return """
                文献元数据：
                - document_id: %s
                - title: %s
                - authors: %s
                - year: %s
                - journal: %s
                - doi: %s

                文献全文 chunks：
                %s
                """.formatted(
                document.documentId(),
                value(document.title()),
                String.join(", ", document.authors() == null ? List.of() : document.authors()),
                document.publicationYear() == null ? "" : document.publicationYear(),
                value(document.journal()),
                value(document.doiNormalized()),
                renderChunks(chunks));
    }

    private String renderChunks(List<EvidenceChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (EvidenceChunk chunk : chunks) {
            builder.append("\n--- chunk_id=").append(value(chunk.chunkId()))
                    .append("; section=").append(value(chunk.sectionPath()))
                    .append(" ---\n")
                    .append(value(chunk.text()));
        }
        return builder.toString();
    }

    private Path outputPath(UUID documentId) {
        return Path.of(properties.getOutputRoot())
                .toAbsolutePath()
                .normalize()
                .resolve(EVIDENCE_TYPE_DIRECTORY)
                .resolve(documentId.toString())
                .resolve(OUTPUT_FILENAME);
    }

    private void writeAtomically(Path outputPath, String markdown) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Path temporary = Files.createTempFile(outputPath.getParent(), "evidence-", ".tmp");
        try {
            Files.writeString(temporary, markdown, StandardCharsets.UTF_8);
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

    private RagDocumentRecord canonicalCompletedDocument(UUID documentId) {
        RagDocumentRecord document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        if (document.status() != com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus.COMPLETED
                || document.duplicateOfDocumentId() != null) {
            throw new IllegalArgumentException(
                    "Evidence extraction requires a completed canonical document: " + documentId);
        }
        return document;
    }

    private OptionalRun activeRun(UUID documentId) {
        return evidenceRepository.findActiveRun(documentId)
                .flatMap(evidenceRepository::findRun)
                .map(OptionalRun::new)
                .orElse(null);
    }

    private ExtractionRunRecord freshRun(RagDocumentRecord document, String promptHash) {
        ExtractionRunRecord run = evidenceRepository.findFreshRun(
                document.documentId(), document.pdfSha256(), promptHash).orElse(null);
        if (run == null) {
            return null;
        }
        if (run.status() == ExtractionStatus.NO_EVIDENCE) {
            return run;
        }
        return run.outputPath() != null && Files.isRegularFile(Path.of(run.outputPath())) ? run : null;
    }

    private String tablePrompt() {
        return PromptResources.load(PromptCatalog.EVIDENCE_ANTIMICROBIAL_COMPOUND_TABLE_SYSTEM);
    }

    private String promptHash() {
        return sha256(tablePrompt());
    }

    private String responseText(ChatResponse response) {
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            throw new IllegalArgumentException("Model returned no text");
        }
        return response.aiMessage().text().trim();
    }

    private String modelName() {
        return modelProperties.getChatModel() == null
                ? null
                : modelProperties.getChatModel().getModelName();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String chunkKey(EvidenceChunk chunk) {
        return value(chunk.chunkId()) + "\u001f" + value(chunk.text());
    }

    private String value(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Evidence extraction is disabled");
        }
    }

    private record OptionalRun(ExtractionRunRecord run) {
    }
}
