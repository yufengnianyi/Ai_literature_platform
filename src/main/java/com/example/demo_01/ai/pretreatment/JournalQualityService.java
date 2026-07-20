package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.JournalQuality;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.JournalQualityTier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class JournalQualityService {

    private static final String ISSN_PREFIX = "issn:";

    public Map<String, JournalQuality> load(Path csvPath) {
        Map<String, JournalQuality> values = new HashMap<>();
        if (!Files.isRegularFile(csvPath)) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(csvPath);
            if (lines.isEmpty()) {
                return values;
            }
            Map<String, Integer> headers = headers(lines.get(0));
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(",", -1);
                if (cells.length == 0) {
                    continue;
                }
                String journal = normalize(cell(cells, headers, "journal"));
                if (journal.isBlank()) {
                    continue;
                }
                String casPartition = blankToNull(cell(cells, headers, "cas_partition"));
                JournalQualityTier tier = casPartition == null
                        ? parseTier(cell(cells, headers, "quality_tier"))
                        : tierFromCasPartition(casPartition);
                String source = blankToNull(cell(cells, headers, "source"));
                String note = blankToNull(cell(cells, headers, "note"));
                JournalQuality quality = new JournalQuality(tier, isTrusted(tier, casPartition), casPartition, source, note);
                values.put(journal, quality);
                addIssn(values, cell(cells, headers, "issn"), quality);
                addIssn(values, cell(cells, headers, "eissn"), quality);
            }
            return values;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read journal quality CSV: " + csvPath, e);
        }
    }

    public JournalQuality match(String journal, Map<String, JournalQuality> qualityMap) {
        if (journal == null || journal.isBlank() || qualityMap == null || qualityMap.isEmpty()) {
            return JournalQuality.unknown();
        }
        return qualityMap.getOrDefault(normalize(journal), JournalQuality.unknown());
    }

    public JournalQuality matchByIssnOrName(List<String> issns, String journal, Map<String, JournalQuality> qualityMap) {
        if (qualityMap == null || qualityMap.isEmpty()) {
            return JournalQuality.unknown();
        }
        if (issns != null) {
            for (String issn : issns) {
                JournalQuality match = qualityMap.get(ISSN_PREFIX + normalizeIssn(issn));
                if (match != null) {
                    return match;
                }
            }
        }
        return match(journal, qualityMap);
    }

    String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("&", "and")
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private JournalQualityTier parseTier(String value) {
        if (value == null || value.isBlank()) {
            return JournalQualityTier.UNKNOWN;
        }
        try {
            return JournalQualityTier.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return JournalQualityTier.UNKNOWN;
        }
    }

    private JournalQualityTier tierFromCasPartition(String value) {
        String normalized = normalizePartition(value);
        return switch (normalized) {
            case "Q1", "1", "Q2", "2" -> JournalQualityTier.HIGH;
            case "Q3", "3" -> JournalQualityTier.MEDIUM;
            case "Q4", "4" -> JournalQualityTier.LOW;
            default -> JournalQualityTier.UNKNOWN;
        };
    }

    private boolean isTrusted(JournalQualityTier tier, String casPartition) {
        if (casPartition == null || casPartition.isBlank()) {
            return tier == JournalQualityTier.HIGH;
        }
        String normalized = normalizePartition(casPartition);
        return normalized.equals("Q1") || normalized.equals("Q2")
                || normalized.equals("1") || normalized.equals("2");
    }

    private String normalizePartition(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim().toUpperCase(Locale.ROOT)
                .replace("一区", "1")
                .replace("二区", "2")
                .replace("三区", "3")
                .replace("四区", "4")
                .replace("1区", "1")
                .replace("2区", "2")
                .replace("3区", "3")
                .replace("4区", "4")
                .replace("ZONE", "")
                .replace("区", "")
                .trim();
        if (text.matches("[1-4]") || text.matches("Q[1-4]")) {
            return text;
        }
        return text;
    }

    private Map<String, Integer> headers(String headerLine) {
        String[] names = headerLine.split(",", -1);
        Map<String, Integer> headers = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            headers.put(names[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return headers;
    }

    private String cell(String[] cells, Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        if (index == null || index < 0 || index >= cells.length) {
            return "";
        }
        return cells[index];
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void addIssn(Map<String, JournalQuality> values, String issn, JournalQuality quality) {
        String normalized = normalizeIssn(issn);
        if (!normalized.isBlank()) {
            values.put(ISSN_PREFIX + normalized, quality);
        }
    }

    private String normalizeIssn(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ROOT).replaceAll("[^0-9X]", "");
    }
}
