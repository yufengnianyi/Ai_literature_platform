package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiProfileOutputValidatorTest {

    private MultiProfileOutputValidator validator;
    private EvidenceProfileRegistry registry;

    @BeforeEach
    void setUp() {
        validator = new MultiProfileOutputValidator();
        ReflectionTestUtils.setField(validator, "objectMapper", new ObjectMapper());
        registry = new EvidenceProfileRegistry();
    }

    @Test
    void strictEvidenceValidationStillRequiresAnchorsForNonQ1Flow() {
        List<String> cells = new ArrayList<>(
                java.util.Collections.nCopies(registry.require("Q2").headers().size(), ""));
        cells.set(0, "effector");
        String raw = "{\"rows\":[{\"cells\":" + toJson(cells) + ",\"anchors\":[]}]}";

        assertThrows(IllegalArgumentException.class,
                () -> validator.parseAndValidateEvidence(raw, registry.require("Q2"), List.of(chunk())));
    }

    private String toJson(List<String> cells) {
        try {
            return new ObjectMapper().writeValueAsString(cells);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private EvidenceChunk chunk() {
        return new EvidenceChunk("c1", "Results", 1, 1, 1,
                "effector evidence text", "body", "document.tei.xml");
    }
}
