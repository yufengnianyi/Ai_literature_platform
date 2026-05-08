package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.AuditResult;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.CompoundSpec;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.RetrievalDirective;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class AuditNode implements NodeAction<PerPaperAgentState> {

    @Resource
    private ReviewProperties reviewProperties;

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        SynthesizedCompoundRecord profile = state.currentProfile().orElse(null);
        CompoundSpec compound = state.currentCompound().orElse(null);

        if (profile == null) {
            AuditResult result = new AuditResult(null, List.of("SYNTHESIS_FAILED"), false, List.of(), List.of());
            return Map.of(PerPaperAgentState.CURRENT_AUDIT, result);
        }

        List<String> warnings = new ArrayList<>();
        List<RetrievalDirective> directives = new ArrayList<>();
        List<String> promptHints = new ArrayList<>();

        boolean hasMic = false;
        boolean hasDoseGradient = false;
        boolean hasCytotoxicity = profile.safetyProfile() != null
                && !profile.safetyProfile().isBlank()
                && !"not reported".equalsIgnoreCase(profile.safetyProfile());

        if (profile.paradigmActivities() != null) {
            for (ParadigmActivityBlock pab : profile.paradigmActivities()) {
                if (pab.keyMetric() != null && pab.keyMetric().type() != null) {
                    String type = pab.keyMetric().type().toUpperCase();
                    if (type.contains("MIC") || type.contains("MFC") || type.contains("EC50") || type.contains("IC50")) {
                        hasMic = true;
                    }
                }
                if (pab.doseGradient() != null && !pab.doseGradient().isEmpty()) {
                    hasDoseGradient = true;
                }
            }
        }

        String compoundName = compound != null ? compound.canonicalName()
                : (profile.compoundName() != null ? profile.compoundName() : "unknown");

        if (!hasMic) {
            warnings.add("MIC_OMITTED");
            directives.add(new RetrievalDirective(
                    "MIC_OMITTED",
                    List.of(compoundName + " MIC", compoundName + " IC50", compoundName + " EC50"),
                    null
            ));
            promptHints.add("Look for MIC/MFC/EC50/IC50 values for " + compoundName);
        }

        if (!hasDoseGradient) {
            warnings.add("DOSE_GRADIENT_OMITTED");
            directives.add(new RetrievalDirective(
                    "DOSE_GRADIENT_OMITTED",
                    List.of(compoundName + " concentration", compoundName + " dose response"),
                    null
            ));
            promptHints.add("Include all tested concentrations and their effects for " + compoundName);
        }

        if (!hasCytotoxicity) {
            warnings.add("CYTOTOXICITY_OMITTED");
            directives.add(new RetrievalDirective(
                    "CYTOTOXICITY_OMITTED",
                    List.of(compoundName + " cytotoxicity", compoundName + " XTT", compoundName + " toxicity"),
                    "CYTOTOXICITY_XTT"
            ));
        }

        boolean shouldResynth = !directives.isEmpty();

        SynthesizedCompoundRecord withWarnings = new SynthesizedCompoundRecord(
                profile.compoundName(), profile.documentId(), profile.documentTitle(),
                profile.role(), profile.structureType(), profile.source(),
                profile.paradigmActivities(), profile.mechanismSummary(), profile.safetyProfile(),
                profile.comparisons(), profile.contextNote(), profile.targetOrganisms(),
                profile.confidence(), profile.reference(), profile.evidenceChunkIds(),
                warnings.isEmpty() ? profile.coverageWarnings() : warnings
        );

        AuditResult result = new AuditResult(withWarnings, warnings, shouldResynth, directives, promptHints);
        log.info("Audit for {}: {} warnings, shouldResynth={}", compoundName, warnings.size(), shouldResynth);

        Map<String, Object> updates = new HashMap<>();
        updates.put(PerPaperAgentState.CURRENT_AUDIT, result);

        String iterKey = compoundName;
        int iter = state.iterations().getOrDefault(iterKey, 0);
        int calls = state.llmCalls();
        boolean budgetExhausted = calls >= reviewProperties.getAgent().getMaxLlmCallsPerPaper()
                || iter >= reviewProperties.getAgent().getMaxIterationsPerCompound();
        boolean willRetrieve = shouldResynth && !budgetExhausted;

        if (!willRetrieve) {
            List<SynthesizedCompoundRecord> acc = new ArrayList<>(state.profiles());
            acc.add(withWarnings);
            updates.put(PerPaperAgentState.PROFILES, acc);
        }
        return updates;
    }
}
