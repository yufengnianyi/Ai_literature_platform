package com.example.demo_01.ai.review.agent;

import com.example.demo_01.ai.review.agent.PerPaperAgentState.AuditResult;
import com.example.demo_01.ai.review.agent.PerPaperAgentState.CompoundSpec;
import com.example.demo_01.ai.review.agent.node.*;
import com.example.demo_01.ai.review.config.ReviewProperties;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Configuration
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class PerPaperGraphFactory {

    @Bean
    public CompiledGraph<PerPaperAgentState> perPaperGraph(
            PlannerNode planner,
            PickNextCompoundNode pick,
            SynthesizeNode synth,
            AuditNode audit,
            RetrieveNode retrieve,
            CompareNode compare,
            PersistNode persist,
            ReviewProperties props) throws GraphStateException {

        var graph = new StateGraph<>(PerPaperAgentState.SCHEMA, PerPaperAgentState::new)
                .addNode("plan", node_async(planner))
                .addNode("pick", node_async(pick))
                .addNode("synth", node_async(synth))
                .addNode("audit", node_async(audit))
                .addNode("retrieve", node_async(retrieve))
                .addNode("compare", node_async(compare))
                .addNode("persist", node_async(persist))
                .addEdge(START, "plan")
                .addEdge("plan", "pick")
                .addConditionalEdges("pick", edge_async(this::routeAfterPick),
                        Map.of("synth", "synth", "compare", "compare"))
                .addEdge("synth", "audit")
                .addConditionalEdges("audit", edge_async(state -> routeAfterAudit(state, props)),
                        Map.of("retrieve", "retrieve", "pick", "pick"))
                .addEdge("retrieve", "synth")
                .addEdge("compare", "persist")
                .addEdge("persist", END);

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .build());
    }

    private String routeAfterPick(PerPaperAgentState state) {
        CompoundSpec current = state.currentCompound().orElse(null);
        if (current == null) {
            return "compare";
        }
        return "synth";
    }

    private String routeAfterAudit(PerPaperAgentState state, ReviewProperties props) {
        AuditResult audit = state.currentAudit().orElse(null);
        if (audit == null) {
            return "pick";
        }
        int calls = state.llmCalls();
        String currentKey = state.currentCompound().map(CompoundSpec::canonicalName).orElse("");
        int iter = state.iterations().getOrDefault(currentKey, 0);
        boolean coverageOk = audit.retrievalDirectives() == null || audit.retrievalDirectives().isEmpty();
        boolean budgetExhausted = calls >= props.getAgent().getMaxLlmCallsPerPaper()
                || iter >= props.getAgent().getMaxIterationsPerCompound();
        return (coverageOk || budgetExhausted) ? "pick" : "retrieve";
    }
}
