package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class DocumentKnowledgeMerger {

    public DocumentKnowledgeRecord merge(DocumentKnowledgeRecord existing,
                                         DocumentKnowledgeRecord incoming,
                                         UUID documentId,
                                         UUID taskId,
                                         KnowledgeStatus status,
                                         String promptVersion,
                                         String knowledgeVersion,
                                         List<String> coverageChunkIds) {
        DocumentKnowledgeRecord base = existing == null ? empty(documentId) : existing;
        DocumentKnowledgeRecord next = incoming == null ? empty(documentId) : incoming;
        List<DocumentKnowledgeCompound> compounds = mergeCompounds(base.compounds(), next.compounds());
        return new DocumentKnowledgeRecord(
                documentId,
                firstNonBlank(next.documentSummary(), base.documentSummary()),
                mergeStrings(base.researchObjects(), next.researchObjects()),
                mergeStrings(base.species(), next.species()),
                mergeStrings(base.genesOrProteins(), next.genesOrProteins()),
                mergeStrings(base.pathwaysOrProcesses(), next.pathwaysOrProcesses()),
                mergeStrings(base.developmentalStages(), next.developmentalStages()),
                mergeStrings(base.methods(), next.methods()),
                compounds,
                mergeStrings(base.keyFindings(), next.keyFindings()),
                mergeStrings(base.innovationPoints(), next.innovationPoints()),
                mergeStrings(base.limitations(), next.limitations()),
                mergeAnchors(base.evidenceAnchors(), next.evidenceAnchors()),
                status,
                promptVersion,
                knowledgeVersion,
                Math.max(base.confidence(), next.confidence()),
                mergeStrings(base.coverageChunkIds(), coverageChunkIds),
                taskId,
                Instant.now()
        );
    }

    private DocumentKnowledgeRecord empty(UUID documentId) {
        return new DocumentKnowledgeRecord(
                documentId, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), KnowledgeStatus.MISS,
                null, null, 0.0, List.of(), null, null
        );
    }

    private List<String> mergeStrings(List<String> left, List<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addAll(merged, left);
        addAll(merged, right);
        return new ArrayList<>(merged);
    }

    private List<DocumentKnowledgeCompound> mergeCompounds(List<DocumentKnowledgeCompound> left,
                                                           List<DocumentKnowledgeCompound> right) {
        Map<String, DocumentKnowledgeCompound> merged = new LinkedHashMap<>();
        addCompounds(merged, left);
        addCompounds(merged, right);
        return new ArrayList<>(merged.values());
    }

    private void addCompounds(Map<String, DocumentKnowledgeCompound> merged, List<DocumentKnowledgeCompound> compounds) {
        if (compounds == null) {
            return;
        }
        for (DocumentKnowledgeCompound compound : compounds) {
            String key = firstNonBlank(compound.normalizedCompoundId(), compound.localAlias(),
                    compound.canonicalName(), compound.resolvedName(), compound.iupacName());
            if (key == null) {
                continue;
            }
            DocumentKnowledgeCompound previous = merged.get(key.toLowerCase());
            if (previous == null || compound.confidence() >= previous.confidence()) {
                merged.put(key.toLowerCase(), compound);
            }
        }
    }

    private List<DocumentEvidenceAnchor> mergeAnchors(List<DocumentEvidenceAnchor> left,
                                                      List<DocumentEvidenceAnchor> right) {
        return mergeObjects(left, right, DocumentEvidenceAnchor::sourceChunkId);
    }

    private <T> List<T> mergeObjects(List<T> left, List<T> right, Function<T, String> keyFn) {
        Map<String, T> merged = new LinkedHashMap<>();
        if (left != null) {
            for (T value : left) {
                String key = keyFn.apply(value);
                if (key != null && !key.isBlank()) {
                    merged.put(key, value);
                }
            }
        }
        if (right != null) {
            for (T value : right) {
                String key = keyFn.apply(value);
                if (key != null && !key.isBlank()) {
                    merged.put(key, value);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void addAll(LinkedHashSet<String> merged, List<String> values) {
        if (values == null) {
            return;
        }
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(merged::add);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
