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
        boolean chinese = containsChinese(rawPrompt);

        String scope = extractScope(rawPrompt, analysis);
        List<String> explicitAspects = extractExplicitAspects(rawPrompt);

        String mainQuestion = sanitizeSentence(analysis.mainQuestion());
        if (mainQuestion == null || looksInstructional(mainQuestion)) {
            mainQuestion = buildMainQuestion(scope, explicitAspects, chinese);
        }

        List<String> subQuestions = sanitizeSubQuestions(analysis.subQuestions());
        if (!explicitAspects.isEmpty()) {
            subQuestions = buildSubQuestions(scope, explicitAspects, chinese);
        } else if (subQuestions.isEmpty()) {
            subQuestions = List.of(mainQuestion);
        }

        return new QueryAnalysis(
                mainQuestion,
                subQuestions,
                dedupe(analysis.keyEntities()),
                dedupe(analysis.keyConcepts())
        );
    }

    private String extractScope(String rawPrompt, QueryAnalysis analysis) {
        for (String line : rawPrompt.split("\\R")) {
            String candidate = sanitizeSentence(line);
            if (candidate == null || looksInstructional(candidate)) {
                continue;
            }
            candidate = trimReviewSuffix(candidate);
            if (candidate != null && candidate.length() >= 4) {
                return candidate;
            }
        }

        String mainQuestion = sanitizeSentence(analysis.mainQuestion());
        if (mainQuestion != null && !looksInstructional(mainQuestion)) {
            return trimReviewSuffix(mainQuestion);
        }
        return "the target topic";
    }

    private List<String> extractExplicitAspects(String rawPrompt) {
        List<String> aspects = new ArrayList<>();
        for (String line : rawPrompt.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("-") && !trimmed.startsWith("*") && !trimmed.startsWith("•")) {
                continue;
            }
            String candidate = sanitizeSentence(trimmed.substring(1));
            if (candidate == null || looksInstructional(candidate) || looksSchemaField(candidate)) {
                continue;
            }
            aspects.add(candidate);
        }
        return dedupe(aspects);
    }

    private List<String> sanitizeSubQuestions(List<String> subQuestions) {
        if (subQuestions == null || subQuestions.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        for (String subQuestion : subQuestions) {
            String candidate = sanitizeSentence(subQuestion);
            if (candidate == null || looksInstructional(candidate) || looksSchemaField(candidate)) {
                continue;
            }
            sanitized.add(candidate);
        }
        return dedupe(sanitized);
    }

    private String buildMainQuestion(String scope, List<String> aspects, boolean chinese) {
        if (chinese) {
            if (!aspects.isEmpty()) {
                return "基于提供的文献，系统综述%s中参与%s的基因及其功能证据，并比较不同过程之间的共性与差异。"
                        .formatted(scope, String.join("、", stripExamples(aspects)));
            }
            return "基于提供的文献，系统综述%s的关键生物学机制及其证据。".formatted(scope);
        }

        if (!aspects.isEmpty()) {
            return "Based on the provided literature, systematically review the genes and supporting evidence involved in %s within %s."
                    .formatted(String.join(", ", stripExamples(aspects)), scope);
        }
        return "Based on the provided literature, systematically review the key biological mechanisms in %s."
                .formatted(scope);
    }

    private List<String> buildSubQuestions(String scope, List<String> aspects, boolean chinese) {
        List<String> subQuestions = new ArrayList<>();
        List<String> cleanedAspects = stripExamples(aspects);
        for (String aspect : cleanedAspects) {
            if (chinese) {
                subQuestions.add("哪些基因参与%s的%s过程，这些基因的功能证据是什么？".formatted(scope, aspect));
            } else {
                subQuestions.add("Which genes are involved in %s in %s, and what evidence supports their functions?"
                        .formatted(aspect, scope));
            }
        }

        if (cleanedAspects.size() >= 2) {
            if (chinese) {
                subQuestions.add("哪些基因同时参与多个过程，或在不同过程中表现出功能交叉，其证据是什么？");
            } else {
                subQuestions.add("Which genes participate in multiple processes or show cross-process functional roles, and what evidence supports this?");
            }
        }
        return dedupe(subQuestions);
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
        return lower.contains("你的任务")
                || lower.contains("要求")
                || lower.contains("每个基因")
                || lower.contains("must")
                || lower.contains("return json")
                || lower.contains("do not")
                || lower.contains("evidence_text")
                || lower.contains("functional_description")
                || lower.contains("confidence")
                || lower.contains("source：")
                || lower.contains("source:")
                || lower.contains("gene_name");
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
                .replaceAll("^[#\\-•*\\d.\\s]+", "")
                .replaceAll("^[：:]+", "")
                .trim();
        return sanitized.isBlank() ? null : sanitized;
    }

    private String trimReviewSuffix(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value
                .replace("系统性回顾", "")
                .replace("系统综述", "")
                .replace("systematic review", "")
                .replace("Systematic Review", "")
                .trim();
        return trimmed.isBlank() ? value : trimmed;
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value).replaceAll(" ").trim();
    }

    private boolean containsChinese(String value) {
        return value != null && CHINESE.matcher(value).find();
    }

    private List<String> dedupe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String value : values) {
            String sanitized = sanitizeSentence(value);
            if (sanitized != null) {
                deduped.add(sanitized);
            }
        }
        return List.copyOf(deduped);
    }
}
