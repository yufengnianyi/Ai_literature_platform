package com.example.demo_01.ai.evidence.eval;

import com.example.demo_01.ai.evidence.eval.EvidenceEvalModels.*;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileOutputValidator;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extraction-layer evaluation harness (L0 routing / L1 rows / L2 fields / L3 anchors).
 * Mirrors the slice style of {@code RagEvaluationMetricsService}.
 */
@Service
public class EvidenceExtractionScorer {

    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final double LENIENT_THRESHOLD = 0.55;
    private static final double STRICT_THRESHOLD = 0.92;

    @Resource
    private EvidenceProfileRegistry profileRegistry;

    @Resource
    private MultiProfileOutputValidator outputValidator;

    public EvalReport score(String runId,
                            List<GoldDocumentQuestion> gold,
                            List<PredictedDocumentQuestion> predicted) {
        Map<String, List<GoldDocumentQuestion>> goldByQ = groupGold(gold);
        Map<String, List<PredictedDocumentQuestion>> predByQ = groupPredicted(predicted);

        List<QuestionMetrics> questionMetrics = new ArrayList<>();
        List<MetricSlice> routing = new ArrayList<>();
        List<MetricSlice> rowStrict = new ArrayList<>();
        List<MetricSlice> rowLenient = new ArrayList<>();
        List<MetricSlice> anchors = new ArrayList<>();

        Set<String> questionIds = new HashSet<>();
        questionIds.addAll(goldByQ.keySet());
        questionIds.addAll(predByQ.keySet());
        for (EvidenceProfile profile : profileRegistry.all()) {
            questionIds.add(profile.questionId());
        }

        for (String questionId : questionIds.stream().sorted().toList()) {
            List<GoldDocumentQuestion> g = goldByQ.getOrDefault(questionId, List.of());
            List<PredictedDocumentQuestion> p = predByQ.getOrDefault(questionId, List.of());
            QuestionMetrics metrics = scoreQuestion(questionId, g, p);
            questionMetrics.add(metrics);
            routing.add(metrics.routing());
            rowStrict.add(metrics.rowStrict());
            rowLenient.add(metrics.rowLenient());
            anchors.add(metrics.anchorFaithfulness());
        }

        return new EvalReport(
                runId,
                List.copyOf(questionMetrics),
                aggregate("routing@all", routing),
                aggregate("row-strict@all", rowStrict),
                aggregate("row-lenient@all", rowLenient),
                aggregate("anchor@all", anchors));
    }

    private QuestionMetrics scoreQuestion(String questionId,
                                          List<GoldDocumentQuestion> gold,
                                          List<PredictedDocumentQuestion> predicted) {
        EvidenceProfile profile = profileRegistry.require(questionId);
        MetricSlice routing = scoreRouting(questionId, gold, predicted);

        int tpStrict = 0;
        int fpStrict = 0;
        int fnStrict = 0;
        int tpLenient = 0;
        int fpLenient = 0;
        int fnLenient = 0;
        int goldDocsWithRows = 0;
        int docsWithAnyMatch = 0;
        int anchorSupport = 0;
        int anchorTotal = 0;

        Map<Integer, int[]> fieldCounts = new LinkedHashMap<>();
        for (int i = 0; i < profile.headers().size(); i++) {
            fieldCounts.put(i, new int[]{0, 0, 0, 0, 0}); // correct, partial, wrong, missing, total
        }

        Map<String, PredictedDocumentQuestion> predByDoc = new LinkedHashMap<>();
        for (PredictedDocumentQuestion item : predicted) {
            predByDoc.put(item.documentId().toString(), item);
        }

        for (GoldDocumentQuestion goldItem : gold) {
            PredictedDocumentQuestion predItem = predByDoc.remove(goldItem.documentId().toString());
            List<GoldRow> goldRows = goldItem.goldRows() == null ? List.of() : goldItem.goldRows();
            List<PredictedRow> predRows = predItem == null || predItem.rows() == null
                    ? List.of() : predItem.rows();
            if (!goldRows.isEmpty()) {
                goldDocsWithRows++;
            }

            MatchResult strict = matchRows(profile, goldRows, predRows, STRICT_THRESHOLD);
            MatchResult lenient = matchRows(profile, goldRows, predRows, LENIENT_THRESHOLD);
            tpStrict += strict.tp();
            fpStrict += strict.fp();
            fnStrict += strict.fn();
            tpLenient += lenient.tp();
            fpLenient += lenient.fp();
            fnLenient += lenient.fn();
            if (strict.tp() > 0) {
                docsWithAnyMatch++;
            }

            for (MatchedPair pair : lenient.pairs()) {
                scoreFields(profile, pair.gold(), pair.predicted(), fieldCounts);
                AnchorScore anchorScore = scoreAnchors(pair.predicted());
                anchorSupport += anchorScore.supported();
                anchorTotal += anchorScore.total();
            }
        }

        for (PredictedDocumentQuestion leftover : predByDoc.values()) {
            int count = leftover.rows() == null ? 0 : leftover.rows().size();
            fpStrict += count;
            fpLenient += count;
        }

        List<FieldMetrics> fields = new ArrayList<>();
        for (Map.Entry<Integer, int[]> entry : fieldCounts.entrySet()) {
            int idx = entry.getKey();
            int[] c = entry.getValue();
            fields.add(new FieldMetrics(
                    profile.headers().get(idx),
                    fieldKind(profile.headers().get(idx)),
                    ratio(c[0], c[4]),
                    c[0], c[1], c[2], c[3], c[4]));
        }

        return new QuestionMetrics(
                questionId,
                routing,
                slice("row-strict@" + questionId, tpStrict, fpStrict, fnStrict),
                slice("row-lenient@" + questionId, tpLenient, fpLenient, fnLenient),
                List.copyOf(fields),
                slice("anchor@" + questionId, anchorSupport, anchorTotal - anchorSupport, 0),
                ratio(docsWithAnyMatch, goldDocsWithRows));
    }

    private MetricSlice scoreRouting(String questionId,
                                     List<GoldDocumentQuestion> gold,
                                     List<PredictedDocumentQuestion> predicted) {
        Map<String, String> goldStatus = new LinkedHashMap<>();
        for (GoldDocumentQuestion item : gold) {
            goldStatus.put(item.documentId().toString(), normalizeStatus(item.classification()));
        }
        Map<String, String> predStatus = new LinkedHashMap<>();
        for (PredictedDocumentQuestion item : predicted) {
            predStatus.put(item.documentId().toString(), normalizeStatus(item.classification()));
        }
        int tp = 0;
        int fp = 0;
        int fn = 0;
        Set<String> docs = new HashSet<>();
        docs.addAll(goldStatus.keySet());
        docs.addAll(predStatus.keySet());
        for (String doc : docs) {
            boolean goldPositive = isExtractable(goldStatus.get(doc));
            boolean predPositive = isExtractable(predStatus.get(doc));
            if (goldPositive && predPositive) {
                tp++;
            } else if (!goldPositive && predPositive) {
                fp++;
            } else if (goldPositive) {
                fn++;
            }
        }
        return slice("routing@" + questionId, tp, fp, fn);
    }

    private MatchResult matchRows(EvidenceProfile profile,
                                  List<GoldRow> goldRows,
                                  List<PredictedRow> predRows,
                                  double threshold) {
        boolean[] goldMatched = new boolean[goldRows.size()];
        boolean[] predMatched = new boolean[predRows.size()];
        List<MatchedPair> pairs = new ArrayList<>();

        List<ScoredPair> scored = new ArrayList<>();
        for (int g = 0; g < goldRows.size(); g++) {
            for (int p = 0; p < predRows.size(); p++) {
                double score = primarySimilarity(profile, goldRows.get(g).cells(), predRows.get(p).cells());
                if (score >= threshold) {
                    scored.add(new ScoredPair(g, p, score));
                }
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (ScoredPair pair : scored) {
            if (goldMatched[pair.goldIndex()] || predMatched[pair.predIndex()]) {
                continue;
            }
            goldMatched[pair.goldIndex()] = true;
            predMatched[pair.predIndex()] = true;
            pairs.add(new MatchedPair(goldRows.get(pair.goldIndex()), predRows.get(pair.predIndex())));
        }
        int tp = pairs.size();
        int fn = 0;
        for (boolean matched : goldMatched) {
            if (!matched) {
                fn++;
            }
        }
        int fp = 0;
        for (boolean matched : predMatched) {
            if (!matched) {
                fp++;
            }
        }
        return new MatchResult(tp, fp, fn, pairs);
    }

    private double primarySimilarity(EvidenceProfile profile,
                                     List<String> goldCells,
                                     List<String> predCells) {
        if (goldCells == null || predCells == null
                || goldCells.size() != profile.headers().size()
                || predCells.size() != profile.headers().size()) {
            return 0;
        }
        double sum = 0;
        int count = 0;
        for (Integer index : profile.primaryFieldIndexes()) {
            if (index == null || index < 0 || index >= goldCells.size()) {
                continue;
            }
            sum += cellSimilarity(goldCells.get(index), predCells.get(index),
                    fieldKind(profile.headers().get(index)));
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private void scoreFields(EvidenceProfile profile,
                             GoldRow gold,
                             PredictedRow predicted,
                             Map<Integer, int[]> fieldCounts) {
        for (int i = 0; i < profile.headers().size(); i++) {
            int[] counts = fieldCounts.get(i);
            counts[4]++;
            String g = i < gold.cells().size() ? gold.cells().get(i) : "";
            String p = i < predicted.cells().size() ? predicted.cells().get(i) : "";
            if (!hasText(g) && !hasText(p)) {
                counts[0]++;
                continue;
            }
            if (hasText(g) && !hasText(p)) {
                counts[3]++;
                continue;
            }
            double sim = cellSimilarity(g, p, fieldKind(profile.headers().get(i)));
            if (sim >= STRICT_THRESHOLD) {
                counts[0]++;
            } else if (sim >= LENIENT_THRESHOLD) {
                counts[1]++;
            } else {
                counts[2]++;
            }
        }
    }

    private AnchorScore scoreAnchors(PredictedRow predicted) {
        List<GoldAnchor> anchors = predicted.anchors() == null ? List.of() : predicted.anchors();
        if (anchors.isEmpty()) {
            return new AnchorScore(0, 1);
        }
        int supported = 0;
        for (GoldAnchor anchor : anchors) {
            if (hasText(anchor.chunkId()) && hasText(anchor.exactQuote())) {
                supported++;
            }
        }
        return new AnchorScore(supported, anchors.size());
    }

    private double cellSimilarity(String left, String right, String kind) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        if ("numeric".equals(kind)) {
            Double na = firstNumber(a);
            Double nb = firstNumber(b);
            if (na != null && nb != null) {
                double denom = Math.max(Math.abs(na), Math.abs(nb));
                if (denom == 0) {
                    return na.equals(nb) ? 1.0 : 0.0;
                }
                double rel = Math.abs(na - nb) / denom;
                return rel <= 0.05 ? 1.0 : rel <= 0.2 ? 0.6 : 0.0;
            }
        }
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return union.isEmpty() ? 0.0 : intersection.size() / (double) union.size();
    }

    private String fieldKind(String header) {
        String lower = header.toLowerCase(Locale.ROOT);
        if (lower.contains("activity") || lower.contains("ec50") || lower.contains("ic50")
                || lower.contains("mic") || lower.contains("%") || lower.contains("size")
                || lower.contains("count") || lower.contains("n50") || lower.contains("bp")
                || lower.contains("sensitivity") || lower.contains("efficacy")) {
            return "numeric";
        }
        if (lower.contains("sequence") || lower.contains("primer") || lower.contains("probe")) {
            return "sequence";
        }
        return "text";
    }

    private Double firstNumber(String text) {
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.split("[^a-z0-9\\u4e00-\\u9fff]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalize(String value) {
        return Objects.requireNonNullElse(value, "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        return Objects.requireNonNullElse(status, "NOT_SUPPORTED").trim().toUpperCase(Locale.ROOT);
    }

    private boolean isExtractable(String status) {
        return "SUPPORTED".equals(status) || "UNCERTAIN".equals(status);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private MetricSlice slice(String name, int tp, int fp, int fn) {
        double precision = ratio(tp, tp + fp);
        double recall = ratio(tp, tp + fn);
        double f1 = (precision + recall) == 0 ? 0 : ratio(2 * precision * recall, precision + recall);
        return new MetricSlice(name, precision, recall, f1, tp, fp, fn);
    }

    private MetricSlice aggregate(String name, List<MetricSlice> slices) {
        int tp = slices.stream().mapToInt(MetricSlice::truePositives).sum();
        int fp = slices.stream().mapToInt(MetricSlice::falsePositives).sum();
        int fn = slices.stream().mapToInt(MetricSlice::falseNegatives).sum();
        return slice(name, tp, fp, fn);
    }

    private double ratio(double numerator, double denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return Math.round((numerator / denominator) * 10000.0) / 10000.0;
    }

    private Map<String, List<GoldDocumentQuestion>> groupGold(List<GoldDocumentQuestion> gold) {
        Map<String, List<GoldDocumentQuestion>> map = new LinkedHashMap<>();
        if (gold == null) {
            return map;
        }
        for (GoldDocumentQuestion item : gold) {
            map.computeIfAbsent(item.questionId(), key -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private Map<String, List<PredictedDocumentQuestion>> groupPredicted(
            List<PredictedDocumentQuestion> predicted) {
        Map<String, List<PredictedDocumentQuestion>> map = new LinkedHashMap<>();
        if (predicted == null) {
            return map;
        }
        for (PredictedDocumentQuestion item : predicted) {
            map.computeIfAbsent(item.questionId(), key -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private record ScoredPair(int goldIndex, int predIndex, double score) {
    }

    private record MatchedPair(GoldRow gold, PredictedRow predicted) {
    }

    private record MatchResult(int tp, int fp, int fn, List<MatchedPair> pairs) {
    }

    private record AnchorScore(int supported, int total) {
    }
}
