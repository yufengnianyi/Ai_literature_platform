package com.example.demo_01.ai.evidence.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.example.demo_01.ai.evidence.model.EvidenceModels.HEADERS;

@Component
public class MarkdownEvidenceTableParser {

    public ParsedEvidenceTable parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("Model returned an empty evidence table");
        }
        List<String> lines = markdown.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() < 2 || lines.stream().anyMatch(line -> !line.startsWith("|") || !line.endsWith("|"))) {
            throw new IllegalArgumentException("Output must contain one Markdown table and no additional text");
        }

        List<String> headers = splitRow(lines.get(0));
        if (!HEADERS.equals(headers)) {
            throw new IllegalArgumentException("Evidence table headers do not match the required 16-column contract");
        }
        List<String> separator = splitRow(lines.get(1));
        if (separator.size() != HEADERS.size() || separator.stream().anyMatch(cell -> !cell.matches(":?-{3,}:?"))) {
            throw new IllegalArgumentException("Evidence table separator row is invalid");
        }

        Map<String, CompoundEvidenceRow> uniqueRows = new LinkedHashMap<>();
        for (int index = 2; index < lines.size(); index++) {
            List<String> cells = splitRow(lines.get(index));
            if (cells.size() != HEADERS.size()) {
                throw new IllegalArgumentException("Evidence row " + (index - 1) + " must contain exactly 16 cells");
            }
            if (HEADERS.equals(cells)
                    || cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"))) {
                throw new IllegalArgumentException("Output must contain exactly one Markdown table");
            }
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            CompoundEvidenceRow row = CompoundEvidenceRow.fromCells(cells);
            uniqueRows.putIfAbsent(fingerprint(row), row);
        }
        return new ParsedEvidenceTable(List.copyOf(uniqueRows.values()));
    }

    public String fingerprint(CompoundEvidenceRow row) {
        String canonical = String.join("\u001f", row.cells()).trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public String render(List<CompoundEvidenceRow> rows) {
        StringBuilder markdown = new StringBuilder();
        appendRow(markdown, HEADERS);
        appendRow(markdown, java.util.Collections.nCopies(HEADERS.size(), "---"));
        for (CompoundEvidenceRow row : rows == null ? List.<CompoundEvidenceRow>of() : rows) {
            appendRow(markdown, row.cells());
        }
        return markdown.toString();
    }

    private void appendRow(StringBuilder markdown, List<String> cells) {
        markdown.append("| ");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                markdown.append(" | ");
            }
            markdown.append(escape(cells.get(index)));
        }
        markdown.append(" |\n");
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private List<String> splitRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        for (int index = 1; index < line.length() - 1; index++) {
            char value = line.charAt(index);
            if (escaped) {
                cell.append(value);
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value == '|') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
                continue;
            }
            cell.append(value);
        }
        if (escaped) {
            cell.append('\\');
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    public record ParsedEvidenceTable(List<CompoundEvidenceRow> rows) {
    }
}
