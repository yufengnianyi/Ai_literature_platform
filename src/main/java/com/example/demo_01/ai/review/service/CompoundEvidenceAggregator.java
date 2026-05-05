package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.ExtractedEvidence;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewEvidenceRecord;
import com.example.demo_01.ai.review.model.ReviewModels.TypedEntities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class CompoundEvidenceAggregator {

    static final String NOT_MENTIONED = "未提及";

    private CompoundEvidenceAggregator() {
    }

    static List<CompoundActivityRow> fromEvidenceRecords(List<ReviewEvidenceRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return aggregate(records.stream()
                .filter(Objects::nonNull)
                .map(RecordEvidenceView::new)
                .toList());
    }

    static List<CompoundActivityRow> fromExtractedEvidence(List<ExtractedEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return aggregate(evidence.stream()
                .filter(Objects::nonNull)
                .map(ExtractedEvidenceView::new)
                .toList());
    }

    private static List<CompoundActivityRow> aggregate(List<? extends EvidenceView> evidence) {
        LinkedHashMap<String, CompoundAccumulator> byCompound = new LinkedHashMap<>();
        for (EvidenceView view : evidence) {
            TypedEntities typed = view.typedEntities();
            if (typed == null) {
                continue;
            }
            List<String> names = compoundNames(typed);
            if (names.isEmpty()) {
                continue;
            }
            if (names.size() > 1) {
                addAmbiguousCompoundRow(byCompound, names, typed, view);
                continue;
            }
            for (String name : names) {
                String cleanName = clean(name);
                if (cleanName.isBlank()) {
                    continue;
                }
                String key = compoundKey(cleanName, typed, view);
                CompoundAccumulator acc = byCompound.computeIfAbsent(key,
                        ignored -> new CompoundAccumulator(displayBaseName(cleanName)));
                acc.addName(cleanName);
                addEvidence(acc, typed, view);
            }
        }
        return byCompound.values().stream()
                .map(CompoundAccumulator::toRow)
                .toList();
    }

    private static List<String> compoundNames(TypedEntities typed) {
        List<String> canonicalNames = list(typed.compoundCanonicalName());
        if (!canonicalNames.isEmpty()) {
            return canonicalNames;
        }
        List<String> extractedNames = list(typed.moleculeOrMetabolite());
        if (!extractedNames.isEmpty()) {
            return extractedNames;
        }
        List<String> localAliases = list(typed.compoundLocalAlias());
        if (!localAliases.isEmpty()) {
            return localAliases;
        }
        return list(typed.compoundIdentifier());
    }

    private static void addAmbiguousCompoundRow(LinkedHashMap<String, CompoundAccumulator> byCompound,
                                                List<String> names,
                                                TypedEntities typed,
                                                EvidenceView view) {
        String joinedNames = String.join("; ", names);
        String key = "ambiguous:" + view.documentKey() + ":" + normalize(joinedNames);
        CompoundAccumulator acc = byCompound.computeIfAbsent(key,
                ignored -> new CompoundAccumulator("需人工复核的多化合物证据: " + joinedNames));
        addEvidence(acc, typed, view);
    }

    private static void addEvidence(CompoundAccumulator acc, TypedEntities typed, EvidenceView view) {
        acc.addAll(typed.compoundStructureType(), acc.structureTypes);
        acc.addAll(typed.compoundSource(), acc.sources);
        acc.addAll(typed.antimicrobialActivity(), acc.activities);
        acc.addAll(typed.targetOrganism(), acc.targetPathogens);
        if (acc.targetPathogens.isEmpty()) {
            acc.addAll(typed.species(), acc.targetPathogens);
        }
        acc.addAll(typed.assayMethod(), acc.assayMethods);
        acc.addAll(typed.proposedTarget(), acc.mechanisms);
        acc.addAll(typed.mechanism(), acc.mechanisms);
        acc.addAll(typed.cytotoxicitySafety(), acc.cytotoxicitySafety);
        acc.addAll(typed.reference(), acc.references);
        acc.addAll(typed.patentStatus(), acc.patentStatuses);
        acc.addFallback(view);
    }

    private static String compoundKey(String name, TypedEntities typed, EvidenceView view) {
        boolean unresolved = containsIgnoreCase(typed.compoundResolutionStatus(), "UNRESOLVED")
                || isLocalCompoundLabel(name);
        if (unresolved) {
            return "local:" + view.documentKey() + ":" + normalize(name);
        }
        List<String> identifiers = list(typed.compoundIdentifier());
        if (!identifiers.isEmpty()) {
            return "id:" + normalize(identifiers.get(0));
        }
        return "name:" + displayBaseName(name).toLowerCase(Locale.ROOT);
    }

    private static String displayBaseName(String name) {
        String normalized = clean(name);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("cinnamaldehyde")) {
            return "cinnamaldehyde";
        }
        return normalized;
    }

    private static boolean isLocalCompoundLabel(String name) {
        String lower = name.toLowerCase(Locale.ROOT).trim();
        return lower.matches("(compound|cmpd)\\s*[-_]?[a-z0-9]+")
                || lower.matches("[0-9]+[a-z]?")
                || lower.matches("[a-z][0-9]+");
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null) {
            return false;
        }
        return values.stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> expected.equalsIgnoreCase(value.trim()));
    }

    private static void addAll(List<String> values, Set<String> out) {
        if (values == null) {
            return;
        }
        values.stream()
                .map(CompoundEvidenceAggregator::clean)
                .filter(value -> !value.isBlank())
                .filter(value -> !isMissingMarker(value))
                .forEach(out::add);
    }

    private static List<String> list(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(CompoundEvidenceAggregator::clean)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String join(Set<String> values) {
        return values == null || values.isEmpty() ? NOT_MENTIONED : String.join("; ", values);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static boolean isMissingMarker(String value) {
        return "not mentioned".equalsIgnoreCase(value)
                || "not reported".equalsIgnoreCase(value)
                || NOT_MENTIONED.equals(value);
    }

    record CompoundActivityRow(
            String compoundName,
            String structureType,
            String source,
            String antimicrobialActivity,
            String targetPathogen,
            String assayMethod,
            String mechanism,
            String cytotoxicitySafety,
            String reference,
            String patentStatus
    ) {
    }

    private interface EvidenceView {
        TypedEntities typedEntities();

        String documentKey();

        String documentLabel();

        String finding();

        String methodology();
    }

    private record RecordEvidenceView(ReviewEvidenceRecord record) implements EvidenceView {
        @Override
        public TypedEntities typedEntities() {
            return record.typedEntities();
        }

        @Override
        public String documentKey() {
            UUID documentId = record.documentId();
            return documentId == null ? documentLabel() : documentId.toString();
        }

        @Override
        public String documentLabel() {
            return first(record.documentTitle(), record.documentId() == null ? "" : record.documentId().toString());
        }

        @Override
        public String finding() {
            return record.finding();
        }

        @Override
        public String methodology() {
            return record.methodology();
        }
    }

    private record ExtractedEvidenceView(ExtractedEvidence evidence) implements EvidenceView {
        @Override
        public TypedEntities typedEntities() {
            return evidence.typedEntities();
        }

        @Override
        public String documentKey() {
            return first(evidence.documentId(), evidence.documentTitle());
        }

        @Override
        public String documentLabel() {
            return first(evidence.documentTitle(), evidence.documentId());
        }

        @Override
        public String finding() {
            return evidence.finding();
        }

        @Override
        public String methodology() {
            return evidence.methodology();
        }
    }

    private static final class CompoundAccumulator {
        private final String baseName;
        private final LinkedHashSet<String> names = new LinkedHashSet<>();
        private final LinkedHashSet<String> structureTypes = new LinkedHashSet<>();
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private final LinkedHashSet<String> activities = new LinkedHashSet<>();
        private final LinkedHashSet<String> targetPathogens = new LinkedHashSet<>();
        private final LinkedHashSet<String> assayMethods = new LinkedHashSet<>();
        private final LinkedHashSet<String> mechanisms = new LinkedHashSet<>();
        private final LinkedHashSet<String> cytotoxicitySafety = new LinkedHashSet<>();
        private final LinkedHashSet<String> references = new LinkedHashSet<>();
        private final LinkedHashSet<String> patentStatuses = new LinkedHashSet<>();

        private CompoundAccumulator(String baseName) {
            this.baseName = baseName;
        }

        private void addName(String name) {
            names.add(name);
        }

        private void addAll(List<String> values, Set<String> out) {
            CompoundEvidenceAggregator.addAll(values, out);
        }

        private void addFallback(EvidenceView view) {
            if (activities.isEmpty() && !clean(view.finding()).isBlank()) {
                activities.add(view.finding());
            }
            if (assayMethods.isEmpty() && !clean(view.methodology()).isBlank()) {
                assayMethods.add(view.methodology());
            }
            if (references.isEmpty() && !clean(view.documentLabel()).isBlank()) {
                references.add(view.documentLabel());
            }
        }

        private CompoundActivityRow toRow() {
            String compoundName = baseName;
            List<String> derivatives = names.stream()
                    .filter(name -> !name.equalsIgnoreCase(baseName))
                    .toList();
            if (!derivatives.isEmpty()) {
                compoundName = compoundName + " (derivatives: " + String.join("; ", derivatives) + ")";
            }
            return new CompoundActivityRow(
                    compoundName,
                    join(structureTypes),
                    join(sources),
                    join(activities),
                    join(targetPathogens),
                    join(assayMethods),
                    join(mechanisms),
                    join(cytotoxicitySafety),
                    join(references),
                    join(patentStatuses)
            );
        }
    }

    private static String first(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
