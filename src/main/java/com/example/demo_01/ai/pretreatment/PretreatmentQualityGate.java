package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityStatus;
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
        String abstractText = metadata == null ? null : metadata.abstractText();
        Map<String, Object> metrics = metrics(chunks);
        int chunkCount = intMetric(metrics, "chunkCount");
        int totalTextChars = intMetric(metrics, "totalTextChars");
        double replacementCharRatio = doubleMetric(metrics, "replacementCharRatio");
        double shortLineRatio = doubleMetric(metrics, "shortLineRatio");
        if (chunkCount >= properties.getMinChunks()
                && totalTextChars >= properties.getMinTotalTextChars()
                && replacementCharRatio <= properties.getMaxReplacementCharRatio()
                && shortLineRatio <= properties.getMaxShortLineRatio()) {
            return new QualityResult(QualityDecision.PASS, QualityStatus.FULL_TEXT_READY, metrics,
                    "", "Full text is ready for evidence extraction.");
        }
        if (!isBlank(title) || !isBlank(abstractText)) {
            return new QualityResult(QualityDecision.REJECT, QualityStatus.METADATA_READY, metrics,
                    fullTextReason(chunkCount, totalTextChars, replacementCharRatio, shortLineRatio, properties),
                    "Metadata is available; full text needs recovery before evidence extraction.");
        }
        if (totalTextChars > 0) {
            return new QualityResult(QualityDecision.REJECT, QualityStatus.METADATA_READY, metrics,
                    fullTextReason(chunkCount, totalTextChars, replacementCharRatio, shortLineRatio, properties),
                    "Readable text is available; metadata needs recovery before relevance review.");
        }
        return new QualityResult(QualityDecision.REJECT, QualityStatus.UNUSABLE, metrics,
                "MISSING_USABLE_CONTENT", "Title, abstract, and readable text are unavailable.");
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

    private String fullTextReason(int chunkCount,
                                  int totalTextChars,
                                  double replacementCharRatio,
                                  double shortLineRatio,
                                  PretreatmentProperties.Quality properties) {
        if (chunkCount < properties.getMinChunks()) {
            return "LOW_CHUNK_COUNT";
        }
        if (totalTextChars < properties.getMinTotalTextChars()) {
            return "LOW_TEXT_COVERAGE";
        }
        if (replacementCharRatio > properties.getMaxReplacementCharRatio()) {
            return "HIGH_GARBLED_TEXT_RATIO";
        }
        if (shortLineRatio > properties.getMaxShortLineRatio()) {
            return "HIGH_SHORT_LINE_RATIO";
        }
        return "FULL_TEXT_NOT_READY";
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
            QualityStatus status,
            Map<String, Object> metrics,
            String rejectReasonCode,
            String reason
    ) {
    }
}
