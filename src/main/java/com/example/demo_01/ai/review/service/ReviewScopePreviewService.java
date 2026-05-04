package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.DocumentKnowledgeRepository;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.repository.ReviewRepository.DocumentSynopsisRecord;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReviewScopePreviewService {

    private static final int INITIAL_DOCUMENT_LIMIT = 8;

    @Resource
    private QueryAnalyzerService queryAnalyzerService;

    @Resource
    private QueryExpansionService queryExpansionService;

    @Resource
    private ReviewRepository reviewRepository;

    @Resource
    private DocumentKnowledgeRepository documentKnowledgeRepository;

    public ReviewScopePreview buildInitialPreview(String question) {
        QueryAnalysis analysis = queryAnalyzerService.analyze(question);
        List<ReviewDocumentCandidate> documents = findInitialDocuments(analysis);
        return buildPreview(analysis, documents, List.of());
    }

    public ReviewScopePreview buildTaskPreview(UUID taskId) {
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Review task not found: " + taskId));
        QueryAnalysis analysis = task.queryAnalysis() == null
                ? queryAnalyzerService.analyze(task.question())
                : task.queryAnalysis();
        return buildPreview(
                analysis,
                reviewRepository.findDocumentCandidates(taskId),
                reviewRepository.findAllCandidates(taskId)
        );
    }

    public ReviewScopePreview buildPreview(QueryAnalysis analysis,
                                           List<ReviewDocumentCandidate> documents,
                                           List<ReviewCandidate> candidates) {
        List<QuestionOption> questions = buildQuestions(analysis);
        List<EntityOption> entities = buildEntities(analysis, questions);
        List<DocumentOption> documentOptions = buildDocuments(documents, candidates, questions, entities);
        return new ReviewScopePreview(analysis, questions, entities, documentOptions);
    }

    private List<ReviewDocumentCandidate> findInitialDocuments(QueryAnalysis analysis) {
        List<String> queries = queryExpansionService.expand(analysis);
        Set<UUID> ids = new LinkedHashSet<>();
        for (String query : queries) {
            if (ids.size() >= INITIAL_DOCUMENT_LIMIT) {
                break;
            }
            try {
                ids.addAll(reviewRepository.searchDocumentsByFts(query, INITIAL_DOCUMENT_LIMIT));
            } catch (Exception ignored) {
                // Initial preview should be best-effort. Retrieval segment performs full search later.
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, DocumentSynopsisRecord> synopses = reviewRepository.findDocumentSynopsisByIds(ids);
        return ids.stream()
                .limit(INITIAL_DOCUMENT_LIMIT)
                .map(id -> toDocumentCandidate(id, synopses.get(id)))
                .toList();
    }

    private ReviewDocumentCandidate toDocumentCandidate(UUID documentId, DocumentSynopsisRecord record) {
        var synopsis = record == null ? null : record.synopsis();
        return new ReviewDocumentCandidate(
                null,
                null,
                documentId,
                record == null ? null : record.title(),
                0,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                Relevance.MEDIUM,
                "Initial synopsis match",
                synopsis == null ? null : synopsis.summary(),
                synopsis == null || synopsis.innovationPoints() == null ? List.of() : synopsis.innovationPoints(),
                synopsis == null || synopsis.keyFindings() == null ? List.of() : synopsis.keyFindings(),
                false,
                true
        );
    }

    private List<QuestionOption> buildQuestions(QueryAnalysis analysis) {
        List<String> canonical = analysis.subQuestions() == null || analysis.subQuestions().isEmpty()
                ? List.of(analysis.mainQuestion())
                : analysis.subQuestions();
        List<String> display = analysis.displaySubQuestions() == null || analysis.displaySubQuestions().isEmpty()
                ? canonical
                : analysis.displaySubQuestions();
        List<QuestionOption> options = new ArrayList<>();
        for (int i = 0; i < canonical.size(); i++) {
            String canonicalText = safe(canonical.get(i));
            String displayText = i < display.size() ? safe(display.get(i)) : canonicalText;
            options.add(new QuestionOption("q" + (i + 1), canonicalText, displayText, true));
        }
        return options;
    }

    private List<EntityOption> buildEntities(QueryAnalysis analysis, List<QuestionOption> questions) {
        List<String> questionIds = questions.stream().map(QuestionOption::id).toList();
        List<EntityOption> entities = new ArrayList<>();
        int index = 1;
        for (String entity : distinct(analysis.keyEntities())) {
            entities.add(new EntityOption("e" + index++, entity, entity, "ENTITY", questionIds, true));
        }
        for (String concept : distinct(analysis.keyConcepts())) {
            entities.add(new EntityOption("e" + index++, concept, concept, "CONCEPT", questionIds, true));
        }
        return entities;
    }

    private List<DocumentOption> buildDocuments(List<ReviewDocumentCandidate> documents,
                                                List<ReviewCandidate> candidates,
                                                List<QuestionOption> questions,
                                                List<EntityOption> entities) {
        Map<UUID, List<ReviewCandidate>> chunksByDocument = candidates == null ? Map.of() : candidates.stream()
                .filter(candidate -> candidate.documentId() != null)
                .collect(Collectors.groupingBy(
                        ReviewCandidate::documentId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<String> questionIds = questions.stream().map(QuestionOption::id).toList();
        List<DocumentOption> options = new ArrayList<>();
        Set<UUID> documentIds = documents == null ? Set.of() : documents.stream()
                .map(ReviewDocumentCandidate::documentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, DocumentKnowledgeRecord> knowledgeByDocument =
                documentKnowledgeRepository.findKnowledgeByDocumentIds(documentIds);
        Map<UUID, List<DocumentCompoundAlias>> aliasesByDocument =
                documentKnowledgeRepository.findAliasesByDocumentIds(documentIds);
        for (ReviewDocumentCandidate document : documents == null ? List.<ReviewDocumentCandidate>of() : documents) {
            List<ReviewCandidate> documentChunks = chunksByDocument.getOrDefault(document.documentId(), List.of());
            DocumentKnowledgeRecord knowledge = knowledgeByDocument.get(document.documentId());
            List<DocumentCompoundAlias> aliases = aliasesByDocument.getOrDefault(document.documentId(), List.of());
            List<String> relatedEntities = relatedEntities(document, documentChunks, entities);
            options.add(new DocumentOption(
                    document.documentId() == null ? safe(document.documentTitle()) : document.documentId().toString(),
                    document.documentId(),
                    safeTitle(document.documentTitle()),
                    questionIds,
                    relatedEntities,
                    safeList(document.keyFindings()),
                    safeList(document.innovationPoints()),
                    document.relevance(),
                    firstNonNull(document.finalScore(), document.contributionScore(), document.seedMaxScore()),
                    document.promotionReason(),
                    document.selected(),
                    documentChunks.stream().map(ReviewCandidate::chunkId).filter(Objects::nonNull).distinct().toList(),
                    previewText(document, documentChunks, knowledge),
                    knowledge == null ? KnowledgeStatus.MISS : knowledge.knowledgeStatus(),
                    aliases,
                    compoundNames(knowledge, aliases)
            ));
        }
        return options;
    }

    private List<String> relatedEntities(ReviewDocumentCandidate document,
                                         List<ReviewCandidate> chunks,
                                         List<EntityOption> entities) {
        String haystack = String.join(" ",
                safe(document.documentTitle()),
                safe(document.synopsisSummary()),
                String.join(" ", safeList(document.keyFindings())),
                String.join(" ", safeList(document.innovationPoints())),
                chunks.stream().map(ReviewCandidate::chunkText).filter(Objects::nonNull).limit(3).collect(Collectors.joining(" "))
        ).toLowerCase();
        List<String> matches = entities.stream()
                .map(EntityOption::canonicalName)
                .filter(name -> !safe(name).isBlank())
                .filter(name -> haystack.contains(name.toLowerCase()))
                .distinct()
                .toList();
        if (!matches.isEmpty()) {
            return matches;
        }
        return entities.stream().map(EntityOption::canonicalName).limit(6).toList();
    }

    private String previewText(ReviewDocumentCandidate document,
                               List<ReviewCandidate> chunks,
                               DocumentKnowledgeRecord knowledge) {
        if (knowledge != null && knowledge.documentSummary() != null && !knowledge.documentSummary().isBlank()) {
            return knowledge.documentSummary();
        }
        if (document.synopsisSummary() != null && !document.synopsisSummary().isBlank()) {
            return document.synopsisSummary();
        }
        return chunks.stream()
                .map(ReviewCandidate::chunkText)
                .filter(Objects::nonNull)
                .findFirst()
                .map(text -> text.length() > 800 ? text.substring(0, 800) + "..." : text)
                .orElse("");
    }

    private List<String> compoundNames(DocumentKnowledgeRecord knowledge, List<DocumentCompoundAlias> aliases) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (knowledge != null && knowledge.compounds() != null) {
            for (DocumentKnowledgeCompound compound : knowledge.compounds()) {
                addName(names, compound.canonicalName());
                addName(names, compound.resolvedName());
                if (compound.resolutionStatus() == CompoundResolutionStatus.UNRESOLVED) {
                    addName(names, "unresolved local compound label: " + compound.localAlias());
                }
            }
        }
        for (DocumentCompoundAlias alias : aliases == null ? List.<DocumentCompoundAlias>of() : aliases) {
            addName(names, alias.resolvedName());
            if (alias.resolutionStatus() == CompoundResolutionStatus.UNRESOLVED) {
                addName(names, "unresolved local compound label: " + alias.localAlias());
            }
        }
        return new ArrayList<>(names);
    }

    private void addName(LinkedHashSet<String> names, String value) {
        if (value != null && !value.isBlank()) {
            names.add(value.trim());
        }
    }

    private List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private Double firstNonNull(Double... values) {
        for (Double value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String safeTitle(String value) {
        return value == null || value.isBlank() ? "Untitled document" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
