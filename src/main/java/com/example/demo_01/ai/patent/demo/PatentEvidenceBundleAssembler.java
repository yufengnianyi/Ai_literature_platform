package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.evidence.table.ParsedTable;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDiscoveryOutcome;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDiscoveryStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentEvidenceBundle;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PatentEvidenceBundleAssembler {

    private final ObjectMapper mapper;

    public PatentEvidenceBundleAssembler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<PatentEvidenceBundle> assemble(PatentTriageResult triage,
                                               List<PatentTableCandidate> candidates,
                                               Path artifactRoot) throws IOException {
        List<ParsedTable> tables = readTables(artifactRoot.resolve("tables.jsonl"));
        List<String> tableRefs = tables.stream().map(ParsedTable::tableRef).toList();
        List<Integer> pages = triage.selectedPages().stream()
                .map(PatentPageAssessment::pageNumber)
                .distinct()
                .sorted()
                .toList();
        List<String> methods = slices(triage, PatentPageRole.TEST_DEFINITION);
        List<String> results = resultSlices(triage);
        List<String> rows = tableRows(tables);
        List<String> missing = new ArrayList<>();
        if (methods.isEmpty()) {
            missing.add("METHOD_CONTEXT");
        }
        if (tables.isEmpty() && candidates.stream().anyMatch(PatentTableCandidate::selectedForRead)) {
            missing.add("SELECTED_TABLE_NOT_RECOVERED");
        }
        if (tables.isEmpty() && results.isEmpty()) {
            missing.add("RESULT_CONTEXT");
        }
        PatentDiscoveryOutcome outcome = tables.isEmpty() && results.isEmpty()
                ? PatentDiscoveryOutcome.NO_EVIDENCE_FOUND : PatentDiscoveryOutcome.EVIDENCE_FOUND;
        PatentDiscoveryStatus status = missing.isEmpty()
                ? PatentDiscoveryStatus.COMPLETE : PatentDiscoveryStatus.REVIEW_REQUIRED;
        if (tables.isEmpty() && candidates.stream().anyMatch(candidate -> candidate.tableKind().name().equals("ACTIVITY"))) {
            outcome = PatentDiscoveryOutcome.UNRESOLVED;
        }
        if (pages.isEmpty()) {
            return List.of();
        }
        return List.of(new PatentEvidenceBundle(
                "B1",
                "assay:1",
                status,
                outcome,
                tableRefs,
                pages,
                methods,
                results,
                rows,
                List.of(),
                List.copyOf(missing)));
    }

    public Path write(Path artifactRoot, List<PatentEvidenceBundle> bundles) throws IOException {
        StringBuilder jsonl = new StringBuilder();
        for (PatentEvidenceBundle bundle : bundles) {
            jsonl.append(mapper.writeValueAsString(bundle)).append('\n');
        }
        Path path = artifactRoot.resolve("patent-evidence-bundles.jsonl");
        Files.writeString(path, jsonl, StandardCharsets.UTF_8);
        return path;
    }

    private List<ParsedTable> readTables(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<ParsedTable> tables = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                tables.add(mapper.readValue(line, ParsedTable.class));
            }
        }
        return List.copyOf(tables);
    }

    private List<String> slices(PatentTriageResult triage, PatentPageRole role) {
        return triage.selectedPages().stream()
                .filter(page -> page.roles().contains(role))
                .sorted(Comparator.comparingInt(PatentPageAssessment::pageNumber))
                .map(page -> truncate(page.text(), 1600))
                .filter(text -> !text.isBlank())
                .toList();
    }

    private List<String> resultSlices(PatentTriageResult triage) {
        return triage.selectedPages().stream()
                .filter(page -> page.roles().contains(PatentPageRole.ACTIVITY_TABLE)
                        || page.roles().contains(PatentPageRole.Q1_ACTIVITY))
                .sorted(Comparator.comparingInt(PatentPageAssessment::pageNumber))
                .map(page -> truncate(page.text(), 1600))
                .filter(text -> !text.isBlank())
                .toList();
    }

    private List<String> tableRows(List<ParsedTable> tables) {
        Set<String> rows = new LinkedHashSet<>();
        for (ParsedTable table : tables) {
            for (int i = 0; i < table.rows().size(); i++) {
                rows.add(table.tableRef() + "#row" + (i + 1) + "=" + table.rows().get(i));
            }
        }
        return List.copyOf(rows);
    }

    private String truncate(String text, int max) {
        String value = text == null ? "" : text.strip();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
