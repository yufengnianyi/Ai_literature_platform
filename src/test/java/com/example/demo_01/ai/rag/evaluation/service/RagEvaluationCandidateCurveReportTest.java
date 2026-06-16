package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.rag.evaluation.config.RagEvaluationProperties;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.JudgmentLabel;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.MetricSlice;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RagEvaluationDocumentJudgment;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RagEvaluationMetrics;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RagEvaluationRetrievalHit;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.RetrievalRoute;
import com.example.demo_01.ai.rag.evaluation.repository.RagEvaluationRepository;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@SpringBootTest
class RagEvaluationCandidateCurveReportTest {

    private static final Path LATEST_RUN_FILE = Path.of(
            "data", "rag-evaluation", "large-goldset-latest.txt");
    private static final List<Integer> CANDIDATE_COUNTS = List.of(50, 100, 200, 500);
    private static final List<String> CUTS = List.of("@10", "@20", "@50", "@100", "@all");

    @Resource
    private RagEvaluationService evaluationService;

    @Resource
    private RagEvaluationRepository evaluationRepository;

    @Resource
    private RagEvaluationMetricsService metricsService;

    @Resource
    private RagEvaluationProperties properties;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    void generateCandidateCurveReport() throws Exception {
        UUID experimentId = resolveExperimentId();
        List<RagEvaluationDocumentJudgment> judgments = evaluationRepository.findJudgments(experimentId);
        if (judgments.isEmpty()) {
            throw new IllegalStateException("No judgments found for " + experimentId);
        }
        Set<UUID> judgedDocumentIds = judgments.stream()
                .map(RagEvaluationDocumentJudgment::documentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> queries = distinctQueries(List.of(
                findQueries(experimentId, RetrievalRoute.FTS),
                findQueries(experimentId, RetrievalRoute.DENSE),
                findQueries(experimentId, RetrievalRoute.BM25)
        ));
        if (queries.isEmpty()) {
            throw new IllegalStateException("No retrieval queries found for " + experimentId);
        }

        Map<UUID, JudgmentLabel> labelsByDocument = judgments.stream()
                .collect(Collectors.toMap(RagEvaluationDocumentJudgment::documentId,
                        RagEvaluationDocumentJudgment::effectiveLabel, (left, right) -> left, LinkedHashMap::new));
        List<RagEvaluationRetrievalHit> storedHits = evaluationRepository.findHits(experimentId).stream()
                .filter(hit -> hit.route() == RetrievalRoute.FTS
                        || hit.route() == RetrievalRoute.DENSE
                        || hit.route() == RetrievalRoute.BM25)
                .toList();

        List<RouteCurvePoint> points = new ArrayList<>();
        for (int candidateCount : CANDIDATE_COUNTS) {
            List<RagEvaluationRetrievalHit> routeHits = truncateStoredHits(storedHits, candidateCount);

            RagEvaluationMetrics metrics = metricsService.calculate(judgments, routeHits);
            for (RetrievalRoute route : List.of(RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25)) {
                List<RagEvaluationRetrievalHit> hitsForRoute = routeHits.stream()
                        .filter(hit -> hit.route() == route)
                        .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                        .toList();
                for (String cut : CUTS) {
                    MetricSlice slice = metric(metrics, route, cut);
                    points.add(toPoint(candidateCount, route, cut, slice, hitsForRoute, labelsByDocument));
                }
            }
        }

        Path reportDir = Path.of("data", "rag-evaluation", experimentId.toString());
        Files.createDirectories(reportDir);
        Path reportPath = reportDir.resolve("candidate-route-curve-report.html");
        Files.writeString(reportPath, renderHtml(experimentId, queries, judgments, points), StandardCharsets.UTF_8);
        System.out.println("Candidate curve report: " + reportPath.toAbsolutePath().normalize());
    }

    private List<RagEvaluationRetrievalHit> truncateStoredHits(List<RagEvaluationRetrievalHit> storedHits,
                                                               int candidateCount) {
        List<RagEvaluationRetrievalHit> result = new ArrayList<>();
        for (RetrievalRoute route : List.of(RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25)) {
            List<RagEvaluationRetrievalHit> selected = storedHits.stream()
                    .filter(hit -> hit.route() == route)
                    .collect(Collectors.groupingBy(RagEvaluationRetrievalHit::query, LinkedHashMap::new, Collectors.toList()))
                    .values()
                    .stream()
                    .flatMap(group -> group.stream()
                            .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                            .limit(candidateCount))
                    .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                    .toList();
            int rank = 1;
            for (RagEvaluationRetrievalHit hit : selected) {
                result.add(new RagEvaluationRetrievalHit(hit.id(), hit.experimentId(), hit.route(),
                        hit.query(), rank++, hit.documentId(), hit.chunkId(), hit.score()));
            }
        }
        return result;
    }

    private RouteCurvePoint toPoint(int candidateCount,
                                    RetrievalRoute route,
                                    String cut,
                                    MetricSlice slice,
                                    List<RagEvaluationRetrievalHit> routeHits,
                                    Map<UUID, JudgmentLabel> labelsByDocument) {
        List<RagEvaluationRetrievalHit> cutHits = "@all".equals(cut)
                ? routeHits
                : routeHits.stream().limit(Integer.parseInt(cut.substring(1))).toList();
        Set<UUID> retrievedDocuments = cutHits.stream()
                .map(RagEvaluationRetrievalHit::documentId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long totalNegative = labelsByDocument.values().stream()
                .filter(label -> label != JudgmentLabel.RELEVANT)
                .count();
        long falsePositive = retrievedDocuments.stream()
                .map(labelsByDocument::get)
                .filter(label -> label == JudgmentLabel.DISTRACTOR || label == JudgmentLabel.IRRELEVANT)
                .count();
        double tnr = ratio(totalNegative - falsePositive, totalNegative);
        long relevantHits = retrievedDocuments.stream()
                .map(labelsByDocument::get)
                .filter(label -> label == JudgmentLabel.RELEVANT)
                .count();
        return new RouteCurvePoint(candidateCount, route, cut, slice.recallAtK(), slice.precisionAtK(),
                tnr, retrievedDocuments.size(), relevantHits, falsePositive, cutHits.size());
    }

    private List<String> findQueries(UUID experimentId, RetrievalRoute route) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT query
                FROM rag_eval_retrieval_hit
                WHERE experiment_id = ? AND route = ?
                ORDER BY query
                """, String.class, experimentId, route.name());
    }

    private List<String> distinctQueries(List<List<String>> groups) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String value : group) {
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return List.copyOf(values);
    }

    private MetricSlice metric(RagEvaluationMetrics metrics, RetrievalRoute route, String cut) {
        return metrics.routes().stream()
                .filter(routeMetrics -> routeMetrics.route() == route)
                .flatMap(routeMetrics -> routeMetrics.slices().stream())
                .filter(slice -> cut.equals(slice.at()))
                .findFirst()
                .orElseThrow();
    }

    private UUID resolveExperimentId() throws Exception {
        String latest = Files.readString(LATEST_RUN_FILE, StandardCharsets.UTF_8);
        for (String line : latest.split("\\R")) {
            if (line.startsWith("experimentId=")) {
                return UUID.fromString(line.substring("experimentId=".length()).trim());
            }
        }
        throw new IllegalStateException("No experimentId found in " + LATEST_RUN_FILE);
    }

    private String renderHtml(UUID experimentId,
                              List<String> queries,
                              List<RagEvaluationDocumentJudgment> judgments,
                              List<RouteCurvePoint> points) {
        long relevantDocuments = judgments.stream()
                .filter(judgment -> judgment.effectiveLabel() == JudgmentLabel.RELEVANT)
                .count();
        long negativeDocuments = judgments.size() - relevantDocuments;
        String routeCharts = """
                <section><h2>Three-route Recall@100</h2>%s</section>
                <section><h2>Three-route Precision@100</h2>%s</section>
                <section><h2>Three-route TNR@100</h2>%s</section>
                """.formatted(
                chart(points, "@100", "recall", List.of(RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25)),
                chart(points, "@100", "precision", List.of(RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25)),
                chart(points, "@100", "tnr", List.of(RetrievalRoute.FTS, RetrievalRoute.DENSE, RetrievalRoute.BM25)));
        String denseBm25Charts = """
                <section><h2>Dense/BM25 Recall@all</h2>%s</section>
                <section><h2>Dense/BM25 Precision@all</h2>%s</section>
                <section><h2>Dense/BM25 TNR@all</h2>%s</section>
                """.formatted(
                chart(points, "@all", "recall", List.of(RetrievalRoute.DENSE, RetrievalRoute.BM25)),
                chart(points, "@all", "precision", List.of(RetrievalRoute.DENSE, RetrievalRoute.BM25)),
                chart(points, "@all", "tnr", List.of(RetrievalRoute.DENSE, RetrievalRoute.BM25)));
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>RAG Candidate Curves</title>
                <style>
                body{font-family:Arial,"Microsoft YaHei",sans-serif;margin:0;background:#f7f8fb;color:#111827}
                main{max-width:1180px;margin:0 auto;padding:24px}
                h1{font-size:24px;margin:0 0 8px}h2{font-size:16px;margin:0 0 10px}
                .meta{font-size:13px;color:#4b5563;line-height:1.55;margin-bottom:16px}
                .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin:16px 0}
                section{background:#fff;border:1px solid #e5e7eb;border-radius:8px;padding:14px}
                svg{width:100%%;height:auto;display:block}
                table{width:100%%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:12px}
                th,td{padding:7px 8px;border-bottom:1px solid #edf0f4;text-align:right}
                th:first-child,td:first-child,th:nth-child(2),td:nth-child(2){text-align:left}
                th{background:#f1f5f9}.note{font-size:12px;color:#4b5563;line-height:1.5}
                </style></head><body><main>
                <h1>RAG Candidate Count Curve Report</h1>
                <div class="meta">
                Experiment: <code>%s</code><br>
                Judged documents: %d; relevant documents: %d; non-relevant documents: %d.<br>
                Query count: %d. Candidate counts: %s.<br>
                TNR = non-relevant judged documents not retrieved at the cut / total non-relevant judged documents.
                Curves are replayed from the stored 500-candidate experiment by truncating each query's stored hits to the requested candidate count.
                Dense/BM25 candidate count is chunk max-results per query; FTS is shown as stored hit candidates derived from document FTS plus priority chunks.
                </div>
                <div class="grid">%s</div>
                <div class="grid">%s</div>
                <section><h2>@100 Metrics Table</h2>%s</section>
                <section><h2>@all Metrics Table</h2>%s</section>
                </main></body></html>
                """.formatted(
                experimentId, judgments.size(), relevantDocuments, negativeDocuments, queries.size(),
                CANDIDATE_COUNTS, routeCharts, denseBm25Charts,
                table(points, "@100"), table(points, "@all"));
    }

    private String chart(List<RouteCurvePoint> points, String cut, String metric, List<RetrievalRoute> routes) {
        int width = 340;
        int height = 190;
        int left = 38;
        int right = 12;
        int top = 16;
        int bottom = 34;
        double xMin = CANDIDATE_COUNTS.get(0);
        double xMax = CANDIDATE_COUNTS.get(CANDIDATE_COUNTS.size() - 1);
        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 %d %d\" role=\"img\">".formatted(width, height));
        svg.append("<rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#fff\"/>".formatted(width, height));
        svg.append("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#d1d5db\"/>"
                .formatted(left, height - bottom, width - right, height - bottom));
        svg.append("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#d1d5db\"/>"
                .formatted(left, top, left, height - bottom));
        for (int tick : List.of(0, 25, 50, 75, 100)) {
            int y = y(tick / 100.0, top, height - bottom);
            svg.append("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#eef2f7\"/>"
                    .formatted(left, y, width - right, y));
            svg.append("<text x=\"%d\" y=\"%d\" font-size=\"9\" text-anchor=\"end\" fill=\"#6b7280\">%d%%</text>"
                    .formatted(left - 4, y + 3, tick));
        }
        Map<RetrievalRoute, String> colors = Map.of(
                RetrievalRoute.FTS, "#4c78a8",
                RetrievalRoute.DENSE, "#59a14f",
                RetrievalRoute.BM25, "#f28e2b");
        for (RetrievalRoute route : routes) {
            List<RouteCurvePoint> series = points.stream()
                    .filter(point -> point.route() == route && cut.equals(point.cut()))
                    .sorted(Comparator.comparingInt(RouteCurvePoint::candidateCount))
                    .toList();
            String path = series.stream()
                    .map(point -> "%s%d,%d".formatted(
                            point == series.get(0) ? "M" : "L",
                            x(point.candidateCount(), xMin, xMax, left, width - right),
                            y(value(point, metric), top, height - bottom)))
                    .collect(Collectors.joining(" "));
            svg.append("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2\"/>"
                    .formatted(path, colors.get(route)));
            for (RouteCurvePoint point : series) {
                svg.append("<circle cx=\"%d\" cy=\"%d\" r=\"2.5\" fill=\"%s\"/>"
                        .formatted(x(point.candidateCount(), xMin, xMax, left, width - right),
                                y(value(point, metric), top, height - bottom), colors.get(route)));
            }
        }
        int legendX = left;
        for (RetrievalRoute route : routes) {
            svg.append("<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"3\" fill=\"%s\"/>"
                    .formatted(legendX, height - 14, colors.get(route)));
            svg.append("<text x=\"%d\" y=\"%d\" font-size=\"10\" fill=\"#374151\">%s</text>"
                    .formatted(legendX + 14, height - 11, route.name()));
            legendX += route.name().length() * 7 + 34;
        }
        for (int count : CANDIDATE_COUNTS) {
            svg.append("<text x=\"%d\" y=\"%d\" font-size=\"9\" text-anchor=\"middle\" fill=\"#6b7280\">%d</text>"
                    .formatted(x(count, xMin, xMax, left, width - right), height - bottom + 14, count));
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private String table(List<RouteCurvePoint> points, String cut) {
        StringBuilder rows = new StringBuilder();
        points.stream()
                .filter(point -> cut.equals(point.cut()))
                .sorted(Comparator.comparingInt(RouteCurvePoint::candidateCount)
                        .thenComparing(point -> point.route().ordinal()))
                .forEach(point -> rows.append("""
                        <tr><td>%d</td><td>%s</td><td>%.2f%%</td><td>%.2f%%</td><td>%.2f%%</td><td>%d</td><td>%d</td><td>%d</td><td>%d</td></tr>
                        """.formatted(point.candidateCount(), point.route().name(),
                        pct(point.recall()), pct(point.precision()), pct(point.tnr()),
                        point.retrievedDocuments(), point.relevantDocuments(),
                        point.falsePositiveDocuments(), point.hitCount())));
        return """
                <table>
                <thead><tr><th>Candidates</th><th>Route</th><th>Recall</th><th>Precision</th><th>TNR</th><th>Docs</th><th>Relevant docs</th><th>False positives</th><th>Chunk hits</th></tr></thead>
                <tbody>%s</tbody></table>
                """.formatted(rows);
    }

    private int x(int candidateCount, double xMin, double xMax, int left, int right) {
        return (int) Math.round(left + (candidateCount - xMin) / (xMax - xMin) * (right - left));
    }

    private int y(double value, int top, int bottom) {
        return (int) Math.round(bottom - Math.max(0.0, Math.min(1.0, value)) * (bottom - top));
    }

    private double value(RouteCurvePoint point, String metric) {
        return switch (metric) {
            case "recall" -> point.recall();
            case "precision" -> point.precision();
            case "tnr" -> point.tnr();
            default -> throw new IllegalArgumentException("Unknown metric: " + metric);
        };
    }

    private double ratio(double numerator, double denominator) {
        if (denominator <= 0.0) {
            return 0.0;
        }
        return Math.round((numerator / denominator) * 10000.0) / 10000.0;
    }

    private double pct(double value) {
        return value * 100.0;
    }

    private record RouteCurvePoint(int candidateCount,
                                   RetrievalRoute route,
                                   String cut,
                                   double recall,
                                   double precision,
                                   double tnr,
                                   int retrievedDocuments,
                                   long relevantDocuments,
                                   long falsePositiveDocuments,
                                   int hitCount) {
    }
}
