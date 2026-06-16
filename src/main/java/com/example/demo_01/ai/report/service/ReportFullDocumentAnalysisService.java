package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.report.config.ReportProperties;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureClaim;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureProfile;
import com.example.demo_01.ai.report.model.ReportModels.ReportDocumentChunk;
import com.example.demo_01.ai.report.repository.ReportLiteratureRepository;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReportFullDocumentAnalysisService {

    @Resource
    private ReportLiteratureRepository literatureRepository;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private DashScopeModelProperties modelProperties;

    @Resource
    private ReportProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public AnalysisOutcome analyze(RagDocumentRecord document) {
        List<ReportDocumentChunk> chunks = literatureRepository.findDocumentChunks(document.documentId());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("document has no full-text chunks");
        }
        String prompt = PromptResources.load(PromptCatalog.REPORT_FULL_DOCUMENT_BATCH_SYSTEM);
        String promptHash = sha256(prompt);
        String modelName = modelProperties.getChatModel().getModelName();
        if (modelName == null || modelName.isBlank()) {
            modelName = "unknown-chat-model";
        }
        String documentHash = documentHash(document, chunks);
        var cached = literatureRepository.findCachedProfile(
                document.documentId(), documentHash, promptHash, modelName);
        if (cached.isPresent()) {
            return new AnalysisOutcome(cached.get(), true, chunks.size());
        }

        List<BatchExtraction> batches = new ArrayList<>();
        for (List<ReportDocumentChunk> window : windows(chunks)) {
            batches.add(analyzeWindow(document, window, prompt));
        }
        LiteratureProfile profile = merge(document, documentHash, batches);
        literatureRepository.saveCachedProfile(profile, promptHash, modelName, chunks.size());
        return new AnalysisOutcome(profile, false, chunks.size());
    }

    List<List<ReportDocumentChunk>> windows(List<ReportDocumentChunk> chunks) {
        List<ReportDocumentChunk> ordered = chunks.stream()
                .filter(chunk -> chunk.text() != null && !chunk.text().isBlank())
                .sorted(Comparator.comparingInt(ReportDocumentChunk::chunkIndex)
                        .thenComparing(ReportDocumentChunk::chunkId))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        int maxChunks = Math.max(1, properties.getChunksPerAnalysisBatch());
        int maxChars = Math.max(1000, properties.getMaxCharsPerAnalysisBatch());
        int overlap = Math.min(Math.max(0, properties.getAnalysisBatchOverlap()), maxChunks - 1);
        List<List<ReportDocumentChunk>> windows = new ArrayList<>();
        int start = 0;
        while (start < ordered.size()) {
            int end = start;
            int chars = 0;
            while (end < ordered.size() && end - start < maxChunks) {
                int nextChars = ordered.get(end).text().length();
                if (end > start && chars + nextChars > maxChars) {
                    break;
                }
                chars += nextChars;
                end++;
            }
            if (end == start) {
                end++;
            }
            windows.add(List.copyOf(ordered.subList(start, end)));
            if (end >= ordered.size()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return List.copyOf(windows);
    }

    private BatchExtraction analyzeWindow(RagDocumentRecord document,
                                          List<ReportDocumentChunk> chunks,
                                          String prompt) {
        String input = buildWindowInput(document, chunks);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= Math.max(1, properties.getMaxDocumentAnalysisAttempts()); attempt++) {
            try {
                String text = chatClient.chatStandard(
                        SystemMessage.from(prompt),
                        UserMessage.from(input)
                ).aiMessage().text();
                BatchExtraction extraction = objectMapper.readValue(cleanJson(text), BatchExtraction.class);
                validateChunkIds(extraction, chunks);
                return extraction;
            } catch (RuntimeException | JsonProcessingException e) {
                lastError = new IllegalStateException(
                        "full-text batch analysis attempt " + attempt + " failed: " + e.getMessage(), e);
            }
        }
        throw lastError == null
                ? new IllegalStateException("full-text batch analysis failed")
                : lastError;
    }

    private String buildWindowInput(RagDocumentRecord document, List<ReportDocumentChunk> chunks) {
        StringBuilder builder = new StringBuilder()
                .append("文献ID：").append(document.documentId()).append('\n')
                .append("标题：").append(document.title() == null ? "" : document.title()).append('\n')
                .append("连续全文chunks：\n");
        for (ReportDocumentChunk chunk : chunks) {
            builder.append("\n--- chunkId=").append(chunk.chunkId())
                    .append(", chunkIndex=").append(chunk.chunkIndex())
                    .append(", section=").append(chunk.sectionPath())
                    .append(" ---\n")
                    .append(chunk.text()).append('\n');
        }
        return builder.toString();
    }

    private void validateChunkIds(BatchExtraction extraction, List<ReportDocumentChunk> chunks) {
        Set<String> allowed = chunks.stream()
                .map(ReportDocumentChunk::chunkId)
                .collect(java.util.stream.Collectors.toSet());
        for (LiteratureClaim claim : extraction.allClaims()) {
            if (claim.statement() == null || claim.statement().isBlank() || claim.chunkIds().isEmpty()) {
                throw new IllegalStateException("literature claim is missing statement or chunkIds");
            }
            if (!allowed.containsAll(claim.chunkIds())) {
                throw new IllegalStateException("literature claim cited a chunk outside the current batch");
            }
        }
    }

    private LiteratureProfile merge(RagDocumentRecord document,
                                    String documentHash,
                                    List<BatchExtraction> batches) {
        return new LiteratureProfile(
                document.documentId(),
                displayTitle(document),
                documentHash,
                mergeClaims(batches.stream().flatMap(batch -> batch.background().stream()).toList()),
                mergeClaims(batches.stream().flatMap(batch -> batch.compounds().stream()).toList()),
                mergeClaims(batches.stream().flatMap(batch -> batch.activity().stream()).toList()),
                mergeClaims(batches.stream().flatMap(batch -> batch.mechanisms().stream()).toList()),
                mergeClaims(batches.stream().flatMap(batch -> batch.applications().stream()).toList()),
                mergeClaims(batches.stream().flatMap(batch -> batch.safetyAndResistance().stream()).toList()),
                mergeClaims(batches.stream().flatMap(batch -> batch.conclusions().stream()).toList()),
                mergeClaims(batches.stream().flatMap(batch -> batch.limitations().stream()).toList())
        );
    }

    private List<LiteratureClaim> mergeClaims(List<LiteratureClaim> claims) {
        Map<String, LiteratureClaim> merged = new LinkedHashMap<>();
        for (LiteratureClaim claim : claims) {
            String key = normalize(claim.category()) + "|" + normalize(claim.statement());
            LiteratureClaim existing = merged.get(key);
            if (existing == null) {
                merged.put(key, claim);
                continue;
            }
            Set<String> chunkIds = new HashSet<>(existing.chunkIds());
            chunkIds.addAll(claim.chunkIds());
            merged.put(key, new LiteratureClaim(
                    existing.category(), existing.statement(), List.copyOf(chunkIds)));
        }
        return List.copyOf(merged.values());
    }

    private String documentHash(RagDocumentRecord document, List<ReportDocumentChunk> chunks) {
        StringBuilder content = new StringBuilder()
                .append(document.pdfSha256() == null ? "" : document.pdfSha256())
                .append('\n');
        chunks.forEach(chunk -> content.append(chunk.chunkId()).append('\n').append(chunk.text()).append('\n'));
        return sha256(content.toString());
    }

    private String displayTitle(RagDocumentRecord document) {
        if (document.title() != null && !document.title().isBlank()) {
            return document.title();
        }
        if (document.sourceFilename() != null && !document.sourceFilename().isBlank()) {
            return document.sourceFilename().replaceFirst("(?i)\\.pdf$", "");
        }
        return "未命名内部文献";
    }

    private String cleanJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("model returned empty full-text analysis");
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record AnalysisOutcome(LiteratureProfile profile, boolean cached, int chunkCount) {
    }

    public record BatchExtraction(
            List<LiteratureClaim> background,
            List<LiteratureClaim> compounds,
            List<LiteratureClaim> activity,
            List<LiteratureClaim> mechanisms,
            List<LiteratureClaim> applications,
            List<LiteratureClaim> safetyAndResistance,
            List<LiteratureClaim> conclusions,
            List<LiteratureClaim> limitations
    ) {
        public BatchExtraction {
            background = safe(background);
            compounds = safe(compounds);
            activity = safe(activity);
            mechanisms = safe(mechanisms);
            applications = safe(applications);
            safetyAndResistance = safe(safetyAndResistance);
            conclusions = safe(conclusions);
            limitations = safe(limitations);
        }

        private static List<LiteratureClaim> safe(List<LiteratureClaim> value) {
            return value == null ? List.of() : List.copyOf(value);
        }

        List<LiteratureClaim> allClaims() {
            List<LiteratureClaim> claims = new ArrayList<>();
            claims.addAll(background);
            claims.addAll(compounds);
            claims.addAll(activity);
            claims.addAll(mechanisms);
            claims.addAll(applications);
            claims.addAll(safetyAndResistance);
            claims.addAll(conclusions);
            claims.addAll(limitations);
            return List.copyOf(claims);
        }
    }
}
