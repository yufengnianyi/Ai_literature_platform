package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import static org.assertj.core.api.Assertions.*;

class ExpertQuestionClassificationTest {

    private final EvidenceProfileRegistry registry = new EvidenceProfileRegistry();
    private MultiProfileOutputValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MultiProfileOutputValidator();
        ReflectionTestUtils.setField(validator, "objectMapper", new ObjectMapper());
    }

    @Test
    void classificationPromptUsesEveryLatestExpertQuestionTemplateInOrder() {
        var service = new MultiProfileEvidenceService();
        ReflectionTestUtils.setField(service, "profileRegistry", registry);

        String prompt = service.classificationSystemPrompt(EXPERT_PROFILE_VERSION);
        int previous = -1;
        for (var profile : registry.all(EXPERT_PROFILE_VERSION)) {
            String marker = "--- " + profile.questionId() + " template ---";
            int current = prompt.indexOf(marker);
            assertThat(current).as(marker).isGreaterThan(previous);
            previous = current;
            assertThat(prompt).contains(PromptResources.load(
                    PromptCatalog.evidenceQuestionExtractionSystem(
                            EXPERT_PROFILE_VERSION, profile.questionId())));
        }
        assertThat(prompt)
                .contains("exactly Q1-Q8")
                .contains("Q5 抗卵菌化合物、靶标与作用机制")
                .contains("Q8 病害诊断、监测、流行病学与预测预警")
                .contains("不设 Q8A/Q8B")
                .contains("background-only")
                .contains("differential expression")
                .contains("host defense inducer")
                .contains("cross-resistance");
    }

    @Test
    void expertClassificationSupportsIndependentMultiLabelMatches() throws Exception {
        List<RawQuestionClassification> questions = new ArrayList<>();
        for (int number = 1; number <= 8; number++) {
            String questionId = "Q" + number;
            if (number == 1 || number == 2 || number == 5 || number == 7) {
                questions.add(new RawQuestionClassification(
                        questionId, "SUPPORTED", 0.92,
                        "The supplied experiment supports this expert question.", List.of("c1")));
            } else {
                questions.add(new RawQuestionClassification(
                        questionId, "NOT_SUPPORTED", 0.95,
                        "No evidence for this expert question.", List.of()));
            }
        }

        String raw = new ObjectMapper().writeValueAsString(new ClassificationOutput(questions));
        List<ClassifiedQuestion> result = validator.parseClassification(
                raw, registry.forVersion(EXPERT_PROFILE_VERSION), List.of(chunk("c1")));

        assertThat(result).hasSize(8);
        assertThat(result.stream()
                .filter(item -> item.status() == ClassificationStatus.SUPPORTED)
                .map(ClassifiedQuestion::questionId))
                .containsExactly("Q1", "Q2", "Q5", "Q7");
        assertThat(result.stream()
                .filter(item -> item.status() == ClassificationStatus.NOT_SUPPORTED)
                .map(ClassifiedQuestion::questionId))
                .containsExactly("Q3", "Q4", "Q6", "Q8");
    }

    @Test
    void unsupportedOrInvalidEvidenceCannotBecomeSupported() throws Exception {
        List<RawQuestionClassification> questions = completeNotSupported();
        questions.set(4, new RawQuestionClassification(
                "Q5", "SUPPORTED", 0.99, "Activity claimed without a supplied anchor.",
                List.of("invented-chunk")));

        String raw = new ObjectMapper().writeValueAsString(new ClassificationOutput(questions));
        List<ClassifiedQuestion> result = validator.parseClassification(
                raw, registry.forVersion(EXPERT_PROFILE_VERSION), List.of(chunk("c1")));

        assertThat(result.get(4).questionId()).isEqualTo("Q5");
        assertThat(result.get(4).status()).isEqualTo(ClassificationStatus.UNCERTAIN);
        assertThat(result.get(4).chunkIds()).isEmpty();
    }

    @Test
    void outputMustContainExactlyTheLatestEightQuestionIds() throws Exception {
        List<RawQuestionClassification> missingQ8 = completeNotSupported().subList(0, 7);
        String missing = new ObjectMapper().writeValueAsString(
                new ClassificationOutput(missingQ8));
        assertThatThrownBy(() -> validator.parseClassification(
                missing, registry.forVersion(EXPERT_PROFILE_VERSION), List.of(chunk("c1"))))
                .hasMessageContaining("Missing classification for Q8");

        List<RawQuestionClassification> withLegacyQ9 = completeNotSupported();
        withLegacyQ9.add(new RawQuestionClassification(
                "Q9", "NOT_SUPPORTED", 1.0, "legacy question", List.of()));
        String legacy = new ObjectMapper().writeValueAsString(
                new ClassificationOutput(withLegacyQ9));
        assertThatThrownBy(() -> validator.parseClassification(
                legacy, registry.forVersion(EXPERT_PROFILE_VERSION), List.of(chunk("c1"))))
                .hasMessageContaining("Unknown evidence question: Q9");
    }

    private List<RawQuestionClassification> completeNotSupported() {
        List<RawQuestionClassification> questions = new ArrayList<>();
        for (int number = 1; number <= 8; number++) {
            questions.add(new RawQuestionClassification(
                    "Q" + number, "NOT_SUPPORTED", 0.95, "No evidence.", List.of()));
        }
        return questions;
    }

    private EvidenceChunk chunk(String id) {
        return new EvidenceChunk(id, "Results", 1, 1, 1,
                "A compound reduced pathogen growth and was applied to infected plants.",
                "body", "document.tei.xml");
    }
}
