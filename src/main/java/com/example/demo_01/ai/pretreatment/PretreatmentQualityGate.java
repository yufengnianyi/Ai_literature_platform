package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityDecision;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PretreatmentQualityGate {

    public QualityResult evaluate(RagDocumentMetadata metadata,
                                  List<RagChunk> chunks,
                                  PretreatmentProperties.Quality properties) {
        String title = metadata == null ? null : metadata.title();
        Map<String, Object> metrics = metrics(chunks);
        if (isBlank(title)) {
            return new QualityResult(QualityDecision.REJECT, metrics, "MISSING_TITLE", "Missing title.");
        }
        int chunkCount = intMetric(metrics, "chunkCount");
        int totalTextChars = intMetric(metrics, "totalTextChars");
        double replacementCharRatio = doubleMetric(metrics, "replacementCharRatio");
        double shortLineRatio = doubleMetric(metrics, "shortLineRatio");
        if (chunkCount < properties.getMinChunks()) {
            return new QualityResult(QualityDecision.REJECT, metrics, "LOW_CHUNK_COUNT",
                    "PDF conversion quality is too low: chunk count below threshold.");
        }
        if (totalTextChars < properties.getMinTotalTextChars()) {
            return new QualityResult(QualityDecision.REJECT, metrics, "LOW_TEXT_COVERAGE",
                    "PDF conversion quality is too low: extracted text below threshold.");
        }
        if (replacementCharRatio > properties.getMaxReplacementCharRatio()) {
            return new QualityResult(QualityDecision.REJECT, metrics, "HIGH_GARBLED_TEXT_RATIO",
                    "PDF conversion quality is too low: replacement/garbled character ratio above threshold.");
        }
        if (shortLineRatio > properties.getMaxShortLineRatio()) {
            return new QualityResult(QualityDecision.REJECT, metrics, "HIGH_SHORT_LINE_RATIO",
                    "PDF conversion quality is too low: abnormal short line ratio above threshold.");
        }
        return new QualityResult(QualityDecision.PASS, metrics, "", "Quality gate passed.");
    }

    Map<String, Object> metrics(List<RagChunk> chunks) {
        int chunkCount = chunks == null ? 0 : chunks.size();
        int totalTextChars = 0;
        int replacementChars = 0;
        int lineCount = 0;
        int shortLines = 0;
        if (chunks != null) {
            for (RagChunk chunk : chunks) {
                String text = chunk.text();
                if (text == null || text.isBlank()) {
                    continue;
                }
                totalTextChars += text.length();
                replacementChars += countReplacementChars(text);
                String[] lines = text.split("\\R");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    lineCount++;
                    if (trimmed.length() < 20) {
                        shortLines++;
                    }
                }
            }
        }
        double averageChunkChars = chunkCount == 0 ? 0.0 : (double) totalTextChars / chunkCount;
        double replacementCharRatio = totalTextChars == 0 ? 0.0 : (double) replacementChars / totalTextChars;
        double shortLineRatio = lineCount == 0 ? 0.0 : (double) shortLines / lineCount;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("chunkCount", chunkCount);
        metrics.put("totalTextChars", totalTextChars);
        metrics.put("averageChunkChars", round(averageChunkChars));
        metrics.put("replacementCharRatio", round(replacementCharRatio));
        metrics.put("shortLineRatio", round(shortLineRatio));
        return metrics;
    }

    private int countReplacementChars(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD' || c == '?') {
                count++;
            }
        }
        return count;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int intMetric(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double doubleMetric(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public record QualityResult(
            QualityDecision decision,
            Map<String, Object> metrics,
            String rejectReasonCode,
            String reason
    ) {
    }
}
