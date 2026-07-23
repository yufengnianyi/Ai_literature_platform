package com.example.demo_01.ai.evidence.eval;

import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.EvalReport;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.FieldMetrics;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.MetricSlice;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.QuestionMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvidenceEvalReportService {

    @Resource
    private ObjectMapper objectMapper;

    public Path writeJson(Path outputPath, EvalReport report) throws IOException {
        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), report);
        return outputPath;
    }

    public Path writeMarkdown(Path outputPath, EvalReport report) throws IOException {
        Files.createDirectories(outputPath.getParent());
        StringBuilder md = new StringBuilder();
        md.append("# Evidence extraction evaluation\n\n");
        md.append("- runId: ").append(report.runId()).append('\n');
        md.append("- overall routing F1: ").append(report.overallRouting().f1()).append('\n');
        md.append("- overall row-strict F1: ").append(report.overallRowStrict().f1()).append('\n');
        md.append("- overall row-lenient F1: ").append(report.overallRowLenient().f1()).append('\n');
        md.append("- overall anchor faithfulness: ")
                .append(report.overallAnchorFaithfulness().precision()).append("\n\n");

        md.append("| Question | Routing F1 | Row strict F1 | Row lenient F1 | Doc recall | Anchor |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (QuestionMetrics q : report.questions()) {
            md.append("| ").append(q.questionId())
                    .append(" | ").append(q.routing().f1())
                    .append(" | ").append(q.rowStrict().f1())
                    .append(" | ").append(q.rowLenient().f1())
                    .append(" | ").append(q.documentRecall())
                    .append(" | ").append(q.anchorFaithfulness().precision())
                    .append(" |\n");
        }
        md.append("\n## Field accuracy (per question)\n");
        for (QuestionMetrics q : report.questions()) {
            md.append("\n### ").append(q.questionId()).append('\n');
            md.append("| Field | Kind | Accuracy | Correct | Partial | Wrong | Missing |\n");
            md.append("| --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
            for (FieldMetrics field : q.fields()) {
                md.append("| ").append(field.fieldName())
                        .append(" | ").append(field.fieldKind())
                        .append(" | ").append(field.accuracy())
                        .append(" | ").append(field.correct())
                        .append(" | ").append(field.partial())
                        .append(" | ").append(field.wrong())
                        .append(" | ").append(field.missing())
                        .append(" |\n");
            }
        }
        Files.writeString(outputPath, md.toString(), StandardCharsets.UTF_8);
        return outputPath;
    }

    public Map<String, Object> toSummaryMap(EvalReport report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", report.runId());
        summary.put("overall", Map.of(
                "routing", sliceMap(report.overallRouting()),
                "rowStrict", sliceMap(report.overallRowStrict()),
                "rowLenient", sliceMap(report.overallRowLenient()),
                "anchorFaithfulness", sliceMap(report.overallAnchorFaithfulness())));
        List<Map<String, Object>> questions = new ArrayList<>();
        for (QuestionMetrics q : report.questions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", q.questionId());
            item.put("routing", sliceMap(q.routing()));
            item.put("rowStrict", sliceMap(q.rowStrict()));
            item.put("rowLenient", sliceMap(q.rowLenient()));
            item.put("documentRecall", q.documentRecall());
            item.put("anchorFaithfulness", sliceMap(q.anchorFaithfulness()));
            questions.add(item);
        }
        summary.put("questions", questions);
        return summary;
    }

    private Map<String, Object> sliceMap(MetricSlice slice) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", slice.name());
        map.put("precision", slice.precision());
        map.put("recall", slice.recall());
        map.put("f1", slice.f1());
        map.put("tp", slice.truePositives());
        map.put("fp", slice.falsePositives());
        map.put("fn", slice.falseNegatives());
        return map;
    }
}
