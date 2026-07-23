package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceRetrievalAgentTest {

    private EvidenceRetrievalAgent agent;
    private EvidenceProperties properties;

    @BeforeEach
    void setUp() {
        agent = new EvidenceRetrievalAgent();
        properties = new EvidenceProperties();
        properties.getAgents().getRetriever().setOnDemandEnabled(false);
        ReflectionTestUtils.setField(agent, "properties", properties);
        ReflectionTestUtils.setField(agent, "objectMapper", new ObjectMapper());
    }

    @Test
    void fallsBackToStaticBatchesWhenOnDemandDisabled() {
        EvidenceProfileRegistry.EvidenceProfile profile =
                new EvidenceProfileRegistry().require("Q1");
        List<EvidenceChunk> chunks = List.of(
                new EvidenceChunk("c1", "Abstract", 1, 1, 1, "oomycete compound assay"),
                new EvidenceChunk("c2", "Results", 2, 1, 1, "EC50 values for metalaxyl"));
        List<List<EvidenceChunk>> batches = agent.modelBatches(profile, chunks);
        assertFalse(batches.isEmpty());
        assertEquals(2, batches.getFirst().size());
    }
}
