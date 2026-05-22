package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PaperEvidenceTableSynthesisService {

    public static final String ANTIMICROBIAL_TEMPLATE_ID = "antimicrobial_compound";
    private static final String NOT_MENTIONED = "\u672a\u63d0\u53ca";
    private static final List<String> ANTIMICROBIAL_HEADERS = List.of(
            "\u5316\u5408\u7269\u540d\u79f0",
            "\u7ed3\u6784\u7c7b\u578b",
            "\u6765\u6e90",
            "\u6291\u83cc\u6d53\u5ea6",
            "\u4f5c\u7528\u75c5\u539f\u83cc",
            "\u8bd5\u9a8c\u65b9\u6cd5",
            "\u53ef\u80fd\u7684\u4f5c\u7528\u9776\u6807/\u673a\u5236",
            "\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u6570\u636e",
            "\u6765\u6e90\u6587\u732e",
            "\u4e13\u5229\u4fe1\u606f"
    );
    private static final List<String> CONCENTRATION_HEADERS = List.of(
            "\u5316\u5408\u7269/\u6807\u7b7e",
            "\u6291\u83cc\u6d53\u5ea6",
            "\u6d53\u5ea6\u7c7b\u578b",
            "\u89c2\u5bdf\u6548\u679c",
            "\u4f5c\u7528\u75c5\u539f\u83cc",
            "\u8bd5\u9a8c\u65b9\u6cd5/\u6761\u4ef6",
            "\u6765\u6e90 chunk ids",
            "\u5907\u6ce8"
    );

    private static final List<String> DEFAULT_HEADERS = List.of(
            "Review question",
            "Paper finding",
            "Evidence / data",
            "Method / context",
            "Interpretation boundary",
            "Source chunks"
    );

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReviewProperties reviewProperties;

    public ReviewPaperEvidenceTable synthesizeBestTable(UUID taskId,
                                                        QueryAnalysis analysis,
                                                        String reviewQuestion,
                                                        UUID documentId,
                                                        String documentTitle,
                                                        List<RetrievedChunk> allChunks,
                                                        List<ExtractedEvidence> extractedEvidence,
                                                        DocumentKnowledgeContext knowledgeContext) {
        return synthesizeBestTable(taskId, analysis, reviewQuestion, documentId, documentTitle, allChunks,
                extractedEvidence, knowledgeContext, ANTIMICROBIAL_TEMPLATE_ID, List.of());
    }

    public ReviewPaperEvidenceTable synthesizeBestTable(UUID taskId,
                                                        QueryAnalysis analysis,
                                                        String reviewQuestion,
                                                        UUID documentId,
                                                        String documentTitle,
                                                        List<RetrievedChunk> allChunks,
                                                        List<ExtractedEvidence> extractedEvidence,
                                                        DocumentKnowledgeContext knowledgeContext,
                                                        String templateId,
                                                        List<RetrievedChunk> selectedSeedChunks) {
        List<RetrievedChunk> safeChunks = allChunks == null ? List.of() : allChunks.stream()
                .filter(Objects::nonNull)
                .toList();
        List<RetrievedChunk> safeSeedChunks = selectedSeedChunks == null ? List.of() : selectedSeedChunks.stream()
                .filter(Objects::nonNull)
                .toList();
        String title = firstNonBlank(documentTitle, safeChunks.stream()
                .map(RetrievedChunk::documentTitle)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));
        int maxIterations = Math.max(1, reviewProperties.getReport().getMaxPaperTableIterations());
        ReviewPaperEvidenceTable best = null;
        String previous = "";
        String resolvedTemplate = firstNonBlank(templateId, ANTIMICROBIAL_TEMPLATE_ID);
        ConcentrationExtraction concentrationExtraction = isAntimicrobialTemplate(resolvedTemplate)
                ? extractConcentration(reviewQuestion, documentId, title, safeChunks)
                : ConcentrationExtraction.empty();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            try {
                ReviewPaperEvidenceTable candidate = callModel(
                        taskId, analysis, reviewQuestion, documentId, title, safeChunks,
                        extractedEvidence, knowledgeContext, previous, iteration, resolvedTemplate,
                        concentrationExtraction, safeSeedChunks);
                if (isBetter(candidate, best)) {
                    best = candidate;
                }
                previous = summarizeCandidate(candidate);
                if (candidate.confidence() >= 0.9 && (candidate.warnings() == null || candidate.warnings().isEmpty())) {
                    break;
                }
            } catch (Exception e) {
                log.warn("Paper evidence table synthesis failed for task {}, document {}, iteration {}: {}",
                        taskId, documentId, iteration, e.getMessage());
            }
        }

        if (best != null) {
            return best;
        }
        return fallbackTable(taskId, reviewQuestion, documentId, title, safeChunks, extractedEvidence, maxIterations,
                resolvedTemplate, concentrationExtraction, safeSeedChunks);
    }

    private ReviewPaperEvidenceTable callModel(UUID taskId,
                                               QueryAnalysis analysis,
                                               String reviewQuestion,
                                               UUID documentId,
                                               String documentTitle,
                                               List<RetrievedChunk> chunks,
                                               List<ExtractedEvidence> extractedEvidence,
                                               DocumentKnowledgeContext knowledgeContext,
                                               String previous,
                                               int iteration,
                                               String templateId,
                                               ConcentrationExtraction concentrationExtraction,
                                               List<RetrievedChunk> selectedSeedChunks) throws JsonProcessingException {
        ConcentrationExtraction safeConcentration = concentrationExtraction == null
                ? ConcentrationExtraction.empty()
                : concentrationExtraction;
        String userMessage = """
                Review question:
                %s

                Sub-questions:
                %s

                Document:
                %s (%s)

                Known document context:
                %s

                Existing extracted evidence from retrieval pipeline:
                %s

                Previous iteration table to improve:
                %s

                Structured concentration summary for the "\u6291\u83cc\u6d53\u5ea6" column:
                %s

                All paper chunks:
                %s
                """.formatted(
                firstNonBlank(reviewQuestion, analysis == null ? null : analysis.mainQuestion()),
                analysis == null || analysis.subQuestions() == null ? "[]" : objectMapper.writeValueAsString(analysis.subQuestions()),
                firstNonBlank(documentTitle, "unknown"),
                documentId,
                objectMapper.writeValueAsString(knowledgeContext),
                objectMapper.writeValueAsString(extractedEvidence == null ? List.of() : extractedEvidence),
                previous == null || previous.isBlank() ? "none" : previous,
                firstNonBlank(safeConcentration.document(), NOT_MENTIONED),
                renderChunks(chunks)
        );

        ChatResponse response = reasoningChatClient.chatCore(
                SystemMessage.from(PromptResources.load(isAntimicrobialTemplate(templateId)
                        ? PromptCatalog.REVIEW_PAPER_EVIDENCE_TABLE_SYNTHESIS_ANTIMICROBIAL_SYSTEM
                        : PromptCatalog.REVIEW_PAPER_EVIDENCE_TABLE_SYNTHESIS_SYSTEM)),
                UserMessage.from(userMessage));
        AiMessage ai = response.aiMessage();
        String raw = ai == null ? null : ai.text();
        PaperTableOutput output = objectMapper.readValue(extractJson(raw), PaperTableOutput.class);

        List<String> headers = normalizeHeaders(output.headers, templateId);
        List<List<String>> rows = normalizeRows(output.rows, headers.size(), templateId);
        List<String> chunkIds = chunks.stream()
                .map(RetrievedChunk::chunkId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> seedChunkIds = selectedSeedChunks == null ? List.of() : selectedSeedChunks.stream()
                .map(RetrievedChunk::chunkId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new ReviewPaperEvidenceTable(
                taskId,
                documentId,
                documentTitle,
                firstNonBlank(reviewQuestion, analysis == null ? null : analysis.mainQuestion()),
                firstNonBlank(output.paperSummary, "No paper-level summary generated."),
                headers,
                rows,
                chunkIds,
                iteration,
                clamp(output.confidence),
                output.warnings == null ? List.of() : output.warnings,
                Instant.now(),
                firstNonBlank(templateId, ANTIMICROBIAL_TEMPLATE_ID),
                safeConcentration.document(),
                safeConcentration.headers(),
                safeConcentration.rows(),
                safeConcentration.summary(),
                seedChunkIds,
                chunkIds
        );
    }

    private ReviewPaperEvidenceTable fallbackTable(UUID taskId,
                                                   String reviewQuestion,
                                                   UUID documentId,
                                                   String documentTitle,
                                                   List<RetrievedChunk> chunks,
                                                   List<ExtractedEvidence> extractedEvidence,
                                                   int iterations,
                                                   String templateId,
                                                   ConcentrationExtraction concentrationExtraction,
                                                   List<RetrievedChunk> selectedSeedChunks) {
        ConcentrationExtraction safeConcentration = concentrationExtraction == null
                ? ConcentrationExtraction.empty()
                : concentrationExtraction;
        if (isAntimicrobialTemplate(templateId)) {
            List<String> chunkIds = chunks.stream()
                    .map(RetrievedChunk::chunkId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            String concentration = firstNonBlank(safeConcentration.summary(), safeConcentration.document(), NOT_MENTIONED);
            List<List<String>> rows = List.of(List.of(
                    NOT_MENTIONED,
                    NOT_MENTIONED,
                    NOT_MENTIONED,
                    concentration,
                    NOT_MENTIONED,
                    NOT_MENTIONED,
                    NOT_MENTIONED,
                    NOT_MENTIONED,
                    firstNonBlank(documentTitle, NOT_MENTIONED),
                    NOT_MENTIONED
            ));
            return new ReviewPaperEvidenceTable(
                    taskId,
                    documentId,
                    documentTitle,
                    reviewQuestion,
                    "Fallback antimicrobial-compound table generated from available chunks.",
                    ANTIMICROBIAL_HEADERS,
                    rows,
                    chunkIds,
                    iterations,
                    0.35,
                    List.of("MODEL_SYNTHESIS_FALLBACK"),
                    Instant.now(),
                    ANTIMICROBIAL_TEMPLATE_ID,
                    safeConcentration.document(),
                    safeConcentration.headers(),
                    safeConcentration.rows(),
                    safeConcentration.summary(),
                    selectedSeedChunks == null ? List.of() : selectedSeedChunks.stream()
                            .map(RetrievedChunk::chunkId).filter(Objects::nonNull).distinct().toList(),
                    chunkIds
            );
        }
        List<List<String>> rows = new ArrayList<>();
        List<ExtractedEvidence> docEvidence = extractedEvidence == null ? List.of() : extractedEvidence.stream()
                .filter(e -> documentId != null && documentId.toString().equals(e.documentId()))
                .toList();
        for (ExtractedEvidence evidence : docEvidence) {
            rows.add(List.of(
                    firstNonBlank(evidence.subQuestion(), reviewQuestion),
                    firstNonBlank(evidence.finding(), evidence.claim(), "Evidence item extracted without finding text."),
                    firstNonBlank(evidence.originalText(), "-"),
                    firstNonBlank(evidence.methodology(), "-"),
                    "Fallback row from existing extracted evidence; verify against full chunks.",
                    firstNonBlank(evidence.chunkId(), "-")
            ));
        }
        if (rows.isEmpty()) {
            rows.add(List.of(
                    firstNonBlank(reviewQuestion, "-"),
                    "No structured evidence was extracted for this paper.",
                    chunks.stream().limit(3).map(c -> truncate(c.text(), 180)).collect(Collectors.joining(" / ")),
                    "-",
                    "Fallback row from all available chunks; model synthesis failed or returned no rows.",
                    chunks.stream().limit(5).map(RetrievedChunk::chunkId).filter(Objects::nonNull).collect(Collectors.joining(", "))
            ));
        }
        return new ReviewPaperEvidenceTable(
                taskId,
                documentId,
                documentTitle,
                reviewQuestion,
                "Fallback per-paper table generated from available chunks and extracted evidence.",
                DEFAULT_HEADERS,
                rows,
                chunks.stream().map(RetrievedChunk::chunkId).filter(Objects::nonNull).distinct().toList(),
                iterations,
                0.4,
                List.of("MODEL_SYNTHESIS_FALLBACK"),
                Instant.now()
        );
    }

    private ConcentrationExtraction extractConcentration(String reviewQuestion,
                                                         UUID documentId,
                                                         String documentTitle,
                                                         List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return ConcentrationExtraction.notMentioned();
        }
        String userMessage = """
                Review question:
                %s

                Document:
                %s (%s)

                All paper chunks:
                %s
                """.formatted(firstNonBlank(reviewQuestion, ""), firstNonBlank(documentTitle, "unknown"),
                documentId, renderChunks(chunks));
        try {
            ChatResponse response = reasoningChatClient.chatCore(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_CONCENTRATION_EXTRACTION_SYSTEM)),
                    UserMessage.from(userMessage));
            AiMessage ai = response.aiMessage();
            String raw = ai == null ? null : ai.text();
            ConcentrationTableOutput output = objectMapper.readValue(extractJson(raw), ConcentrationTableOutput.class);
            List<String> headers = normalizeConcentrationHeaders(output.headers);
            List<List<String>> rows = normalizeConcentrationRows(output.rows, headers.size());
            String summary = firstNonBlank(output.summary, summarizeConcentrationRows(headers, rows), NOT_MENTIONED);
            return new ConcentrationExtraction(
                    renderConcentrationDocument(summary, headers, rows),
                    headers,
                    rows,
                    summary
            );
        } catch (Exception e) {
            log.warn("Concentration extraction failed for document {}: {}", documentId, e.getMessage());
            return ConcentrationExtraction.notMentioned();
        }
    }

    private String renderChunks(List<RetrievedChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            builder.append("\n--- chunk_id=").append(chunk.chunkId())
                    .append("; section=").append(firstNonBlank(chunk.sectionPath(), "-"))
                    .append(" ---\n")
                    .append(firstNonBlank(chunk.text(), ""));
        }
        return builder.toString();
    }

    private List<String> normalizeConcentrationHeaders(List<String> headers) {
        return CONCENTRATION_HEADERS;
    }

    private List<List<String>> normalizeConcentrationRows(List<List<String>> rows, int width) {
        if (rows == null || rows.isEmpty()) {
            return List.of(java.util.Collections.nCopies(width, NOT_MENTIONED));
        }
        List<List<String>> normalized = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> safe = row == null ? new ArrayList<>() : new ArrayList<>(row);
            while (safe.size() < width) {
                safe.add(NOT_MENTIONED);
            }
            if (safe.size() > width) {
                safe = safe.subList(0, width);
            }
            normalized.add(safe.stream()
                    .map(this::normalizeMentionedCell)
                    .toList());
        }
        return normalized;
    }

    private String normalizeMentionedCell(String value) {
        if (value == null || value.isBlank() || "-".equals(value.trim()) || "N/A".equalsIgnoreCase(value.trim())) {
            return NOT_MENTIONED;
        }
        return value.trim();
    }

    private String renderConcentrationDocument(String summary, List<String> headers, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("Summary: ").append(firstNonBlank(summary, NOT_MENTIONED)).append("\n");
        builder.append("Table:\n");
        builder.append(String.join(" | ", headers)).append("\n");
        for (List<String> row : rows == null ? List.<List<String>>of() : rows) {
            builder.append(row == null ? NOT_MENTIONED : row.stream()
                    .map(value -> firstNonBlank(value, NOT_MENTIONED))
                    .collect(Collectors.joining(" | "))).append("\n");
        }
        return builder.toString().trim();
    }

    private String summarizeConcentrationRows(List<String> headers, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return NOT_MENTIONED;
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .map(row -> {
                    List<String> parts = new ArrayList<>();
                    for (int i = 0; i < row.size(); i++) {
                        String value = normalizeMentionedCell(row.get(i));
                        if (!NOT_MENTIONED.equals(value)) {
                            String header = headers != null && i < headers.size() ? headers.get(i) : "Column " + (i + 1);
                            parts.add(header + ": " + value);
                        }
                    }
                    return String.join("; ", parts);
                })
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" / "));
    }

    private List<String> normalizeHeaders(List<String> headers, String templateId) {
        if (isAntimicrobialTemplate(templateId)) {
            return ANTIMICROBIAL_HEADERS;
        }
        List<String> safe = headers == null ? List.of() : headers.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        return safe.isEmpty() ? DEFAULT_HEADERS : safe;
    }

    private List<List<String>> normalizeRows(List<List<String>> rows, int width, String templateId) {
        String missing = isAntimicrobialTemplate(templateId) ? NOT_MENTIONED : "-";
        if (rows == null || rows.isEmpty()) {
            return isAntimicrobialTemplate(templateId)
                    ? List.of(java.util.Collections.nCopies(width, NOT_MENTIONED))
                    : List.of(List.of("No explicit row generated", "-", "-", "-", "-", "-"));
        }
        List<List<String>> normalized = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> safe = row == null ? new ArrayList<>() : new ArrayList<>(row);
            while (safe.size() < width) {
                safe.add(missing);
            }
            if (safe.size() > width) {
                safe = safe.subList(0, width);
            }
            normalized.add(safe.stream().map(value -> {
                if (value == null || value.isBlank() || "-".equals(value.trim()) || "N/A".equalsIgnoreCase(value.trim())) {
                    return missing;
                }
                return value;
            }).toList());
        }
        return normalized;
    }

    private boolean isAntimicrobialTemplate(String templateId) {
        return templateId == null || templateId.isBlank() || ANTIMICROBIAL_TEMPLATE_ID.equals(templateId);
    }

    private boolean isBetter(ReviewPaperEvidenceTable candidate, ReviewPaperEvidenceTable current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        int candidateCells = candidate.rows() == null ? 0 : candidate.rows().stream().mapToInt(List::size).sum();
        int currentCells = current.rows() == null ? 0 : current.rows().stream().mapToInt(List::size).sum();
        double candidateScore = candidate.confidence() + Math.min(0.2, candidateCells / 100.0);
        double currentScore = current.confidence() + Math.min(0.2, currentCells / 100.0);
        return candidateScore >= currentScore;
    }

    private String summarizeCandidate(ReviewPaperEvidenceTable candidate) throws JsonProcessingException {
        if (candidate == null) {
            return "";
        }
        return objectMapper.writeValueAsString(Map.of(
                "paperSummary", candidate.paperSummary(),
                "headers", candidate.headers(),
                "rows", candidate.rows(),
                "warnings", candidate.warnings()
        ));
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PaperTableOutput {
        public String paperSummary;
        public List<String> headers;
        public List<List<String>> rows;
        public double confidence;
        public List<String> warnings;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ConcentrationTableOutput {
        public String summary;
        public List<String> headers;
        public List<List<String>> rows;
    }

    private record ConcentrationExtraction(
            String document,
            List<String> headers,
            List<List<String>> rows,
            String summary
    ) {
        private static ConcentrationExtraction empty() {
            return new ConcentrationExtraction(null, List.of(), List.of(), null);
        }

        private static ConcentrationExtraction notMentioned() {
            return new ConcentrationExtraction(NOT_MENTIONED, CONCENTRATION_HEADERS,
                    List.of(java.util.Collections.nCopies(CONCENTRATION_HEADERS.size(), NOT_MENTIONED)),
                    NOT_MENTIONED);
        }
    }
}
