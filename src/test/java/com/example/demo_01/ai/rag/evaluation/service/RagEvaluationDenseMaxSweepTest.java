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
import org.springframework.test.util.ReflectionTestUtils;

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
import java.util.stream.Collectors;

@SpringBootTest
class RagEvaluationDenseMaxSweepTest {

    private static final Path LATEST_RUN_FILE = Path.of(
            "data", "rag-evaluation", "large-goldset-latest.txt");
    private static final List<Integer> DEFAULT_DENSE_MAX_VALUES = List.of(
            200, 400, 600, 800, 1000, 1200, 1400, 1600,
            1800, 2000, 2200, 2400, 2600, 2800, 3000);
    private static final List<RouteSet> ROUTE_SETS = List.of(
            new RouteSet("Expanded", RetrievalRoute.FTS, RetrievalRoute.DENSE,
                    RetrievalRoute.BM25, RetrievalRoute.OVERALL),
            new RouteSet("Review entity", RetrievalRoute.REVIEW_ENTITY_FTS,
                    RetrievalRoute.REVIEW_ENTITY_DENSE, RetrievalRoute.REVIEW_ENTITY_BM25,
                    RetrievalRoute.REVIEW_ENTITY_OVERALL),
            new RouteSet("Gold entity", RetrievalRoute.GOLD_ENTITY_FTS,
                    RetrievalRoute.GOLD_ENTITY_DENSE, RetrievalRoute.GOLD_ENTITY_BM25,
                    RetrievalRoute.GOLD_ENTITY_OVERALL)
    );

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
    void runDenseMaxSweepFrom200To3000() throws Exception {
        UUID experimentId = resolveExperimentId();
        List<Integer> denseMaxValues = denseMaxValues();
        List<RagEvaluationDocumentJudgment> judgments = evaluationRepository.findJudgments(experimentId);
        if (judgments.isEmpty()) {
            throw new IllegalStateException("No judgments found for " + experimentId);
        }
        Set<UUID> judgedDocumentIds = judgments.stream()
                .map(RagEvaluationDocumentJudgment::documentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, JudgmentLabel> labelsByDocument = judgments.stream()
                .collect(Collectors.toMap(RagEvaluationDocumentJudgment::documentId,
                        RagEvaluationDocumentJudgment::effectiveLabel,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, Object> experimentConfig = evaluationRepository.findExperiment(experimentId)
                .orElseThrow(() -> new IllegalStateException("Experiment not found: " + experimentId))
                .config();
        applyFixedConfig(experimentConfig);

        List<RagEvaluationRetrievalHit> storedHits = evaluationRepository.findHits(experimentId);
        Map<RouteSet, List<String>> queriesBySet = new LinkedHashMap<>();
        for (RouteSet routeSet : ROUTE_SETS) {
            List<String> queries = distinctQueries(List.of(
                    findQueries(experimentId, routeSet.ftsRoute()),
                    findQueries(experimentId, routeSet.denseRoute()),
                    findQueries(experimentId, routeSet.bm25Route())
            ));
            if (queries.isEmpty()) {
                throw new IllegalStateException("No queries found for " + routeSet.label()
                        + " in " + experimentId);
            }
            queriesBySet.put(routeSet, queries);
        }

        List<DenseSweepPoint> points = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int denseMax : denseMaxValues) {
            try {
                properties.setDenseMaxResults(denseMax);
                List<RagEvaluationRetrievalHit> routeHits = new ArrayList<>();
                for (RouteSet routeSet : ROUTE_SETS) {
                    routeHits.addAll(fixedFtsBm25Hits(storedHits, routeSet));
                    routeHits.addAll(remap(invokeDense(experimentId, queriesBySet.get(routeSet)),
                            routeSet.denseRoute()));
                }
                routeHits = evaluationService.filterAndRerankHits(routeHits, judgedDocumentIds);

                List<RagEvaluationRetrievalHit> allHits = new ArrayList<>(routeHits);
                for (RouteSet routeSet : ROUTE_SETS) {
                    allHits.addAll(evaluationService.fuseOverall(experimentId, routeHits,
                            List.of(routeSet.ftsRoute(), routeSet.denseRoute(), routeSet.bm25Route()),
                            routeSet.overallRoute()));
                }

                RagEvaluationMetrics metrics = metricsService.calculate(judgments, allHits);
                for (RouteSet routeSet : ROUTE_SETS) {
                    points.add(point(denseMax, routeSet.label(), "Dense",
                            metric(metrics, routeSet.denseRoute(), "@all"),
                            hitsFor(allHits, routeSet.denseRoute()), labelsByDocument));
                    points.add(point(denseMax, routeSet.label(), "Overall",
                            metric(metrics, routeSet.overallRoute(), "@all"),
                            hitsFor(allHits, routeSet.overallRoute()), labelsByDocument));
                    points.add(point(denseMax, routeSet.label(), "Overall@100",
                            metric(metrics, routeSet.overallRoute(), "@100"),
                            hitsFor(allHits, routeSet.overallRoute()).stream().limit(100).toList(),
                            labelsByDocument));
                }
                System.out.printf("Dense sweep finished denseMax=%d%n", denseMax);
            } catch (Exception e) {
                failures.add("denseMax=" + denseMax + ": " + shortError(e));
                if (points.isEmpty() || isNonRetryableApiFailure(e)) {
                    break;
                }
            }
        }

        Path reportDir = Path.of("data", "rag-evaluation", experimentId.toString());
        Files.createDirectories(reportDir);
        Path reportPath = reportDir.resolve("dense-max-sweep-report.html");
        Files.writeString(reportPath, renderHtml(experimentId, denseMaxValues, queriesBySet,
                judgments, points, failures), StandardCharsets.UTF_8);
        System.out.println("Dense max sweep report: " + reportPath.toAbsolutePath().normalize());
        if (!failures.isEmpty() && Boolean.getBoolean("rag.eval.dense-sweep.fail-on-api-error")) {
            throw new IllegalStateException("Dense sweep failed: " + String.join("; ", failures));
        }
    }

    private void applyFixedConfig(Map<String, Object> rawConfig) {
        int rrfK = intConfig(rawConfig, "rrfK", properties.getRrfK());
        double denseMinScore = doubleConfig(rawConfig, "denseMinScore", properties.getDenseMinScore());
        properties.setRrfK(rrfK);
        properties.setDenseMinScore(denseMinScore);
    }

    private int intConfig(Map<String, Object> rawConfig, String key, int fallback) {
        Object value = rawConfig == null ? null : rawConfig.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private double doubleConfig(Map<String, Object> rawConfig, String key, double fallback) {
        Object value = rawConfig == null ? null : rawConfig.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private List<RagEvaluationRetrievalHit> fixedFtsBm25Hits(List<RagEvaluationRetrievalHit> storedHits,
                                                            RouteSet routeSet) {
        return storedHits.stream()
                .filter(hit -> hit.route() == routeSet.ftsRoute() || hit.route() == routeSet.bm25Route())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<RagEvaluationRetrievalHit> invokeDense(UUID experimentId, List<String> queries) {
        List<RagEvaluationRetrievalHit> hits = ReflectionTestUtils.invokeMethod(
                evaluationService, "retrieveDense", experimentId, queries);
        return hits == null ? List.of() : hits;
    }

    private List<RagEvaluationRetrievalHit> remap(List<RagEvaluationRetrievalHit> hits, RetrievalRoute route) {
        return hits.stream()
                .map(hit -> new RagEvaluationRetrievalHit(hit.id(), hit.experimentId(), route,
                        hit.query(), hit.rank(), hit.documentId(), hit.chunkId(), hit.score()))
                .toList();
    }

    private DenseSweepPoint point(int denseMax,
                                  String routeSet,
                                  String route,
                                  MetricSlice metric,
                                  List<RagEvaluationRetrievalHit> hits,
                                  Map<UUID, JudgmentLabel> labelsByDocument) {
        Set<UUID> retrievedDocuments = hits.stream()
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
        return new DenseSweepPoint(denseMax, routeSet, route, metric.recallAtK(),
                metric.precisionAtK(), tnr, metric.keyChunkRecall(),
                metric.retrievedDocCount(), falsePositive, metric.mrr(), metric.ndcgAtK(), metric.map());
    }

    private List<RagEvaluationRetrievalHit> hitsFor(List<RagEvaluationRetrievalHit> hits, RetrievalRoute route) {
        return hits.stream()
                .filter(hit -> hit.route() == route)
                .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                .toList();
    }

    private MetricSlice metric(RagEvaluationMetrics metrics, RetrievalRoute route, String cut) {
        return metrics.routes().stream()
                .filter(routeMetrics -> routeMetrics.route() == route)
                .flatMap(routeMetrics -> routeMetrics.slices().stream())
                .filter(slice -> cut.equals(slice.at()))
                .findFirst()
                .orElseThrow();
    }

    private boolean isNonRetryableApiFailure(Exception error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("arrearage")
                        || lower.contains("overdue-payment")
                        || lower.contains("invalid api-key")
                        || lower.contains("invalid api key")
                        || lower.contains("access denied")
                        || lower.contains("unauthorized")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String shortError(Exception error) {
        StringBuilder message = new StringBuilder(error.getClass().getSimpleName());
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            message.append(" - ").append(error.getMessage());
        }
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message.append(" | caused by ")
                        .append(cause.getClass().getSimpleName())
                        .append(" - ")
                        .append(cause.getMessage());
            }
            cause = cause.getCause();
        }
        return message.toString()
                .replace('\n', ' ')
                .replace('\r', ' ');
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
            group.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(values::add);
        }
        return List.copyOf(values);
    }

    private List<Integer> denseMaxValues() {
        String explicit = System.getProperty("rag.eval.dense-sweep.values");
        if (explicit == null || explicit.isBlank()) {
            return DEFAULT_DENSE_MAX_VALUES;
        }
        return java.util.Arrays.stream(explicit.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Integer::parseInt)
                .filter(value -> value >= 1)
                .distinct()
                .sorted()
                .toList();
    }

    private UUID resolveExperimentId() throws Exception {
        String explicit = System.getProperty("rag.eval.experiment-id");
        if (explicit != null && !explicit.isBlank()) {
            return UUID.fromString(explicit.trim());
        }
        String latest = Files.readString(LATEST_RUN_FILE, StandardCharsets.UTF_8);
        for (String line : latest.split("\\R")) {
            if (line.startsWith("experimentId=")) {
                return UUID.fromString(line.substring("experimentId=".length()).trim());
            }
        }
        throw new IllegalStateException("No experimentId found in " + LATEST_RUN_FILE);
    }

    private String renderHtml(UUID experimentId,
                              List<Integer> denseMaxValues,
                              Map<RouteSet, List<String>> queriesBySet,
                              List<RagEvaluationDocumentJudgment> judgments,
                              List<DenseSweepPoint> points,
                              List<String> failures) {
        long relevantDocuments = judgments.stream()
                .filter(judgment -> judgment.effectiveLabel() == JudgmentLabel.RELEVANT)
                .count();
        long negativeDocuments = judgments.size() - relevantDocuments;
        String queryCounts = queriesBySet.entrySet().stream()
                .map(entry -> entry.getKey().label() + "=" + entry.getValue().size())
                .collect(Collectors.joining("; "));
        String failureNote = failures.isEmpty()
                ? ""
                : "<div class=\"warn\">Dense sweep blocked or partial: %s</div>"
                .formatted(escapeHtml(String.join("; ", failures)));
        if (points.isEmpty()) {
            return """
                    <!doctype html>
                    <html><head><meta charset="utf-8"><title>Dense Max Sweep Blocked</title>
                    <style>
                    body{font-family:Arial,"Microsoft YaHei",sans-serif;margin:0;background:#f7f8fb;color:#111827}
                    main{max-width:900px;margin:0 auto;padding:24px}
                    h1{font-size:23px;margin:0 0 8px}.meta{font-size:13px;color:#4b5563;line-height:1.55}
                    .warn{background:#fff7ed;border:1px solid #fed7aa;color:#9a3412;border-radius:8px;padding:12px;margin-top:14px;font-size:13px;line-height:1.5}
                    </style></head><body><main>
                    <h1>Dense Max Sweep Blocked</h1>
                    <div class="meta">
                    Experiment: <code>%s</code><br>
                    Requested dense max values: %s.<br>
                    Query counts: %s.<br>
                    No Dense point was completed, so no metric curve can be generated.
                    </div>
                    %s
                    </main></body></html>
                    """.formatted(experimentId, denseMaxValues, escapeHtml(queryCounts), failureNote);
        }
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>Dense Max Sweep</title>
                <style>
                body{font-family:Arial,"Microsoft YaHei",sans-serif;margin:0;background:#f7f8fb;color:#111827}
                main{max-width:1160px;margin:0 auto;padding:22px}
                h1{font-size:23px;margin:0 0 8px}h2{font-size:15px;margin:0 0 9px}
                .meta{font-size:12px;color:#4b5563;line-height:1.55;margin-bottom:14px}
                .grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:14px 0}
                section{background:#fff;border:1px solid #e5e7eb;border-radius:8px;padding:12px;min-width:0}
                svg{width:100%%;height:auto;display:block}
                table{width:100%%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:11px}
                th,td{padding:6px 7px;border-bottom:1px solid #edf0f4;text-align:right}
                th:first-child,td:first-child,th:nth-child(2),td:nth-child(2),th:nth-child(3),td:nth-child(3){text-align:left}
                th{background:#f1f5f9}.note{font-size:12px;color:#4b5563;line-height:1.5}
                .warn{background:#fff7ed;border:1px solid #fed7aa;color:#9a3412;border-radius:8px;padding:10px;margin:10px 0;font-size:12px;line-height:1.45}
                @media(max-width:850px){.grid{grid-template-columns:1fr}}
                </style></head><body><main>
                <h1>Dense Max Sweep Report</h1>
                <div class="meta">
                Experiment: <code>%s</code><br>
                Judged documents: %d; relevant documents: %d; non-relevant documents: %d.<br>
                Dense max values: %s. Query counts: %s.<br>
                Only Dense retrieval is rerun. Stored FTS/BM25 hits from the same experiment are fixed and reused for RRF overall routes.
                Dense max is the maximum retrieved chunks per query. TNR = non-relevant judged documents not retrieved / total non-relevant judged documents.
                </div>
                %s
                <div class="grid">
                  <section><h2>Overall Recall@all</h2>%s</section>
                  <section><h2>Overall Precision@all</h2>%s</section>
                  <section><h2>Overall TNR@all</h2>%s</section>
                  <section><h2>Overall Key Chunk Recall</h2>%s</section>
                  <section><h2>Dense Route Recall@all</h2>%s</section>
                  <section><h2>Overall Recall@100</h2>%s</section>
                </div>
                <section><h2>All Metrics</h2>%s</section>
                </main></body></html>
                """.formatted(
                experimentId, judgments.size(), relevantDocuments, negativeDocuments,
                denseMaxValues, escapeHtml(queryCounts),
                failureNote,
                chart(points, "Overall", "recall", denseMaxValues),
                chart(points, "Overall", "precision", denseMaxValues),
                chart(points, "Overall", "tnr", denseMaxValues),
                chart(points, "Overall", "keyChunkRecall", denseMaxValues),
                chart(points, "Dense", "recall", denseMaxValues),
                chart(points, "Overall@100", "recall", denseMaxValues),
                table(points));
    }

    private String chart(List<DenseSweepPoint> points,
                         String route,
                         String metric,
                         List<Integer> denseMaxValues) {
        int width = 520;
        int height = 230;
        int left = 44;
        int right = 14;
        int top = 16;
        int bottom = 38;
        double xMin = denseMaxValues.get(0);
        double xMax = denseMaxValues.get(denseMaxValues.size() - 1);
        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 %d %d\" role=\"img\">".formatted(width, height));
        svg.append("<rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#fff\"/>".formatted(width, height));
        for (int tick : List.of(0, 25, 50, 75, 100)) {
            int y = y(tick / 100.0, top, height - bottom);
            svg.append("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#eef2f7\"/>"
                    .formatted(left, y, width - right, y));
            svg.append("<text x=\"%d\" y=\"%d\" font-size=\"9\" text-anchor=\"end\" fill=\"#6b7280\">%d%%</text>"
                    .formatted(left - 5, y + 3, tick));
        }
        svg.append("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#d1d5db\"/>"
                .formatted(left, height - bottom, width - right, height - bottom));
        svg.append("<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#d1d5db\"/>"
                .formatted(left, top, left, height - bottom));
        Map<String, String> colors = Map.of(
                "Expanded", "#4c78a8",
                "Review entity", "#59a14f",
                "Gold entity", "#f28e2b");
        for (String routeSet : colors.keySet()) {
            List<DenseSweepPoint> series = points.stream()
                    .filter(point -> point.routeSet().equals(routeSet) && point.route().equals(route))
                    .sorted(Comparator.comparingInt(DenseSweepPoint::denseMax))
                    .toList();
            String path = "";
            for (int i = 0; i < series.size(); i++) {
                DenseSweepPoint point = series.get(i);
                path += (i == 0 ? "M" : "L")
                        + x(point.denseMax(), xMin, xMax, left, width - right)
                        + "," + y(value(point, metric), top, height - bottom) + " ";
            }
            svg.append("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2\"/>"
                    .formatted(path.trim(), colors.get(routeSet)));
            for (DenseSweepPoint point : series) {
                svg.append("<circle cx=\"%d\" cy=\"%d\" r=\"2.5\" fill=\"%s\"/>"
                        .formatted(x(point.denseMax(), xMin, xMax, left, width - right),
                                y(value(point, metric), top, height - bottom), colors.get(routeSet)));
            }
        }
        int legendX = left;
        for (String routeSet : List.of("Expanded", "Review entity", "Gold entity")) {
            svg.append("<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"3\" fill=\"%s\"/>"
                    .formatted(legendX, height - 14, colors.get(routeSet)));
            svg.append("<text x=\"%d\" y=\"%d\" font-size=\"10\" fill=\"#374151\">%s</text>"
                    .formatted(legendX + 14, height - 11, escapeHtml(routeSet)));
            legendX += routeSet.length() * 6 + 34;
        }
        for (int count : denseMaxValues) {
            svg.append("<text x=\"%d\" y=\"%d\" font-size=\"8\" text-anchor=\"middle\" fill=\"#6b7280\">%d</text>"
                    .formatted(x(count, xMin, xMax, left, width - right), height - bottom + 14, count));
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private String table(List<DenseSweepPoint> points) {
        StringBuilder rows = new StringBuilder();
        points.stream()
                .sorted(Comparator.comparingInt(DenseSweepPoint::denseMax)
                        .thenComparing(DenseSweepPoint::routeSet)
                        .thenComparing(DenseSweepPoint::route))
                .forEach(point -> rows.append("""
                        <tr><td>%d</td><td>%s</td><td>%s</td><td>%.2f%%</td><td>%.2f%%</td><td>%.2f%%</td><td>%.2f%%</td><td>%d</td><td>%d</td><td>%.4f</td><td>%.4f</td><td>%.4f</td></tr>
                        """.formatted(point.denseMax(), escapeHtml(point.routeSet()), point.route(),
                        pct(point.recall()), pct(point.precision()), pct(point.tnr()),
                        pct(point.keyChunkRecall()), point.retrievedDocuments(),
                        point.falsePositiveDocuments(), point.mrr(), point.ndcg(), point.map())));
        return """
                <table>
                <thead><tr><th>Dense max</th><th>Query set</th><th>Route</th><th>Recall</th><th>Precision</th><th>TNR</th><th>Key chunk</th><th>Docs</th><th>False positives</th><th>MRR</th><th>nDCG</th><th>MAP</th></tr></thead>
                <tbody>%s</tbody></table>
                """.formatted(rows);
    }

    private int x(int denseMax, double xMin, double xMax, int left, int right) {
        return (int) Math.round(left + (denseMax - xMin) / (xMax - xMin) * (right - left));
    }

    private int y(double value, int top, int bottom) {
        return (int) Math.round(bottom - Math.max(0.0, Math.min(1.0, value)) * (bottom - top));
    }

    private double value(DenseSweepPoint point, String metric) {
        return switch (metric) {
            case "recall" -> point.recall();
            case "precision" -> point.precision();
            case "tnr" -> point.tnr();
            case "keyChunkRecall" -> point.keyChunkRecall();
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

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record RouteSet(String label,
                            RetrievalRoute ftsRoute,
                            RetrievalRoute denseRoute,
                            RetrievalRoute bm25Route,
                            RetrievalRoute overallRoute) {
    }

    private record DenseSweepPoint(int denseMax,
                                   String routeSet,
                                   String route,
                                   double recall,
                                   double precision,
                                   double tnr,
                                   double keyChunkRecall,
                                   int retrievedDocuments,
                                   long falsePositiveDocuments,
                                   double mrr,
                                   double ndcg,
                                   double map) {
    }
}
