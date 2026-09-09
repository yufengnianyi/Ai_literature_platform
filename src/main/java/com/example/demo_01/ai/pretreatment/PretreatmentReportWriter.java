package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentResult;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.QualityStatus;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.RelevanceDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class PretreatmentReportWriter {

    @Resource
    private ObjectMapper objectMapper;

    public void write(Path outputDir, PretreatmentRunSummary summary, List<PretreatmentDocumentResult> results) {
        try {
            Files.createDirectories(outputDir);
            writeJsonl(outputDir.resolve("results.jsonl"), results);
            writeCsv(outputDir.resolve("results.csv"), results);
            writeIds(outputDir.resolve("accepted-document-ids.txt"), results, FinalDecision.ACCEPTED);
            writeIds(outputDir.resolve("rejected-document-ids.txt"), results, FinalDecision.REJECTED);
            writeIds(outputDir.resolve("skipped-document-ids.txt"), results, FinalDecision.SKIPPED);
            writeSummary(outputDir.resolve("summary.md"), summary, results);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write PreTreatment report: " + outputDir, e);
        }
    }

    public Path resolveApplyRunDir(Path outputRoot, String applyRunId) {
        if (applyRunId != null && !applyRunId.isBlank()) {
            return outputRoot.resolve(applyRunId).toAbsolutePath().normalize();
        }
        try {
            if (!Files.isDirectory(outputRoot)) {
                throw new IllegalStateException("PreTreatment output root does not exist: " + outputRoot);
            }
            return Files.list(outputRoot)
                    .filter(Files::isDirectory)
                    .max(Comparator.comparing(path -> path.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("No PreTreatment run output found under: " + outputRoot))
                    .toAbsolutePath()
                    .normalize();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve latest PreTreatment output under: " + outputRoot, e);
        }
    }

    public List<UUID> readRejectedIds(Path runDir) {
        Path path = runDir.resolve("rejected-document-ids.txt");
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Rejected document id file not found: " + path);
        }
        try {
            return Files.readAllLines(path).stream()
                    .filter(line -> line != null && !line.isBlank())
                    .map(String::trim)
                    .map(UUID::fromString)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read rejected ids: " + path, e);
        }
    }

    private void writeJsonl(Path path, List<PretreatmentDocumentResult> results) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (PretreatmentDocumentResult result : results) {
            builder.append(objectMapper.writeValueAsString(result)).append('\n');
        }
        Files.writeString(path, builder.toString());
    }

    private void writeCsv(Path path, List<PretreatmentDocumentResult> results) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("document_id,title,journal,doi,quality_decision,quality_status,relevance_decision,relevance_source,reject_reason_code,quality_metrics,llm_label,final_decision,reason\n");
        for (PretreatmentDocumentResult result : results) {
            builder.append(csv(result.documentId() == null ? "" : result.documentId().toString())).append(',')
                    .append(csv(result.title())).append(',')
                    .append(csv(result.journal())).append(',')
                    .append(csv(result.doi())).append(',')
                    .append(csv(name(result.qualityDecision()))).append(',')
                    .append(csv(name(result.qualityStatus()))).append(',')
                    .append(csv(name(result.relevanceDecision()))).append(',')
                    .append(csv(name(result.relevanceSource()))).append(',')
                    .append(csv(result.rejectReasonCode())).append(',')
                    .append(csv(json(result.qualityMetrics()))).append(',')
                    .append(csv(name(result.llmLabel()))).append(',')
                    .append(csv(name(result.finalDecision()))).append(',')
                    .append(csv(result.reason())).append('\n');
        }
        Files.writeString(path, builder.toString());
    }

    private void writeIds(Path path, List<PretreatmentDocumentResult> results, FinalDecision decision) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (PretreatmentDocumentResult result : results) {
            if (result.finalDecision() == decision && result.documentId() != null) {
                builder.append(result.documentId()).append('\n');
            }
        }
        Files.writeString(path, builder.toString());
    }

    private void writeSummary(Path path, PretreatmentRunSummary summary, List<PretreatmentDocumentResult> results) throws IOException {
        String markdown = """
                # PreTreatment Summary

                Run ID: `%s`

                Mode: `%s`

                Dry run: `%s`

                Output: `%s`

                | Metric | Count |
                | --- | ---: |
                | Total documents | %d |
                | Processed documents | %d |
                | Accepted | %d |
                | Rejected | %d |
                | Skipped | %d |
                | Vectors removed | %d |

                ## Screening Funnel

                | Layer | Pass | Reject/Stop |
                | --- | ---: | ---: |
                | Full text ready | %d | %d |
                | Relevance decision | %d | %d |
                """.formatted(
                summary.runId(),
                summary.mode(),
                summary.dryRun(),
                summary.outputDir(),
                summary.totalArtifacts(),
                summary.processedDocuments(),
                summary.acceptedDocuments(),
                summary.rejectedDocuments(),
                summary.skippedDocuments(),
                summary.vectorsRemoved(),
                countQuality(results, QualityStatus.FULL_TEXT_READY),
                countNotQuality(results, QualityStatus.FULL_TEXT_READY),
                count(results, FinalDecision.ACCEPTED),
                countPostQualityNotAccepted(results));
        Files.writeString(path, markdown);
    }

    private String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "";
        }
    }

    private String name(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private int count(List<PretreatmentDocumentResult> results, FinalDecision decision) {
        return (int) results.stream().filter(result -> result.finalDecision() == decision).count();
    }

    private int countQuality(List<PretreatmentDocumentResult> results, QualityStatus status) {
        return (int) results.stream().filter(result -> result.qualityStatus() == status).count();
    }

    private int countNotQuality(List<PretreatmentDocumentResult> results, QualityStatus status) {
        return (int) results.stream().filter(result -> result.qualityStatus() != status).count();
    }

    private int countPostQualityNotAccepted(List<PretreatmentDocumentResult> results) {
        return (int) results.stream()
                .filter(result -> result.relevanceDecision() != RelevanceDecision.NOT_EVALUATED)
                .filter(result -> result.finalDecision() != FinalDecision.ACCEPTED)
                .count();
    }
}
