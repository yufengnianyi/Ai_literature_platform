package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ReviewPromptCanonicalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");

    public QueryAnalysis canonicalize(String rawPrompt, QueryAnalysis analysis) {
        String languageCode = detectLanguage(rawPrompt, analysis);
        String scope = extractScope(rawPrompt, analysis);
        List<String> explicitAspects = extractExplicitAspects(rawPrompt);

        String mainQuestion = sanitizeSentence(analysis.mainQuestion());
        if (mainQuestion == null || looksInstructional(mainQuestion) || containsChinese(mainQuestion)) {
            mainQuestion = buildCanonicalMainQuestion(scope, explicitAspects, rawPrompt);
        }

        List<String> subQuestions = sanitizeSubQuestions(analysis.subQuestions(), true);
        if (!explicitAspects.isEmpty() || isBroadGeneListing(rawPrompt)) {
            subQuestions = buildCanonicalSubQuestions(scope, explicitAspects, rawPrompt);
        } else if (subQuestions.isEmpty()) {
            subQuestions = List.of(mainQuestion);
        }

        String displayMainQuestion = sanitizeSentence(analysis.displayMainQuestion());
        if (displayMainQuestion == null) {
            displayMainQuestion = "zh".equals(languageCode) ? firstUsefulLine(rawPrompt, mainQuestion) : mainQuestion;
        }
        List<String> displaySubQuestions = sanitizeSubQuestions(analysis.displaySubQuestions(), false);
        if (displaySubQuestions.isEmpty()) {
            displaySubQuestions = "zh".equals(languageCode) ? List.of(displayMainQuestion) : subQuestions;
        }

        return new QueryAnalysis(
                mainQuestion,
                subQuestions,
                dedupe(analysis.keyEntities(), true),
                dedupe(analysis.keyConcepts(), true),
                languageCode,
                displayMainQuestion,
                displaySubQuestions
        );
    }

    private String detectLanguage(String rawPrompt, QueryAnalysis analysis) {
        if (analysis.languageCode() != null && !analysis.languageCode().isBlank()) {
            return analysis.languageCode().toLowerCase(Locale.ROOT).startsWith("zh") ? "zh" : "en";
        }
        return containsChinese(rawPrompt) ? "zh" : "en";
    }

    private String extractScope(String rawPrompt, QueryAnalysis analysis) {
        String mainQuestion = sanitizeSentence(analysis.mainQuestion());
        if (mainQuestion != null && !looksInstructional(mainQuestion) && !containsChinese(mainQuestion)) {
            return trimReviewSuffix(mainQuestion);
        }
        for (String line : safe(rawPrompt).split("\\R")) {
            String candidate = sanitizeSentence(line);
            if (candidate == null || looksInstructional(candidate)) {
                continue;
            }
            return trimReviewSuffix(candidate);
        }
        return "the target research topic";
    }

    private List<String> extractExplicitAspects(String rawPrompt) {
        List<String> aspects = new ArrayList<>();
        for (String line : safe(rawPrompt).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("-") && !trimmed.startsWith("*")) {
                continue;
            }
            String candidate = sanitizeSentence(trimmed.substring(1));
            if (candidate == null || looksInstructional(candidate) || looksSchemaField(candidate)) {
                continue;
            }
            aspects.add(candidate);
        }
        return dedupe(aspects, false);
    }

    private List<String> sanitizeSubQuestions(List<String> subQuestions, boolean requireEnglish) {
        if (subQuestions == null || subQuestions.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        for (String subQuestion : subQuestions) {
            String candidate = sanitizeSentence(subQuestion);
            if (candidate == null || looksInstructional(candidate) || looksSchemaField(candidate)) {
                continue;
            }
            if (requireEnglish && containsChinese(candidate)) {
                continue;
            }
            sanitized.add(candidate);
        }
        return dedupe(sanitized, requireEnglish);
    }

    private String buildCanonicalMainQuestion(String scope, List<String> aspects, String rawPrompt) {
        if (isBroadGeneListing(rawPrompt)) {
            return "Catalog the genes, proteins, compounds, mechanisms, evidence strength, and paper-level novelty related to "
                    + scope + ".";
        }
        if (!aspects.isEmpty()) {
            return "Systematically review " + scope + " with emphasis on "
                    + String.join(", ", stripExamples(aspects)) + ".";
        }
        return "Systematically review the biological mechanisms, research evidence, and knowledge gaps related to "
                + scope + ".";
    }

    private List<String> buildCanonicalSubQuestions(String scope, List<String> aspects, String rawPrompt) {
        if (isBroadGeneListing(rawPrompt)) {
            return List.of(
                    "Which genes, proteins, or compounds are directly related to the research topic?",
                    "Which developmental stages, pathways, organisms, or phenotypes do they affect?",
                    "What mechanisms or biological processes are supported by the literature?",
                    "Which experimental or computational methods support the conclusions?",
                    "Which findings are well supported, and which remain uncertain or under-studied?"
            );
        }
        List<String> cleaned = stripExamples(aspects);
        if (cleaned.isEmpty()) {
            return List.of(
                    "What are the main entities and mechanisms involved in " + scope + "?",
                    "Which papers provide the strongest evidence for those mechanisms?",
                    "What limitations and future research directions remain?"
            );
        }
        List<String> subQuestions = new ArrayList<>();
        for (String aspect : cleaned) {
            subQuestions.add("What evidence explains " + aspect + " in " + scope + "?");
        }
        return dedupe(subQuestions, false);
    }

    private boolean isBroadGeneListing(String rawPrompt) {
        String lower = safe(rawPrompt).toLowerCase(Locale.ROOT);
        return lower.contains("catalog")
                || lower.contains("gene_name")
                || lower.contains("all genes")
                || lower.contains("all compounds")
                || lower.contains("genes")
                || lower.contains("proteins")
                || lower.contains("compounds")
                || lower.contains("基因")
                || lower.contains("蛋白")
                || lower.contains("化合物");
    }

    private List<String> stripExamples(List<String> aspects) {
        List<String> stripped = new ArrayList<>(aspects.size());
        for (String aspect : aspects) {
            String value = aspect.replaceAll("[（(].*?[）)]", "").trim();
            stripped.add(value.isBlank() ? aspect : value);
        }
        return stripped;
    }

    private boolean looksInstructional(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("your task")
                || lower.contains("requirement")
                || lower.contains("must")
                || lower.contains("return json")
                || lower.contains("do not")
                || lower.contains("evidence_text")
                || lower.contains("functional_description")
                || lower.contains("confidence")
                || lower.contains("source:")
                || lower.contains("gene_name")
                || lower.contains("你的任务")
                || lower.contains("要求")
                || lower.contains("输出格式");
    }

    private boolean looksSchemaField(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("gene_name")
                || lower.startsWith("species")
                || lower.startsWith("biological_process")
                || lower.startsWith("specific_function")
                || lower.startsWith("functional_description")
                || lower.startsWith("evidence_text")
                || lower.startsWith("source")
                || lower.startsWith("confidence");
    }

    private String sanitizeSentence(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = normalizeWhitespace(value)
                .replaceAll("^[#\\-*\\d.\\s]+", "")
                .trim();
        return sanitized.isBlank() ? null : sanitized;
    }

    private String trimReviewSuffix(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value
                .replace("systematic review", "")
                .replace("Systematic Review", "")
                .replace("系统综述", "")
                .replace("文献综述", "")
                .trim();
        return trimmed.isBlank() ? value : trimmed;
    }

    private String firstUsefulLine(String rawPrompt, String fallback) {
        for (String line : safe(rawPrompt).split("\\R")) {
            String candidate = sanitizeSentence(line);
            if (candidate != null && !looksInstructional(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private String normalizeWhitespace(String value) {
        return WHITESPACE.matcher(safe(value)).replaceAll(" ").trim();
    }

    private boolean containsChinese(String value) {
        return value != null && CHINESE.matcher(value).find();
    }

    private List<String> dedupe(List<String> values, boolean requireEnglish) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String value : values) {
            String sanitized = sanitizeSentence(value);
            if (sanitized != null && (!requireEnglish || !containsChinese(sanitized))) {
                deduped.add(sanitized);
            }
        }
        return List.copyOf(deduped);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
