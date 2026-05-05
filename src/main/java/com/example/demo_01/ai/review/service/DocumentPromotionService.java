package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentSynopsis;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.Relevance;

import com.example.demo_01.ai.review.model.ReviewModels.ReviewDocumentCandidate;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.repository.ReviewRepository.DocumentSynopsisRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentPromotionService {

    private static final int DOCUMENT_LLM_BATCH_SIZE = 6;
    private static final double MIN_FINAL_SCORE = 0.55;

    @Resource
    private ReviewProperties reviewProperties;

    @Resource
    private ReviewRepository reviewRepository;

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    private final LlmBatchProcessor batchProcessor = new LlmBatchProcessor();

    public DocumentPromotionResult promote(QueryAnalysis analysis,
                                           String canonicalQuestion,
                                           List<RetrievedChunk> seedChunks) {
        if (seedChunks == null || seedChunks.isEmpty()) {
            return new DocumentPromotionResult(List.of(), List.of());
        }
        ReviewProperties.Retrieval cfg = reviewProperties.getRetrieval();

        Map<UUID, List<RetrievedChunk>> byDocument = seedChunks.stream()
                .filter(chunk -> chunk.documentId() != null)
                .collect(Collectors.groupingBy(
                        RetrievedChunk::documentId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<UUID, DocumentSynopsisRecord> synopsisById =
                reviewRepository.findDocumentSynopsisByIds(byDocument.keySet());

        List<DocumentScoreCard> heuristics = byDocument.entrySet().stream()
                .map(entry -> buildHeuristicScoreCard(
                        entry.getKey(),
                        entry.getValue(),
                        synopsisById.get(entry.getKey()),
                        analysis))
                .sorted(Comparator.comparingDouble(DocumentScoreCard::heuristicScore).reversed())
                .toList();

        List<DocumentScoreCard> shortlist = heuristics.stream()
                .limit(cfg.getDocumentShortlistTop())
                .toList();
        Map<UUID, DocumentAssessment> assessments = assessContribution(canonicalQuestion, analysis, shortlist);

        List<DocumentScoreCard> scored = heuristics.stream()
                .map(card -> applyAssessment(card, assessments.get(card.documentId())))
                .sorted(Comparator.comparingDouble(DocumentScoreCard::finalScore).reversed())
                .toList();

        List<DocumentScoreCard> selectedDocs = selectDocuments(scored, cfg.getDocumentExpandTop());
        Set<UUID> selectedIds = selectedDocs.stream()
                .map(DocumentScoreCard::documentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, DocumentScoreCard> selectedMap = selectedDocs.stream()
                .collect(Collectors.toMap(DocumentScoreCard::documentId, card -> card));

        List<RetrievedChunk> promoted = reviewRepository.findPriorityChunksByDocumentIds(
                selectedIds, cfg.getDocumentExpandChunkLimit()).stream()
                .filter(chunk -> !containsSeedChunk(byDocument.get(chunk.documentId()), chunk.chunkId()))
                .map(chunk -> enrichPromotedChunk(chunk, selectedMap.get(chunk.documentId())))
                .toList();

        List<ReviewDocumentCandidate> candidates = scored.stream()
                .map(card -> toDocumentCandidate(card, selectedIds.contains(card.documentId())))
                .toList();
        return new DocumentPromotionResult(candidates, promoted);
    }

    private DocumentScoreCard buildHeuristicScoreCard(UUID documentId,
                                                      List<RetrievedChunk> seeds,
                                                      DocumentSynopsisRecord synopsisRecord,
                                                      QueryAnalysis analysis) {
        List<RetrievedChunk> orderedSeeds = seeds.stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .toList();
        double maxSeedScore = orderedSeeds.isEmpty() ? 0.0 : orderedSeeds.get(0).score();
        double avgTop3 = orderedSeeds.stream().limit(3).mapToDouble(RetrievedChunk::score).average().orElse(0.0);
        double seedScore = 0.5 * maxSeedScore
                + 0.3 * avgTop3
                + 0.2 * Math.min(orderedSeeds.size() / 4.0, 1.0);
        double sectionPriorScore = orderedSeeds.isEmpty()
                ? 0.0
                : orderedSeeds.stream().filter(this::isPrioritySection).count() / (double) orderedSeeds.size();
        RagDocumentSynopsis synopsis = synopsisRecord == null ? null : synopsisRecord.synopsis();
        double entityCoverageScore = computeEntityCoverage(analysis, synopsis);
        String documentTitle = orderedSeeds.stream()
                .map(RetrievedChunk::documentTitle)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(synopsisRecord == null ? null : synopsisRecord.title());
        String summary = synopsis == null ? null : synopsis.summary();
        List<String> keyFindings = synopsis == null || synopsis.keyFindings() == null ? List.of() : synopsis.keyFindings();
        List<String> innovationPoints = synopsis == null || synopsis.innovationPoints() == null ? List.of() : synopsis.innovationPoints();
        return new DocumentScoreCard(
                documentId,
                documentTitle,
                orderedSeeds,
                maxSeedScore,
                avgTop3,
                seedScore,
                sectionPriorScore,
                entityCoverageScore,
                entityCoverageScore,
                seedScore + entityCoverageScore + sectionPriorScore,
                mapHeuristicRelevance(seedScore + entityCoverageScore + sectionPriorScore),
                "heuristic fallback",
                summary,
                innovationPoints,
                keyFindings
        );
    }

    private Map<UUID, DocumentAssessment> assessContribution(String canonicalQuestion,
                                                             QueryAnalysis analysis,
                                                             List<DocumentScoreCard> shortlist) {
        List<DocumentScoreCard> assessable = shortlist.stream()
                .filter(card -> card.summary() != null && !card.summary().isBlank())
                .toList();
        if (assessable.isEmpty()) {
            return Map.of();
        }
        List<DocumentAssessment> results = batchProcessor.processInBatches(
                assessable,
                DOCUMENT_LLM_BATCH_SIZE,
                batch -> assessBatch(canonicalQuestion, analysis, batch),
                reviewTaskExecutor
        );
        Map<UUID, DocumentAssessment> result = new LinkedHashMap<>();
        for (DocumentAssessment assessment : results) {
            result.put(assessment.documentId(), assessment);
        }
        return result;
    }

    private List<DocumentAssessment> assessBatch(String canonicalQuestion,
                                                 QueryAnalysis analysis,
                                                 List<DocumentScoreCard> batch) {
        StringBuilder documentPrompts = new StringBuilder();
        for (DocumentScoreCard card : batch) {
            documentPrompts.append(PromptResources.format(
                    PromptCatalog.REVIEW_DOCUMENT_PROMOTION_DOCUMENT,
                    card.documentId(),
                    safe(card.documentTitle()),
                    safe(card.summary()),
                    joinSafe(card.keyFindings()),
                    joinSafe(card.innovationPoints())));
        }
        String userPrompt = PromptResources.format(
                PromptCatalog.REVIEW_DOCUMENT_PROMOTION_USER,
                canonicalQuestion,
                joinSafe(analysis.keyEntities()),
                joinSafe(analysis.keyConcepts()),
                documentPrompts);
        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_DOCUMENT_PROMOTION_SYSTEM)),
                    UserMessage.from(userPrompt)
            );
            AiMessage aiMessage = response.aiMessage();
            String raw = aiMessage == null ? "[]" : aiMessage.text();
            return objectMapper.readValue(extractJson(raw), new TypeReference<List<DocumentAssessment>>() {});
        } catch (Exception e) {
            log.warn("Document contribution assessment failed: {}", e.getMessage());
            return List.of();
        }
    }

    private DocumentScoreCard applyAssessment(DocumentScoreCard card, DocumentAssessment assessment) {
        double contributionScore = assessment == null ? card.entityCoverageScore() : normalizeScore(assessment.contributionScore());
        Relevance relevance = assessment == null || assessment.relevance() == null
                ? mapHeuristicRelevance(card.heuristicScore())
                : assessment.relevance();
        String reason = assessment == null ? "heuristic fallback"
                : safe(assessment.reason()) + formatAnsweredAspects(assessment.answeredAspects());
        double finalScore = 0.30 * card.seedScore()
                + 0.40 * contributionScore
                + 0.20 * card.entityCoverageScore()
                + 0.10 * card.sectionPriorScore();
        return new DocumentScoreCard(
                card.documentId(),
                card.documentTitle(),
                card.seedChunks(),
                card.seedMaxScore(),
                card.seedAvgTop3Score(),
                card.seedScore(),
                card.sectionPriorScore(),
                card.entityCoverageScore(),
                contributionScore,
                finalScore,
                relevance,
                reason,
                card.summary(),
                card.innovationPoints(),
                card.keyFindings()
        );
    }

    private List<DocumentScoreCard> selectDocuments(List<DocumentScoreCard> scored, int expandTop) {
        List<DocumentScoreCard> qualified = scored.stream()
                .filter(card -> meetsThreshold(card.relevance(), Relevance.MEDIUM) && card.finalScore() >= MIN_FINAL_SCORE)
                .toList();
        if (qualified.size() >= expandTop) {
            return qualified.stream().limit(expandTop).toList();
        }
        return scored.stream().limit(Math.min(expandTop, scored.size())).toList();
    }

    private ReviewDocumentCandidate toDocumentCandidate(DocumentScoreCard card, boolean selected) {
        return new ReviewDocumentCandidate(
                null,
                null,
                card.documentId(),
                card.documentTitle(),
                card.seedChunks().size(),
                card.seedChunks().stream().map(RetrievedChunk::chunkId).toList(),
                card.seedMaxScore(),
                card.seedAvgTop3Score(),
                card.sectionPriorScore(),
                card.entityCoverageScore(),
                card.contributionScore(),
                card.finalScore(),
                card.relevance(),
                card.reason(),
                card.summary(),
                card.innovationPoints(),
                card.keyFindings(),
                selected,
                selected
        );
    }

    private RetrievedChunk enrichPromotedChunk(RetrievedChunk chunk, DocumentScoreCard card) {
        return new RetrievedChunk(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.documentTitle(),
                chunk.text(),
                chunk.sectionPath(),
                card == null ? chunk.score() : card.finalScore(),
                "DOC_PROMOTED"
        );
    }

    private boolean containsSeedChunk(List<RetrievedChunk> seeds, String chunkId) {
        if (seeds == null || chunkId == null) {
            return false;
        }
        return seeds.stream().anyMatch(seed -> chunkId.equals(seed.chunkId()));
    }

    private double computeEntityCoverage(QueryAnalysis analysis, RagDocumentSynopsis synopsis) {
        if (analysis == null) {
            return 0.0;
        }
        Set<String> queryTerms = new LinkedHashSet<>();
        normalizeAll(analysis.keyEntities()).forEach(queryTerms::add);
        normalizeAll(analysis.keyConcepts()).forEach(queryTerms::add);
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> synopsisTerms = new LinkedHashSet<>();
        if (synopsis != null) {
            normalizeAll(synopsis.species()).forEach(synopsisTerms::add);
            normalizeAll(synopsis.geneOrProtein()).forEach(synopsisTerms::add);
            normalizeAll(synopsis.pathwayOrProcess()).forEach(synopsisTerms::add);
            normalizeAll(synopsis.developmentalStage()).forEach(synopsisTerms::add);
        }
        long overlap = queryTerms.stream()
                .filter(term -> synopsisTerms.stream().anyMatch(candidate -> candidate.contains(term) || term.contains(candidate)))
                .count();
        return overlap / (double) queryTerms.size();
    }

    private boolean isPrioritySection(RetrievedChunk chunk) {
        String value = safe(chunk.sectionPath()).toLowerCase(Locale.ROOT);
        return value.contains("result") || value.contains("discussion") || value.contains("conclusion");
    }

    private Relevance mapHeuristicRelevance(double score) {
        if (score >= 0.75) {
            return Relevance.HIGH;
        }
        if (score >= 0.55) {
            return Relevance.MEDIUM;
        }
        if (score >= 0.35) {
            return Relevance.LOW;
        }
        return Relevance.IRRELEVANT;
    }

    private boolean meetsThreshold(Relevance actual, Relevance minimum) {
        return actual != null && actual.ordinal() <= minimum.ordinal();
    }

    private double normalizeScore(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private List<String> normalizeAll(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT).trim())
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String formatAnsweredAspects(List<String> answeredAspects) {
        if (answeredAspects == null || answeredAspects.isEmpty()) {
            return "";
        }
        return " | answeredAspects=" + String.join(", ", answeredAspects);
    }

    private String joinSafe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("; ", values);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    public record DocumentPromotionResult(
            List<ReviewDocumentCandidate> documentCandidates,
            List<RetrievedChunk> expandedChunks
    ) {
    }

    private record DocumentScoreCard(
            UUID documentId,
            String documentTitle,
            List<RetrievedChunk> seedChunks,
            double seedMaxScore,
            double seedAvgTop3Score,
            double seedScore,
            double sectionPriorScore,
            double entityCoverageScore,
            double contributionScore,
            double finalScore,
            Relevance relevance,
            String reason,
            String summary,
            List<String> innovationPoints,
            List<String> keyFindings
    ) {
        double heuristicScore() {
            return seedScore + entityCoverageScore + sectionPriorScore;
        }
    }

    private record DocumentAssessment(
            UUID documentId,
            Relevance relevance,
            double contributionScore,
            String reason,
            List<String> answeredAspects
    ) {
    }
}
