package com.example.demo_01.ai.report.model;

import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;

import java.util.List;
import java.util.Map;

public final class ReportAggregationModels {

    private ReportAggregationModels() {
    }

    public record ReportAggregationResult(
            ReportAggregationOverview overview,
            Map<String, Integer> sourceCategoryCounts,
            Map<String, Integer> structureTypeCounts,
            Map<String, Integer> assayMethodCounts,
            Map<String, Integer> organismCounts,
            List<CompoundAggregation> compounds,
            List<MechanismEntry> mechanisms,
            SupplementaryStats supplementary,
            List<String> sourceDocuments
    ) {
    }

    public record ReportAggregationOverview(
            int evidenceRowCount,
            int distinctCompoundCount,
            int documentCount,
            int organismCount,
            int pureCompoundCount,
            int naturalExtractCount,
            int localLabelCount
    ) {
    }

    public record CompoundAggregation(
            String dedupKey,
            String displayName,
            NameKind nameKind,
            int evidenceRowCount,
            int documentCount,
            List<String> organisms,
            List<String> structureTypes,
            List<String> activitySamples,
            List<String> sourceDocuments
    ) {
    }

    public record MechanismEntry(
            String compoundName,
            String mechanism,
            String validationMethod,
            String sourceDocument
    ) {
    }

    public record SupplementaryStats(
            int cytotoxicityCount,
            int resistanceCount,
            int synergyCount,
            int patentCount,
            List<String> cytotoxicitySamples,
            List<String> resistanceSamples,
            List<String> synergySamples
    ) {
    }
}
