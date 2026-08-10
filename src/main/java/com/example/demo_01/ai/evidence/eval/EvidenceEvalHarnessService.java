package com.example.demo_01.ai.evidence.eval;

import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.EvalReport;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.GoldAnchor;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.GoldDocumentQuestion;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.PredictedDocumentQuestion;
import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.PredictedRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.GenericEvidenceRecord;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.QuestionMatchRecord;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedAnchor;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads gold JSONL + predicted batch rows and writes L0-L3 evaluation reports.
 */
@Service
public class EvidenceEvalHarnessService {

    @Resource
    private EvidenceExtractionScorer scorer;

    @Resource(name = "evidenceEvalReportService")
    private EvidenceEvalReportService reportService;

    @Resource
    private MultiProfileEvidenceRepository repository;

    @Resource
    private ObjectMapper objectMapper;

    public EvalReport evaluateBatch(UUID batchId, Path goldJsonl, Path outputDir) throws Exception {
        List<GoldDocumentQuestion> gold = loadGold(goldJsonl);
        List<PredictedDocumentQuestion> predicted = loadPredicted(batchId);
        EvalReport report = scorer.score(batchId.toString(), gold, predicted);
        Files.createDirectories(outputDir);
        reportService.writeJson(outputDir.resolve("eval-report.json"), report);
        reportService.writeMarkdown(outputDir.resolve("eval-report.md"), report);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputDir.resolve("eval-summary.json").toFile(),
                        reportService.toSummaryMap(report));
        return report;
    }

    public List<GoldDocumentQuestion> loadGold(Path goldJsonl) throws Exception {
        List<GoldDocumentQuestion> items = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(goldJsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                items.add(objectMapper.readValue(line, GoldDocumentQuestion.class));
            }
        }
        return List.copyOf(items);
    }

    public List<PredictedDocumentQuestion> loadPredicted(UUID batchId) {
        List<QuestionMatchRecord> matches = repository.findAllMatches(batchId);
        List<GenericEvidenceRecord> evidence = repository.findAllEvidence(batchId);
        Map<String, PredictedDocumentQuestion> byKey = new LinkedHashMap<>();
        for (QuestionMatchRecord match : matches) {
            String key = match.documentId() + "\u001f" + match.questionId();
            byKey.put(key, new PredictedDocumentQuestion(
                    match.documentId(),
                    match.questionId(),
                    match.classificationStatus().name(),
                    new ArrayList<>()));
        }
        for (GenericEvidenceRecord record : evidence) {
            String key = record.documentId() + "\u001f" + record.questionId();
            PredictedDocumentQuestion existing = byKey.computeIfAbsent(key, ignored ->
                    new PredictedDocumentQuestion(
                            record.documentId(), record.questionId(),
                            record.classificationStatus().name(), new ArrayList<>()));
            List<PredictedRow> rows = new ArrayList<>(existing.rows());
            rows.add(new PredictedRow(
                    record.cells(),
                    record.anchors().stream()
                            .map(this::toGoldAnchor)
                            .toList(),
                    record.validationStatus() == null ? null : record.validationStatus().name()));
            byKey.put(key, new PredictedDocumentQuestion(
                    existing.documentId(), existing.questionId(),
                    existing.classification(), rows));
        }
        return List.copyOf(byKey.values());
    }

    private GoldAnchor toGoldAnchor(ValidatedAnchor anchor) {
        return new GoldAnchor(anchor.chunkId(), anchor.exactQuote());
    }
}
