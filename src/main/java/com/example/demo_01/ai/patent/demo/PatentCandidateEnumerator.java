package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.CandidateEnumeration;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
public class PatentCandidateEnumerator {

    private static final List<Charset> CSV_CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GB18030")
    );

    public CandidateEnumeration enumerate(Path manifestPath,
                                          Path pdfRoot,
                                          String category,
                                          int limit,
                                          Long seed) {
        return enumerate(manifestPath, pdfRoot, category, limit, seed, List.of());
    }

    public CandidateEnumeration enumerate(Path manifestPath,
                                          Path pdfRoot,
                                          String category,
                                          int limit,
                                          Long seed,
                                          List<String> publicationNumbers) {
        Set<String> whitelist = publicationNumbers == null ? Set.of() : Set.copyOf(publicationNumbers);
        List<Map<String, String>> rows = readRows(manifestPath);
        List<PatentCandidate> candidates = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String rowCategory = value(row, "simple_category");
            if (!category.equals(rowCategory)) {
                continue;
            }
            String publicationNumber = value(row, "publication_number");
            if (publicationNumber.isBlank()) {
                continue;
            }
            if (!whitelist.isEmpty() && !whitelist.contains(publicationNumber)) {
                continue;
            }
            Path pdfPath = resolvePdfPath(value(row, "pdf_path"), pdfRoot, publicationNumber);
            if (pdfPath != null && Files.isRegularFile(pdfPath)) {
                candidates.add(new PatentCandidate(
                        publicationNumber,
                        value(row, "title"),
                        rowCategory,
                        pdfPath.toAbsolutePath().normalize()));
            }
        }
        List<PatentCandidate> selected = new ArrayList<>(candidates);
        if (seed != null) {
            Collections.shuffle(selected, new Random(seed));
        }
        if (limit > 0 && selected.size() > limit) {
            selected = new ArrayList<>(selected.subList(0, limit));
        }
        return new CandidateEnumeration(candidates.size(), List.copyOf(selected));
    }

    private List<Map<String, String>> readRows(Path manifestPath) {
        for (Charset charset : CSV_CHARSETS) {
            try {
                return readRows(manifestPath, charset);
            } catch (IOException ignored) {
                // Try the next configured export encoding.
            }
        }
        throw new IllegalStateException("Failed to read patent manifest: " + manifestPath);
    }

    private List<Map<String, String>> readRows(Path manifestPath, Charset charset) throws IOException {
        List<String> records = logicalRecords(manifestPath, charset);
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> headers = parseCsvRecord(records.get(0));
        if (!headers.isEmpty()) {
            headers.set(0, stripBom(headers.get(0)));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = parseCsvRecord(records.get(i));
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String key = headers.get(c).trim().toLowerCase(Locale.ROOT);
                row.put(key, c < cells.size() ? cells.get(c).trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> logicalRecords(Path manifestPath, Charset charset) throws IOException {
        List<String> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(manifestPath, charset)) {
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(line);
                quoted = updateQuoteState(line, quoted);
                if (!quoted) {
                    records.add(current.toString());
                    current.setLength(0);
                }
            }
            if (!current.isEmpty()) {
                records.add(current.toString());
            }
        }
        return records;
    }

    private boolean updateQuoteState(CharSequence text, boolean quoted) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            }
        }
        return quoted;
    }

    private List<String> parseCsvRecord(String record) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < record.length(); i++) {
            char ch = record.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < record.length() && record.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private Path resolvePdfPath(String pdfPath, Path pdfRoot, String publicationNumber) {
        if (!pdfPath.isBlank()) {
            Path path = Path.of(pdfPath);
            return path.isAbsolute() ? path : pdfRoot.resolve(path).normalize();
        }
        if (pdfRoot == null || publicationNumber.isBlank()) {
            return null;
        }
        return pdfRoot.resolve(publicationNumber + ".pdf").normalize();
    }

    private String value(Map<String, String> row, String key) {
        return row.getOrDefault(key, "");
    }

    private String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF'
                ? value.substring(1)
                : value;
    }
}
