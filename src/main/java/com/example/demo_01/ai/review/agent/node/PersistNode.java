package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.model.ReviewModels.SynthesizedCompoundRecord;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class PersistNode implements NodeAction<PerPaperAgentState> {

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        List<SynthesizedCompoundRecord> profiles = state.profiles();
        if (profiles == null || profiles.isEmpty()) {
            log.info("No profiles to persist");
            return Map.of();
        }
        // DB insert is done in ReviewPipelineService after the graph returns (single source of truth).
        log.info("Agent persist stage done for task {} ({} profiles)", state.taskId(), profiles.size());
        return Map.of();
    }
}
