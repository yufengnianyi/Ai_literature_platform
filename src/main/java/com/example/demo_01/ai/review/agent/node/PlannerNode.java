package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.service.agent.PerPaperPlannerAgent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class PlannerNode implements NodeAction<PerPaperAgentState> {

    @Resource
    private PerPaperPlannerAgent perPaperPlannerAgent;

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        List<RetrievedChunk> seeds = state.seedChunks();
        UUID documentId = state.documentId();

        ArrayDeque<PerPaperAgentState.CompoundSpec> queue = perPaperPlannerAgent.buildCompoundQueue(documentId, seeds);
        return Map.of(
                PerPaperAgentState.COMPOUND_QUEUE, queue,
                PerPaperAgentState.LLM_CALLS, state.llmCalls() + 1,
                PerPaperAgentState.ITERATIONS, new HashMap<String, Integer>(),
                PerPaperAgentState.CTX_BY_COMPOUND, new HashMap<String, List<RetrievedChunk>>()
        );
    }
}
