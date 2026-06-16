package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.report.model.ReportAggregationModels.CompoundAggregation;
import com.example.demo_01.ai.report.model.ReportAggregationModels.MechanismEntry;
import com.example.demo_01.ai.report.model.ReportAggregationModels.ReportAggregationOverview;
import com.example.demo_01.ai.report.model.ReportAggregationModels.ReportAggregationResult;
import com.example.demo_01.ai.report.model.ReportAggregationModels.SupplementaryStats;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReportAggregationService {

    public ReportAggregationResult aggregate(List<RankedEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return emptyResult();
        }

        Map<String, Integer> sourceCounts = new HashMap<>();
        Map<String, Integer> structureCounts = new HashMap<>();
        Map<String, Integer> assayCounts = new HashMap<>();
        Map<String, Integer> organismCounts = new HashMap<>();
        Map<String, MutableCompound> compounds = new LinkedHashMap<>();
        List<MechanismEntry> mechanisms = new ArrayList<>();
        SupplementaryAccumulator supplementary = new SupplementaryAccumulator();
        Set<UUID> documents = new HashSet<>();
        Set<String> organisms = new HashSet<>();
        Set<String> dedupKeys = new HashSet<>();
        int pureCompounds = 0;
        int naturalExtracts = 0;
        int localLabels = 0;
        LinkedHashSet<String> sourceDocuments = new LinkedHashSet<>();

        for (RankedEvidence item : evidence) {
            CompoundEvidenceRecord record = item.evidence();
            var row = record.row();
            documents.add(record.documentId());
            increment(sourceCounts, firstNonBlank(row.sourceCategory(), "未注明来源"));
            increment(structureCounts, firstNonBlank(row.structureType(), "未注明结构类型"));
            increment(assayCounts, firstNonBlank(row.assayMethod(), "未注明实验方法"));
            increment(organismCounts, firstNonBlank(row.oomyceteScientificName(), "未注明测试对象"));
            addNonBlank(organisms, row.oomyceteScientificName());

            String dedupKey = firstNonBlank(record.dedupKey(), fallbackDedupKey(record));
            dedupKeys.add(dedupKey);
            NameKind nameKind = record.nameKind() == null ? NameKind.PURE_COMPOUND : record.nameKind();
            switch (nameKind) {
                case PURE_COMPOUND -> pureCompounds++;
                case NATURAL_EXTRACT -> naturalExtracts++;
                case LOCAL_LABEL -> localLabels++;
            }

            MutableCompound compound = compounds.computeIfAbsent(dedupKey, ignored -> new MutableCompound(
                    dedupKey, displayName(record), nameKind));
            compound.evidenceRowCount++;
            compound.documentIds.add(record.documentId());
            addNonBlank(compound.organisms, row.oomyceteScientificName());
            addNonBlank(compound.structureTypes, row.structureType());
            addSample(compound.activitySamples, row.activityData(), 5);
            addSample(compound.sourceDocuments, record.documentTitle(), 5);

            if (hasText(row.targetOrMechanism())) {
                mechanisms.add(new MechanismEntry(
                        displayName(record),
                        row.targetOrMechanism(),
                        value(row.targetValidationMethod()),
                        value(record.documentTitle())));
            }
            supplementary.accumulate(row);
            addNonBlank(sourceDocuments, record.documentTitle());
        }

        List<CompoundAggregation> compoundList = compounds.values().stream()
                .map(MutableCompound::freeze)
                .sorted(Comparator.comparingInt(CompoundAggregation::documentCount).reversed()
                        .thenComparingInt(CompoundAggregation::evidenceRowCount).reversed()
                        .thenComparing(CompoundAggregation::displayName))
                .toList();

        ReportAggregationOverview overview = new ReportAggregationOverview(
                evidence.size(),
                dedupKeys.size(),
                documents.size(),
                organisms.size(),
                countDistinctKind(compounds, NameKind.PURE_COMPOUND),
                countDistinctKind(compounds, NameKind.NATURAL_EXTRACT),
                countDistinctKind(compounds, NameKind.LOCAL_LABEL));

        return new ReportAggregationResult(
                overview,
                sortedCounts(sourceCounts),
                sortedCounts(structureCounts),
                sortedCounts(assayCounts),
                sortedCounts(organismCounts),
                compoundList,
                mechanisms.stream().limit(30).toList(),
                supplementary.freeze(),
                List.copyOf(sourceDocuments));
    }

    private ReportAggregationResult emptyResult() {
        return new ReportAggregationResult(
                new ReportAggregationOverview(0, 0, 0, 0, 0, 0, 0),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                new SupplementaryStats(0, 0, 0, 0, List.of(), List.of(), List.of()),
                List.of());
    }

    private int countDistinctKind(Map<String, MutableCompound> compounds, NameKind kind) {
        return (int) compounds.values().stream()
                .filter(compound -> compound.nameKind == kind)
                .count();
    }

    private String displayName(CompoundEvidenceRecord record) {
        return firstNonBlank(
                record.row().compoundStandardName(),
                record.row().compoundOriginalName(),
                "未命名实体");
    }

    private String fallbackDedupKey(CompoundEvidenceRecord record) {
        return "compound:" + normalize(displayName(record));
    }

    private void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private Map<String, Integer> sortedCounts(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(sorted);
    }

    private void addNonBlank(Collection<String> values, String value) {
        if (!hasText(value)) {
            return;
        }
        String trimmed = value.trim();
        if (!values.contains(trimmed)) {
            values.add(trimmed);
        }
    }

    private void addNonBlank(Set<String> values, String value) {
        addNonBlank((Collection<String>) values, value);
    }

    private void addSample(List<String> samples, String value, int limit) {
        if (!hasText(value) || samples.size() >= limit) {
            return;
        }
        String trimmed = value.trim();
        if (!samples.contains(trimmed)) {
            samples.add(trimmed);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class MutableCompound {
        private final String dedupKey;
        private final String displayName;
        private final NameKind nameKind;
        private int evidenceRowCount;
        private final Set<UUID> documentIds = new HashSet<>();
        private final Set<String> organisms = new LinkedHashSet<>();
        private final Set<String> structureTypes = new LinkedHashSet<>();
        private final List<String> activitySamples = new ArrayList<>();
        private final List<String> sourceDocuments = new ArrayList<>();

        private MutableCompound(String dedupKey, String displayName, NameKind nameKind) {
            this.dedupKey = dedupKey;
            this.displayName = displayName;
            this.nameKind = nameKind;
        }

        private CompoundAggregation freeze() {
            return new CompoundAggregation(
                    dedupKey,
                    displayName,
                    nameKind,
                    evidenceRowCount,
                    documentIds.size(),
                    List.copyOf(organisms),
                    List.copyOf(structureTypes),
                    List.copyOf(activitySamples),
                    List.copyOf(sourceDocuments));
        }
    }

    private static final class SupplementaryAccumulator {
        private int cytotoxicityCount;
        private int resistanceCount;
        private int synergyCount;
        private int patentCount;
        private final List<String> cytotoxicitySamples = new ArrayList<>();
        private final List<String> resistanceSamples = new ArrayList<>();
        private final List<String> synergySamples = new ArrayList<>();

        private void accumulate(com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow row) {
            if (hasTextStatic(row.cytotoxicity())) {
                cytotoxicityCount++;
                addSampleStatic(cytotoxicitySamples, row.cytotoxicity(), 5);
            }
            if (hasTextStatic(row.resistanceCrossResistance())) {
                resistanceCount++;
                addSampleStatic(resistanceSamples, row.resistanceCrossResistance(), 5);
            }
            if (hasTextStatic(row.synergy())) {
                synergyCount++;
                addSampleStatic(synergySamples, row.synergy(), 5);
            }
            if (hasTextStatic(row.patentInformation())) {
                patentCount++;
            }
        }

        private SupplementaryStats freeze() {
            return new SupplementaryStats(
                    cytotoxicityCount,
                    resistanceCount,
                    synergyCount,
                    patentCount,
                    List.copyOf(cytotoxicitySamples),
                    List.copyOf(resistanceSamples),
                    List.copyOf(synergySamples));
        }

        private static boolean hasTextStatic(String value) {
            return value != null && !value.isBlank();
        }

        private static void addSampleStatic(List<String> samples, String value, int limit) {
            if (!hasTextStatic(value) || samples.size() >= limit) {
                return;
            }
            String trimmed = value.trim();
            if (!samples.contains(trimmed)) {
                samples.add(trimmed);
            }
        }
    }
}
