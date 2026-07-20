package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.TitleVectorDecision;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OomyceteTitleVectorMatcherTest {

    private OomyceteTitleVectorMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new OomyceteTitleVectorMatcher();
        ReflectionTestUtils.setField(matcher, "quwenEmbeddingModel", new FakeEmbeddingModel());
    }

    @Test
    void lexicalOverridePassesEnglishTitle() {
        var result = matcher.match("A new disease caused by Pythium in soybean", properties());

        assertThat(result.vectorDecision()).isEqualTo(TitleVectorDecision.PASS);
        assertThat(result.lexicalOverride()).isTrue();
        assertThat(result.bestProfileTerm()).isEqualTo("pythium");
        assertThat(result.thresholdPasses()).containsEntry("0.60", true);
    }

    @Test
    void lexicalOverridePassesChineseTitle() {
        var result = matcher.match("腐霉侵染机制研究", properties());

        assertThat(result.vectorDecision()).isEqualTo(TitleVectorDecision.PASS);
        assertThat(result.lexicalOverride()).isTrue();
        assertThat(result.bestProfileTerm()).isEqualTo("腐霉");
    }

    @Test
    void vectorSimilarityPassesRelatedTitleWithoutLexicalTerm() {
        var result = matcher.match("A study of stramenopile plant pathogens", properties());

        assertThat(result.vectorDecision()).isEqualTo(TitleVectorDecision.PASS);
        assertThat(result.lexicalOverride()).isFalse();
        assertThat(result.score()).isGreaterThanOrEqualTo(0.40);
        assertThat(result.thresholdPasses()).containsEntry("0.30", true).containsEntry("0.60", true);
    }

    @Test
    void lowSimilarityRejectsUnrelatedTitle() {
        var result = matcher.match("Consumer willingness to pay for potatoes", properties());

        assertThat(result.vectorDecision()).isEqualTo(TitleVectorDecision.REJECT_LOW_TITLE_RELEVANCE);
        assertThat(result.score()).isLessThan(0.40);
        assertThat(result.thresholdPasses()).containsEntry("0.30", false);
    }

    private PretreatmentProperties.TitleVector properties() {
        PretreatmentProperties.TitleVector properties = new PretreatmentProperties.TitleVector();
        properties.setActiveThreshold(0.40);
        properties.setThresholds(List.of(0.30, 0.40, 0.50, 0.60));
        return properties;
    }

    private static class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(vector(segment.text())))
                    .toList());
        }

        private float[] vector(String text) {
            String lower = text == null ? "" : text.toLowerCase();
            if (lower.contains("oomycete")
                    || lower.contains("phytophthora")
                    || lower.contains("pythium")
                    || lower.contains("saprolegnia")
                    || lower.contains("plasmopara")
                    || lower.contains("peronospora")
                    || lower.contains("aphanomyces")
                    || lower.contains("achlya")
                    || lower.contains("bremia")
                    || lower.contains("hyaloperonospora")
                    || lower.contains("albugo")
                    || lower.contains("peronosclerospora")
                    || lower.contains("downy mildew")
                    || lower.contains("late blight")
                    || lower.contains("pythiosis")
                    || lower.contains("water mold")
                    || lower.contains("water mould")
                    || lower.contains("卵菌")
                    || lower.contains("疫霉")
                    || lower.contains("腐霉")
                    || lower.contains("水霉")
                    || lower.contains("霜霉")
                    || lower.contains("单轴霉")
                    || lower.contains("丝囊霉")
                    || lower.contains("盘梗霉")
                    || lower.contains("白锈菌")
                    || lower.contains("stramenopile plant pathogens")) {
                return new float[]{1.0f, 0.0f};
            }
            return new float[]{0.0f, 1.0f};
        }
    }
}
