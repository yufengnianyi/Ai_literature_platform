package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CompoundProfileAuditor {

    private static final Pattern P_MIC = Pattern.compile("\\bMIC\\b");
    private static final Pattern P_CONCENTRATION =
            Pattern.compile("\\b\\d+(?:\\.\\d+)?\\s*(μ?g\\s*[/·]?\\s*mL|µM|μM|nM|mM|mg\\s*[/·]?\\s*L|ppm)\\b");
    private static final Pattern P_DOSE_DEPENDENT =
            Pattern.compile("\\b(dose[- ]dependent|concentration[- ]dependent)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_PARADIGM =
            Pattern.compile("\\b(mycelial growth|micro[- ]?well dilution|zoosporogenesis|plate inhibition|XTT reduction|morphological)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern P_LOCAL_COMPOUND =
            Pattern.compile("^(?:compound|cmpd)\\s+\\d+", Pattern.CASE_INSENSITIVE);

    @Resource
    private ReviewProperties reviewProperties;

    @Resource
    private CompoundEvidenceSynthesizer compoundEvidenceSynthesizer;

    public AuditResult audit(SynthesizedCompoundRecord record,
                             List<ExtractedEvidence> sourceEvidence,
                             List<RetrievedChunk> sourceChunks,
                             Map<UUID, DocumentKnowledgeContext> knowledgeContexts) {

        if (!reviewProperties.getAudit().isEnableCoverageAudit()) {
            return new AuditResult(record, List.of(), false, List.of(), List.of());
        }

        String combinedChunkText = sourceChunks.stream()
                .map(RetrievedChunk::text)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "));

        List<String> warnings = new ArrayList<>();
        boolean shouldResynthesize = false;

        // MIC_OMITTED
        if (P_MIC.matcher(combinedChunkText).find() && noKeyMetric(record)) {
            warnings.add("MIC_OMITTED: source mentions MIC");
            shouldResynthesize = true;
        }

        // DOSE_GRADIENT_OMITTED
        Matcher concMatcher = P_CONCENTRATION.matcher(combinedChunkText);
        int concCount = 0;
        while (concMatcher.find()) concCount++;
        if (concCount >= 3 && noDoseGradient(record)) {
            warnings.add("DOSE_GRADIENT_OMITTED: " + concCount + " concentrations in source");
            shouldResynthesize = true;
        }

        // DOSE_DEPENDENT_NOT_CAPTURED
        if (P_DOSE_DEPENDENT.matcher(combinedChunkText).find() && noDoseDependent(record)) {
            warnings.add("DOSE_DEPENDENT_NOT_CAPTURED");
        }

        // PARADIGM_COVERAGE
        Set<String> sourceParadigms = new HashSet<>();
        Matcher pm = P_PARADIGM.matcher(combinedChunkText);
        while (pm.find()) sourceParadigms.add(pm.group().toLowerCase(Locale.ROOT));
        int profileParadigms = record.paradigmActivities() != null ? record.paradigmActivities().size() : 0;
        if (sourceParadigms.size() > profileParadigms) {
            warnings.add("PARADIGM_COVERAGE: source=" + sourceParadigms.size() + ", profile=" + profileParadigms);
            shouldResynthesize = true;
        }

        // IDENTITY_UNRESOLVED
        if (record.compoundName() != null && P_LOCAL_COMPOUND.matcher(record.compoundName()).find()) {
            warnings.add("IDENTITY_UNRESOLVED: " + record.compoundName());
        }

        // LOW_CONFIDENCE
        if (record.confidence() < 0.5) {
            warnings.add("LOW_CONFIDENCE: " + record.confidence());
        }

        SynthesizedCompoundRecord withWarnings = new SynthesizedCompoundRecord(
                record.compoundName(), record.documentId(), record.documentTitle(),
                record.role(), record.structureType(), record.source(),
                record.paradigmActivities(), record.mechanismSummary(), record.safetyProfile(),
                record.comparisons(), record.contextNote(), record.targetOrganisms(),
                record.confidence(), record.reference(), record.evidenceChunkIds(),
                warnings.isEmpty() ? record.coverageWarnings() : warnings);

        // Build retrieval directives and prompt hints from warnings
        List<RetrievalDirective> directives = new ArrayList<>();
        List<String> promptHints = new ArrayList<>();
        String compoundName = record.compoundName() != null ? record.compoundName() : "unknown";

        for (String warning : warnings) {
            if (warning.startsWith("MIC_OMITTED")) {
                directives.add(new RetrievalDirective("MIC_OMITTED",
                        List.of(compoundName + " MIC", compoundName + " IC50", compoundName + " EC50"), null));
                promptHints.add("Look for MIC/MFC/EC50/IC50 values for " + compoundName);
            } else if (warning.startsWith("DOSE_GRADIENT_OMITTED")) {
                directives.add(new RetrievalDirective("DOSE_GRADIENT_OMITTED",
                        List.of(compoundName + " concentration", compoundName + " dose response"), null));
                promptHints.add("Include all tested concentrations and their effects for " + compoundName);
            } else if (warning.startsWith("PARADIGM_COVERAGE")) {
                directives.add(new RetrievalDirective("PARADIGM_COVERAGE",
                        List.of(compoundName + " zoospore", compoundName + " mycelial growth", compoundName + " cytotoxicity"), null));
                promptHints.add("Check for additional experimental paradigms for " + compoundName);
            } else if (warning.startsWith("IDENTITY_UNRESOLVED")) {
                directives.add(new RetrievalDirective("IDENTITY_UNRESOLVED",
                        List.of(compoundName + " structure", compoundName + " identification"), null));
            }
        }

        // Attempt resynthesis if needed
        if (shouldResynthesize && reviewProperties.getAudit().getMaxResynthesisAttempts() > 0) {
            log.info("Triggering resynthesis for {} with hints: {}", record.compoundName(), warnings);
            String groupKey = (record.documentId() != null ? record.documentId() : "unknown")
                    + "::" + (record.compoundName() != null ? record.compoundName().toLowerCase(Locale.ROOT) : "unknown");
            SynthesizedCompoundRecord resynthesized = compoundEvidenceSynthesizer.synthesizeWithHints(
                    groupKey, sourceEvidence, knowledgeContexts, warnings);
            if (resynthesized != null && hasImprovement(resynthesized, record, warnings)) {
                log.info("Resynthesis improved record for {}", record.compoundName());
                withWarnings = new SynthesizedCompoundRecord(
                        resynthesized.compoundName(), resynthesized.documentId(), resynthesized.documentTitle(),
                        resynthesized.role(), resynthesized.structureType(), resynthesized.source(),
                        resynthesized.paradigmActivities(), resynthesized.mechanismSummary(), resynthesized.safetyProfile(),
                        resynthesized.comparisons(), resynthesized.contextNote(), resynthesized.targetOrganisms(),
                        resynthesized.confidence(), resynthesized.reference(), resynthesized.evidenceChunkIds(),
                        List.of());
                shouldResynthesize = false;
                warnings.clear();
                directives.clear();
                promptHints.clear();
            }
        }

        return new AuditResult(withWarnings, warnings, shouldResynthesize, directives, promptHints);
    }

    private boolean noKeyMetric(SynthesizedCompoundRecord record) {
        if (record.paradigmActivities() == null) return true;
        return record.paradigmActivities().stream()
                .allMatch(p -> p.keyMetric() == null || p.keyMetric().type() == null);
    }

    private boolean noDoseGradient(SynthesizedCompoundRecord record) {
        if (record.paradigmActivities() == null) return true;
        return record.paradigmActivities().stream()
                .allMatch(p -> p.doseGradient() == null || p.doseGradient().isEmpty());
    }

    private boolean noDoseDependent(SynthesizedCompoundRecord record) {
        if (record.paradigmActivities() == null) return true;
        return record.paradigmActivities().stream()
                .allMatch(p -> p.doseDependent() == null);
    }

    private boolean hasImprovement(SynthesizedCompoundRecord resynthesized,
                                    SynthesizedCompoundRecord original,
                                    List<String> warnings) {
        if (warnings.stream().anyMatch(w -> w.startsWith("MIC_OMITTED")) && !noKeyMetric(resynthesized)) return true;
        if (warnings.stream().anyMatch(w -> w.startsWith("DOSE_GRADIENT_OMITTED")) && !noDoseGradient(resynthesized)) return true;
        int newParadigms = resynthesized.paradigmActivities() != null ? resynthesized.paradigmActivities().size() : 0;
        int oldParadigms = original.paradigmActivities() != null ? original.paradigmActivities().size() : 0;
        if (newParadigms > oldParadigms) return true;
        return false;
    }

    public record AuditResult(
            SynthesizedCompoundRecord recordWithWarnings,
            List<String> warnings,
            boolean shouldResynthesize,
            List<RetrievalDirective> retrievalDirectives,
            List<String> promptHints
    ) {
        public AuditResult(SynthesizedCompoundRecord recordWithWarnings, List<String> warnings, boolean shouldResynthesize) {
            this(recordWithWarnings, warnings, shouldResynthesize, List.of(), List.of());
        }
    }

    public record RetrievalDirective(
            String reason,
            List<String> queries,
            String paradigmHint
    ) {}
}
