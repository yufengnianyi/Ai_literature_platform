package com.example.demo_01.ai.rag.parser;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DocumentTitleHeuristics {

    private static final Pattern DOI_OR_CONTACT = Pattern.compile("(\\bdoi\\s*:|https?://doi\\.org/|10\\.\\d{4,9}/|\\be-?mail\\b|\\btel\\b|\\bfax\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final String[] INVALID_PHRASES = {
            "this article has been accepted for publication",
            "undergone full peer review",
            "copyediting, typesetting, pagination",
            "version of record",
            "please cite this article as",
            "this article is protected by copyright",
            "all rights reserved",
            "accepted manuscript",
            "author manuscript",
            "uncorrected proof",
            "article in press",
            "early view",
            "corresponding author",
            "department of",
            "college of"
    };

    private DocumentTitleHeuristics() {
    }

    public static boolean isInvalidExtractedTitle(String title, String journal) {
        String normalized = normalize(title);
        if (normalized == null) {
            return true;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String phrase : INVALID_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        if (DOI_OR_CONTACT.matcher(normalized).find()) {
            return true;
        }
        String normalizedJournal = normalize(journal);
        if (normalizedJournal != null && normalized.equalsIgnoreCase(normalizedJournal)) {
            return true;
        }
        int wordCount = normalized.split("\\s+").length;
        long sentenceMarks = normalized.chars()
                .filter(ch -> ch == '.' || ch == '!' || ch == '?')
                .count();
        return normalized.length() > 250 || wordCount > 45 || sentenceMarks > 2;
    }

    public static String validTitleOrNull(String title, String journal) {
        return isInvalidExtractedTitle(title, journal) ? null : normalize(title);
    }

    public static String stripMarkup(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        return normalize(normalized.replaceAll("<[^>]+>", ""));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
