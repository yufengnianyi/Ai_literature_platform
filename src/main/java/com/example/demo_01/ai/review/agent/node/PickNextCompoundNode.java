package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.CompoundSpec;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class PickNextCompoundNode implements NodeAction<PerPaperAgentState> {

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        ArrayDeque<CompoundSpec> queue = state.compoundQueue();
        if (queue == null || queue.isEmpty()) {
            log.info("No more compounds in queue, routing to compare");
            Map<String, Object> result = new java.util.HashMap<>();
            result.put(PerPaperAgentState.CURRENT_COMPOUND, null);
            result.put(PerPaperAgentState.CURRENT_AUDIT, null);
            return result;
        }
        CompoundSpec next = queue.poll();
        log.info("Picking next compound: {}", next.canonicalName());
        Map<String, Object> result = new java.util.HashMap<>();
        result.put(PerPaperAgentState.CURRENT_COMPOUND, next);
        result.put(PerPaperAgentState.COMPOUND_QUEUE, queue);
        result.put(PerPaperAgentState.CURRENT_AUDIT, null);
        return result;
    }
}
