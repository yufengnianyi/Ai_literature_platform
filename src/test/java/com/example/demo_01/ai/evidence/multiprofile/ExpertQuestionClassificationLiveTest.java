package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ClassificationStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ClassifiedQuestion;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.example.demo_01.ai.model.DashScopeChatRequestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.EXPERT_PROFILE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in semantic classification evaluation against real abstracts in the local RAG corpus.
 * Enable explicitly because it calls the configured DashScope model and consumes tokens.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_CLASSIFICATION_TESTS", matches = "true")
class ExpertQuestionClassificationLiveTest {

    private static final List<Case> CASES = List.of(
            new Case("0c9e1f06-6984-46b5-bba2-29021688c5ad", Set.of("Q1", "Q2", "Q3")),
            new Case("034e7aff-0a54-4050-ad4a-ffe4e589cd2b", Set.of("Q3", "Q4")),
            new Case("160e49ff-8ba2-4fad-a222-ce1afd232c6c", Set.of("Q4")),
            new Case("03cf6af5-5477-4917-8c96-56f7db5418ce", Set.of("Q5", "Q7")),
            new Case("14ff899b-a254-445c-a394-c14653a294bf", Set.of("Q6", "Q8")),
            new Case("02678a42-6cc4-4fbc-88bc-b1580d3afa5d", Set.of("Q7")),
            new Case("016332b5-552c-4365-ab4c-718d5571db88", Set.of("Q8")),
            new Case("0608ccd1-4748-4f24-9f1b-59875ebd1a27", Set.of("Q4", "Q8")),
            new Case("455957a3-2a12-479d-83e6-3d7d386e9b70", Set.of("Q5", "Q7"))
    );

    @Test
    void classifyMultipleRealAbstractsAndWriteEvaluationReport() throws Exception {
        String apiKey = requiredEnvironment("DASHSCOPE_API_KEY");
        String modelName = environmentOrDefault(
                "DASHSCOPE_CHAT_MODEL", "qwen3-max-2026-01-23");
        var model = QwenChatModel.builder().apiKey(apiKey).modelName(modelName).build();
        var requestFactory = new DashScopeChatRequestFactory();
        var registry = new EvidenceProfileRegistry();
        var validator = new MultiProfileOutputValidator();
        ReflectionTestUtils.setField(validator, "objectMapper", new ObjectMapper());
        var service = new MultiProfileEvidenceService();
        ReflectionTestUtils.setField(service, "profileRegistry", registry);
        String systemPrompt = service.classificationSystemPrompt(EXPERT_PROFILE_VERSION);

        List<Result> results = new ArrayList<>();
        for (Case testCase : CASES) {
            Paper paper = loadPaper(testCase.documentId());
            EvidenceChunk chunk = new EvidenceChunk(
                    "abstract", "Abstract", 1, 1, 1,
                    paper.abstractText(), "abstract", "header.tei.xml");
            SourceDocument document = new SourceDocument(
                    UUID.fromString(testCase.documentId()), paper.title(), List.of(),
                    null, null, null, null);
            String userPrompt = service.classificationInput(
                    document, List.of(chunk), EXPERT_PROFILE_VERSION);
            var response = model.chat(requestFactory.request(false, 0,
                    SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)));
            List<ClassifiedQuestion> classified = validator.parseClassification(
                    response.aiMessage().text(), registry.forVersion(EXPERT_PROFILE_VERSION),
                    List.of(chunk));
            Set<String> predicted = new LinkedHashSet<>();
            for (ClassifiedQuestion item : classified) {
                if (item.status() == ClassificationStatus.SUPPORTED
                        || item.status() == ClassificationStatus.UNCERTAIN) {
                    predicted.add(item.questionId());
                }
            }
            results.add(Result.of(testCase, paper.title(), predicted, classified));
        }

        Evaluation evaluation = Evaluation.from(results);
        writeReport(modelName, results, evaluation);
        assertThat(evaluation.recall()).as("live classification recall").isGreaterThanOrEqualTo(0.80);
        assertThat(evaluation.precision()).as("live classification precision").isGreaterThanOrEqualTo(0.80);
    }

    private Paper loadPaper(String documentId) throws Exception {
        Path path = Path.of("data", "rag", documentId, "header.tei.xml");
        assertThat(path).as("local TEI fixture for " + documentId).exists();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(path.toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        String title = ((String) xpath.evaluate(
                "string((//*[local-name()='titleStmt']/*[local-name()='title'][@type='main']"
                        + " | //*[local-name()='titleStmt']/*[local-name()='title'])[1])",
                document, XPathConstants.STRING)).trim();
        String abstractText = ((String) xpath.evaluate(
                "string(//*[local-name()='profileDesc']/*[local-name()='abstract'])",
                document, XPathConstants.STRING)).replaceAll("\\s+", " ").trim();
        assertThat(abstractText).as("abstract for " + documentId).isNotBlank();
        return new Paper(title, abstractText);
    }

    private void writeReport(String modelName, List<Result> results, Evaluation evaluation)
            throws Exception {
        Path output = Path.of("target", "classification-evaluation", "expert-q8-live.md");
        Files.createDirectories(output.getParent());
        StringBuilder report = new StringBuilder("# Expert Q1-Q8 live classification evaluation\n\n")
                .append("- model: `").append(modelName).append("`\n")
                .append("- papers: ").append(results.size()).append("\n")
                .append(String.format(Locale.ROOT,
                        "- micro precision: %.4f\n- micro recall: %.4f\n- micro F1: %.4f\n\n",
                        evaluation.precision(), evaluation.recall(), evaluation.f1()))
                .append("| Document | Title | Expected | Predicted | TP | FP | FN |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (Result result : results) {
            report.append("| ").append(result.documentId()).append(" | ")
                    .append(escape(result.title())).append(" | ")
                    .append(join(result.expected())).append(" | ")
                    .append(join(result.predicted())).append(" | ")
                    .append(join(result.truePositive())).append(" | ")
                    .append(join(result.falsePositive())).append(" | ")
                    .append(join(result.falseNegative())).append(" |\n");
        }
        report.append("\n## Model decisions\n");
        for (Result result : results) {
            report.append("\n### ").append(result.documentId()).append("\n\n");
            for (ClassifiedQuestion item : result.decisions()) {
                report.append("- ").append(item.questionId()).append(": `")
                        .append(item.status()).append("` (")
                        .append(String.format(Locale.ROOT, "%.2f", item.confidence()))
                        .append(") — ").append(item.reason()).append("\n");
            }
        }
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private String requiredEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required for the live classification test");
        }
        return value;
    }

    private String environmentOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String join(Set<String> values) {
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String escape(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private record Case(String documentId, Set<String> expected) { }

    private record Paper(String title, String abstractText) { }

    private record Result(String documentId, String title, Set<String> expected,
                          Set<String> predicted, Set<String> truePositive,
                          Set<String> falsePositive, Set<String> falseNegative,
                          List<ClassifiedQuestion> decisions) {
        static Result of(Case testCase, String title, Set<String> predicted,
                         List<ClassifiedQuestion> decisions) {
            Set<String> tp = new LinkedHashSet<>(predicted);
            tp.retainAll(testCase.expected());
            Set<String> fp = new LinkedHashSet<>(predicted);
            fp.removeAll(testCase.expected());
            Set<String> fn = new LinkedHashSet<>(testCase.expected());
            fn.removeAll(predicted);
            return new Result(testCase.documentId(), title, testCase.expected(), Set.copyOf(predicted),
                    Set.copyOf(tp), Set.copyOf(fp), Set.copyOf(fn), List.copyOf(decisions));
        }
    }

    private record Evaluation(double precision, double recall, double f1) {
        static Evaluation from(List<Result> results) {
            int tp = results.stream().mapToInt(result -> result.truePositive().size()).sum();
            int fp = results.stream().mapToInt(result -> result.falsePositive().size()).sum();
            int fn = results.stream().mapToInt(result -> result.falseNegative().size()).sum();
            double precision = tp == 0 ? 0 : (double) tp / (tp + fp);
            double recall = tp == 0 ? 0 : (double) tp / (tp + fn);
            double f1 = precision + recall == 0 ? 0
                    : 2 * precision * recall / (precision + recall);
            return new Evaluation(precision, recall, f1);
        }
    }
}
