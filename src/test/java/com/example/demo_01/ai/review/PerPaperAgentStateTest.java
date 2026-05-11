package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PerPaperAgentStateTest {

    @Test
    void schemaDefaultsShouldNotEmitNullValuesDuringGraphInitialization() {
        AgentStateFactory<PerPaperAgentState> factory = PerPaperAgentState::new;

        Map<String, Object> defaults = assertDoesNotThrow(
                () -> factory.initialDataFromSchema(PerPaperAgentState.SCHEMA));

        assertFalse(defaults.containsKey(PerPaperAgentState.DOCUMENT_ID));
        assertFalse(defaults.containsKey(PerPaperAgentState.CURRENT_COMPOUND));
        assertFalse(defaults.containsKey(PerPaperAgentState.CURRENT_PROFILE));
        assertFalse(defaults.containsKey(PerPaperAgentState.CURRENT_AUDIT));
        assertFalse(defaults.containsKey(PerPaperAgentState.TASK_ID));
        assertFalse(defaults.containsKey(PerPaperAgentState.REVIEW_QUESTION));
        assertFalse(defaults.containsKey(PerPaperAgentState.PAPER_EVIDENCE_TABLE));
    }
}
