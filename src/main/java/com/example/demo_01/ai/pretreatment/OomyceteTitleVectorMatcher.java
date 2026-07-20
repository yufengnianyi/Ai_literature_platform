package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.TitleDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.TitleVectorDecision;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OomyceteTitleVectorMatcher {

    private static final List<String> PROFILE_TERMS = List.of(
            "oomycete 卵菌",
            "oomycetes 卵菌",
            "Phytophthora 疫霉",
            "Pythium 腐霉",
            "Saprolegnia 水霉",
            "Plasmopara 单轴霉",
            "Peronospora 霜霉",
            "Pseudoperonospora 假霜霉",
            "Aphanomyces 丝囊霉",
            "Achlya 水霉属",
            "Bremia 盘梗霉",
            "Hyaloperonospora 透明霜霉",
            "Albugo 白锈菌",
            "Peronosclerospora 霜指霉",
            "downy mildew 霜霉病",
            "late blight 晚疫病",
            "pythiosis 腐霉病",
            "water mold 水霉",
            "water mould 水霉"
    );

    private static final List<String> LEXICAL_TERMS = List.of(
            "oomycete",
            "oomycetes",
            "phytophthora",
            "pythium",
            "saprolegnia",
            "plasmopara",
            "peronospora",
            "pseudoperonospora",
            "aphanomyces",
            "achlya",
            "bremia",
            "hyaloperonospora",
            "albugo",
            "peronosclerospora",
            "downy mildew",
            "late blight",
            "pythiosis",
            "water mold",
            "water mould",
            "卵菌",
            "疫霉",
            "腐霉",
            "水霉",
            "霜霉",
            "霜霉病",
            "晚疫病"
    );

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    public TitleMatchResult match(String title, PretreatmentProperties.TitleVector properties) {
        if (title == null || title.isBlank()) {
            return new TitleMatchResult(TitleDecision.REJECT_NO_OOMYCETE_SIGNAL, null, null,
                    thresholdPasses(0.0, properties.getThresholds(), false),
                    TitleVectorDecision.REJECT_LOW_TITLE_RELEVANCE, false);
        }
        String lexicalTerm = lexicalTerm(title);
        boolean lexicalOverride = lexicalTerm != null;
        SimilarityMatch similarity = similarity(title);
        double score = similarity.score();
        Map<String, Boolean> thresholdPasses = thresholdPasses(score, properties.getThresholds(), lexicalOverride);
        boolean pass = lexicalOverride || score >= properties.getActiveThreshold();
        return new TitleMatchResult(
                lexicalOverride ? TitleDecision.TITLE_MATCH : (pass ? TitleDecision.TITLE_UNCERTAIN : TitleDecision.REJECT_NO_OOMYCETE_SIGNAL),
                score,
                lexicalOverride ? lexicalTerm : similarity.term(),
                thresholdPasses,
                pass ? TitleVectorDecision.PASS : TitleVectorDecision.REJECT_LOW_TITLE_RELEVANCE,
                lexicalOverride
        );
    }

    private SimilarityMatch similarity(String title) {
        List<TextSegment> segments = new ArrayList<>();
        segments.add(TextSegment.from(title));
        for (String term : PROFILE_TERMS) {
            segments.add(TextSegment.from(term));
        }
        Response<List<Embedding>> response = quwenEmbeddingModel.embedAll(segments);
        List<Embedding> embeddings = response.content();
        if (embeddings == null || embeddings.size() <= 1) {
            return new SimilarityMatch(0.0, "");
        }
        float[] titleVector = embeddings.get(0).vector();
        double bestScore = -1.0;
        String bestTerm = "";
        for (int i = 1; i < embeddings.size(); i++) {
            double score = cosine(titleVector, embeddings.get(i).vector());
            if (score > bestScore) {
                bestScore = score;
                bestTerm = PROFILE_TERMS.get(i - 1);
            }
        }
        return new SimilarityMatch(round(Math.max(0.0, bestScore)), bestTerm);
    }

    private Map<String, Boolean> thresholdPasses(double score, List<Double> thresholds, boolean lexicalOverride) {
        Map<String, Boolean> passes = new LinkedHashMap<>();
        List<Double> safeThresholds = thresholds == null || thresholds.isEmpty()
                ? List.of(0.30, 0.40, 0.50, 0.60)
                : thresholds;
        for (Double threshold : safeThresholds) {
            if (threshold == null) {
                continue;
            }
            passes.put(formatThreshold(threshold), lexicalOverride || score >= threshold);
        }
        return passes;
    }

    private String lexicalTerm(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        for (String term : LEXICAL_TERMS) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                return term;
            }
        }
        return null;
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String formatThreshold(double threshold) {
        return String.format(Locale.ROOT, "%.2f", threshold);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record SimilarityMatch(double score, String term) {
    }

    public record TitleMatchResult(
            TitleDecision titleDecision,
            Double score,
            String bestProfileTerm,
            Map<String, Boolean> thresholdPasses,
            TitleVectorDecision vectorDecision,
            boolean lexicalOverride
    ) {
    }
}
