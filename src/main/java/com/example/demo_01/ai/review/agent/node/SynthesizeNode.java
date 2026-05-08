package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.CompoundSpec;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.service.CompoundEvidenceSynthesizer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class SynthesizeNode implements NodeAction<PerPaperAgentState> {

    @Resource
    private CompoundEvidenceSynthesizer synthesizer;

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        CompoundSpec compound = state.currentCompound().orElseThrow();
        UUID documentId = state.documentId();
        List<RetrievedChunk> seeds = state.seedChunks();
        Map<String, List<RetrievedChunk>> ctxByCompound = state.ctxByCompound();

        List<RetrievedChunk> compoundChunks = ctxByCompound.getOrDefault(compound.canonicalName(), List.of());
        List<RetrievedChunk> allChunks = new ArrayList<>(seeds);
        allChunks.addAll(compoundChunks);

        List<ExtractedEvidence> mockEvidence = buildEvidenceFromChunks(allChunks, compound, documentId);

        List<String> hints = state.currentAudit()
                .filter(a -> a.promptHints() != null)
                .map(PerPaperAgentState.AuditResult::promptHints)
                .orElse(List.of());

        try {
            Map<UUID, DocumentKnowledgeContext> kc = state.knowledgeContextsMap();
            String groupKey = documentId + "::" + (compound.canonicalName() != null
                    ? compound.canonicalName().toLowerCase(Locale.ROOT) : "unknown");

            List<SynthesizedCompoundRecord> results;
            if (!hints.isEmpty()) {
                SynthesizedCompoundRecord one = synthesizer.synthesizeWithHints(
                        groupKey, mockEvidence, kc, hints);
                results = one != null ? List.of(one) : List.of();
            } else {
                results = synthesizer.synthesize(mockEvidence, kc);
            }

            SynthesizedCompoundRecord profile = results.stream()
                    .filter(r -> r.compoundName() != null &&
                            r.compoundName().equalsIgnoreCase(compound.canonicalName()))
                    .findFirst()
                    .orElse(results.isEmpty() ? null : results.get(0));

            String key = compound.canonicalName();
            Map<String, Integer> iterations = new HashMap<>(state.iterations());
            iterations.merge(key, 1, Integer::sum);

            log.info("Synthesized profile for {} (iter={})", compound.canonicalName(), iterations.get(key));
            Map<String, Object> updates = new HashMap<>();
            updates.put(PerPaperAgentState.CURRENT_PROFILE, profile);
            updates.put(PerPaperAgentState.LLM_CALLS, state.llmCalls() + 1);
            updates.put(PerPaperAgentState.ITERATIONS, iterations);
            return updates;
        } catch (Exception e) {
            log.warn("Synthesis failed for compound {}: {}", compound.canonicalName(), e.getMessage());
            Map<String, Object> updates = new HashMap<>();
            updates.put(PerPaperAgentState.CURRENT_PROFILE, null);
            updates.put(PerPaperAgentState.LLM_CALLS, state.llmCalls() + 1);
            return updates;
        }
    }

    private List<ExtractedEvidence> buildEvidenceFromChunks(List<RetrievedChunk> chunks,
                                                            CompoundSpec compound,
                                                            UUID documentId) {
        List<ExtractedEvidence> evidence = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.text() == null) continue;
            String textLower = chunk.text().toLowerCase();
            boolean relevant = compound.localLabels().stream()
                    .anyMatch(label -> textLower.contains(label.toLowerCase()));
            if (!relevant && compound.canonicalName() != null) {
                relevant = textLower.contains(compound.canonicalName().toLowerCase());
            }
            if (relevant) {
                evidence.add(new ExtractedEvidence(
                        chunk.chunkId(),
                        documentId != null ? documentId.toString() : null,
                        chunk.documentTitle(),
                        null, null, null,
                        new TypedEntities(
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                                List.of(compound.canonicalName()), List.of(), List.of(),
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                                compound.localLabels(), List.of(compound.canonicalName()),
                                List.of(), List.of(), List.of()),
                        List.of(compound.canonicalName()),
                        "EXPERIMENTAL",
                        0.7,
                        chunk.text().length() > 500 ? chunk.text().substring(0, 500) : chunk.text(),
                        null
                ));
            }
        }
        return evidence;
    }
}
