package com.example.demo_01.ai.evidence.eval;

import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.EvalReport;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.GoldAnchor;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.GoldDocumentQuestion;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.GoldRow;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.PredictedDocumentQuestion;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.PredictedRow;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceExtractionScorerTest {

    private EvidenceExtractionScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new EvidenceExtractionScorer();
        EvidenceProfileRegistry registry = new EvidenceProfileRegistry();
        MultiProfileOutputValidator validator = new MultiProfileOutputValidator();
        ReflectionTestUtils.setField(validator, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(scorer, "profileRegistry", registry);
        ReflectionTestUtils.setField(scorer, "outputValidator", validator);
    }

    @Test
    void scoresExactQ1MatchAsPerfect() {
        UUID docId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<String> cells = q1Cells("metalaxyl", "Phytophthora infestans", "mycelial growth assay",
                "EC50 0.12 ug/mL");
        GoldDocumentQuestion gold = new GoldDocumentQuestion(
                docId, "Q1", "SUPPORTED",
                List.of(new GoldRow(cells, List.of(new GoldAnchor("c1", "metalaxyl")))),
                "test", "gold-v1");
        PredictedDocumentQuestion predicted = new PredictedDocumentQuestion(
                docId, "Q1", "SUPPORTED",
                List.of(new PredictedRow(cells, List.of(new GoldAnchor("c1", "metalaxyl")), "VALID")));

        EvalReport report = scorer.score("unit-test", List.of(gold), List.of(predicted));

        assertEquals(1.0, report.overallRouting().f1(), 0.0001);
        assertEquals(1.0, report.overallRowStrict().f1(), 0.0001);
        assertEquals(1.0, report.questions().getFirst().documentRecall(), 0.0001);
        assertTrue(report.overallAnchorFaithfulness().precision() >= 0.99);
    }

    @Test
    void detectsMissedRowAsFalseNegative() {
        UUID docId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        List<String> cells = q1Cells("oxathiapiprolin", "Phytophthora capsici", "leaf disk",
                "EC50 0.01 ug/mL");
        GoldDocumentQuestion gold = new GoldDocumentQuestion(
                docId, "Q1", "SUPPORTED",
                List.of(new GoldRow(cells, List.of())),
                "test", "gold-v1");
        PredictedDocumentQuestion predicted = new PredictedDocumentQuestion(
                docId, "Q1", "SUPPORTED", List.of());

        EvalReport report = scorer.score("unit-test-fn", List.of(gold), List.of(predicted));
        assertEquals(0.0, report.overallRowStrict().recall(), 0.0001);
        assertEquals(1, report.overallRowStrict().falseNegatives());
    }

    private List<String> q1Cells(String compound, String pathogen, String assay, String activity) {
        List<String> headers = new EvidenceProfileRegistry().require("Q1").headers();
        List<String> cells = new ArrayList<>(Collections.nCopies(headers.size(), ""));
        cells.set(0, compound);
        cells.set(1, compound);
        cells.set(5, pathogen);
        cells.set(6, assay);
        cells.set(7, activity);
        return cells;
    }
}
