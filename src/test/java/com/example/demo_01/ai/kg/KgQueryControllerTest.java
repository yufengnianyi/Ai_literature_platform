package com.example.demo_01.ai.kg;

import com.example.demo_01.ai.kg.api.KgQueryController;
import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphEdge;
import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphNode;
import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphView;
import com.example.demo_01.ai.kg.service.QuestionGraphQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KgQueryControllerTest {

    private QuestionGraphQueryService questionGraphQueryService;
    private KgQueryController controller;

    @BeforeEach
    void setUp() {
        questionGraphQueryService = mock(QuestionGraphQueryService.class);
        controller = new KgQueryController();
        ReflectionTestUtils.setField(controller, "questionGraphQueryService", questionGraphQueryService);
    }

    @Test
    void queryShouldReturnQuestionGraph() {
        QuestionGraphView graphView = new QuestionGraphView(
                "What does FLS2 do?",
                "READY",
                List.of("FLS2"),
                List.of(
                        new QuestionGraphNode("fls2", "FLS2", "GENE_OR_PROTEIN", true, 2, List.of("Plant immunity paper")),
                        new QuestionGraphNode("bik1", "BIK1", "GENE_OR_PROTEIN", false, 1, List.of())
                ),
                List.of(
                        new QuestionGraphEdge("fls2|ASSOCIATES_WITH|bik1", "fls2", "bik1", "ASSOCIATES_WITH")
                ),
                List.of("Plant immunity paper")
        );
        when(questionGraphQueryService.query("What does FLS2 do?")).thenReturn(graphView);

        var response = controller.query("What does FLS2 do?");

        assertEquals("READY", response.getData().status());
        assertEquals(2, response.getData().nodes().size());
        assertEquals("FLS2", response.getData().matchedEntities().get(0));
    }
}
