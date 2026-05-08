package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.service.WithinPaperComparatorAgent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class CompareNode implements NodeAction<PerPaperAgentState> {

    @Resource
    private WithinPaperComparatorAgent withinPaperComparatorAgent;

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        List<SynthesizedCompoundRecord> profiles = state.profiles();
        if (profiles == null || profiles.isEmpty()) {
            log.info("Skipping comparison: no profiles");
            return Map.of();
        }
        if (profiles.size() < 2) {
            log.info("Skipping comparison: less than 2 profiles");
            return Map.of();
        }
        try {
            List<SynthesizedCompoundRecord> enhanced = withinPaperComparatorAgent.compare(profiles);
            Map<String, Object> updates = new HashMap<>();
            updates.put(PerPaperAgentState.PROFILES, enhanced);
            updates.put(PerPaperAgentState.LLM_CALLS, state.llmCalls() + 1);
            return updates;
        } catch (Exception e) {
            log.warn("Comparison failed: {}", e.getMessage());
            return Map.of(PerPaperAgentState.LLM_CALLS, state.llmCalls());
        }
    }
}
