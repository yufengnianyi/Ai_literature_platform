package com.example.demo_01.ai.rag.parser;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DoiNormalizer {

    private static final Pattern DOI_PATTERN = Pattern.compile("10\\.\\d{4,9}/[-._;()/:A-Z0-9]+", Pattern.CASE_INSENSITIVE);

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim();
        candidate = candidate.replace("https://doi.org/", "");
        candidate = candidate.replace("http://doi.org/", "");
        candidate = candidate.replace("doi:", "");
        candidate = candidate.trim();

        Matcher matcher = DOI_PATTERN.matcher(candidate);
        if (matcher.find()) {
            return matcher.group().toLowerCase(Locale.ROOT);
        }
        return candidate.isBlank() ? null : candidate.toLowerCase(Locale.ROOT);
    }
}
