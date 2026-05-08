package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.AuditResult;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.RetrievalDirective;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.service.HighRecallRetrievalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class RetrieveNode implements NodeAction<PerPaperAgentState> {

    @Resource
    private HighRecallRetrievalService retrievalService;

    @Resource
    private ReviewProperties reviewProperties;

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        UUID documentId = state.documentId();
        AuditResult audit = state.currentAudit().orElse(null);
        String compoundName = state.currentCompound()
                .map(PerPaperAgentState.CompoundSpec::canonicalName).orElse("unknown");

        List<String> queries = new ArrayList<>();
        if (audit != null && audit.retrievalDirectives() != null) {
            for (RetrievalDirective d : audit.retrievalDirectives()) {
                if (d.queries() != null) queries.addAll(d.queries());
            }
        }
        if (queries.isEmpty()) {
            queries.add(compoundName + " activity");
        }

        int k = reviewProperties.getAgent().getRetrieverKPerDirective();
        try {
            List<RetrievedChunk> retrieved = retrievalService.searchWithinDocument(documentId, queries, k);
            Map<String, List<RetrievedChunk>> ctx = new HashMap<>(state.ctxByCompound());
            List<RetrievedChunk> existing = ctx.getOrDefault(compoundName, new ArrayList<>());
            List<RetrievedChunk> merged = new ArrayList<>(existing);
            Set<String> existingIds = new HashSet<>();
            existing.forEach(c -> existingIds.add(c.chunkId()));
            for (RetrievedChunk c : retrieved) {
                if (!existingIds.contains(c.chunkId())) {
                    merged.add(c);
                }
            }
            ctx.put(compoundName, merged);
            log.info("Retrieved {} new chunks for compound {} in doc {}", retrieved.size(), compoundName, documentId);
            return Map.of(PerPaperAgentState.CTX_BY_COMPOUND, ctx);
        } catch (Exception e) {
            log.warn("Retrieval failed for compound {} in doc {}: {}", compoundName, documentId, e.getMessage());
            return Map.of();
        }
    }
}
