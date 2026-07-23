package com.example.demo_01.ai.evidence.eval;

import java.util.List;
import java.util.UUID;

public final class EvidenceEvalModels {

    private EvidenceEvalModels() {
    }

    public record GoldDocumentQuestion(
            UUID documentId,
            String questionId,
            String classification,
            List<GoldRow> goldRows,
            String annotator,
            String version
    ) {
    }

    public record GoldRow(
            List<String> cells,
            List<GoldAnchor> anchors
    ) {
    }

    public record GoldAnchor(
            String chunkId,
            String exactQuote
    ) {
    }

    public record PredictedDocumentQuestion(
            UUID documentId,
            String questionId,
            String classification,
            List<PredictedRow> rows
    ) {
    }

    public record PredictedRow(
            List<String> cells,
            List<GoldAnchor> anchors,
            String validationStatus
    ) {
    }

    public record MetricSlice(
            String name,
            double precision,
            double recall,
            double f1,
            int truePositives,
            int falsePositives,
            int falseNegatives
    ) {
    }

    public record FieldMetrics(
            String fieldName,
            String fieldKind,
            double accuracy,
            int correct,
            int partial,
            int wrong,
            int missing,
            int total
    ) {
    }

    public record QuestionMetrics(
            String questionId,
            MetricSlice routing,
            MetricSlice rowStrict,
            MetricSlice rowLenient,
            List<FieldMetrics> fields,
            MetricSlice anchorFaithfulness,
            double documentRecall
    ) {
    }

    public record EvalReport(
            String runId,
            List<QuestionMetrics> questions,
            MetricSlice overallRouting,
            MetricSlice overallRowStrict,
            MetricSlice overallRowLenient,
            MetricSlice overallAnchorFaithfulness
    ) {
    }
}
