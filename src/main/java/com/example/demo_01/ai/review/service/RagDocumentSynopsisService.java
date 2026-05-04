package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentSynopsis;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class RagDocumentSynopsisService {

    private static final Pattern SPECIES_PATTERN = Pattern.compile("\\b(?:Phytophthora\\s+[A-Za-z-]+|P\\.\\s*[A-Za-z-]+)\\b");
    private static final Pattern GENE_PATTERN = Pattern.compile("\\b(?:[A-Z][A-Za-z]{1,6}[0-9][A-Za-z0-9_-]*|[A-Z]{2,}[0-9A-Za-z_-]{1,10})\\b");
    private static final Set<String> GENE_STOPWORDS = Set.of(
            "DNA", "RNA", "RNASEQ", "CRISPR", "JSON", "TEI", "PDF", "RT", "PCR", "DMSO", "GUS", "CWDE"
    );

    @Resource
    private RagDocumentRepository ragDocumentRepository;

    @Resource
    private QueryExpansionService queryExpansionService;

    @Resource
    private ObjectMapper objectMapper;

    public void backfillMissingSynopses(int limit) {
        List<RagDocumentRecord> documents = ragDocumentRepository.findCompletedWithoutSynopsis(limit);
        for (RagDocumentRecord document : documents) {
            try {
                RagDocumentSynopsis synopsis = buildSynopsis(document);
                if (synopsis != null && synopsis.searchableText() != null && !synopsis.searchableText().isBlank()) {
                    ragDocumentRepository.updateSynopsis(document.documentId(), synopsis);
                }
            } catch (Exception e) {
                log.warn("Failed to build synopsis for document {}: {}", document.documentId(), e.getMessage());
            }
        }
        if (!documents.isEmpty()) {
            log.info("Backfilled {} document synopses at {}", documents.size(), Instant.now());
        }
    }

    RagDocumentSynopsis buildSynopsis(RagDocumentRecord document) {
        List<ChunkSnippet> snippets = loadSnippets(document);
        String sourceText = buildSourceText(document, snippets);
        List<String> species = mergeDistinct(
                queryExpansionService.findCanonicalTerms(sourceText, "SPECIES"),
                regexMatches(sourceText, SPECIES_PATTERN)
        );
        List<String> genes = mergeDistinct(
                queryExpansionService.findCanonicalTerms(sourceText, "GENE", "GENE_FAMILY"),
                regexGeneMatches(sourceText)
        );
        List<String> processes = queryExpansionService.findCanonicalTerms(sourceText, "PROCESS");
        List<String> stages = queryExpansionService.findCanonicalTerms(sourceText, "STAGE");
        List<String> methods = queryExpansionService.findCanonicalTerms(sourceText, "METHOD");
        List<String> keyFindings = snippets.stream()
                .map(snippet -> firstSentence(snippet.text()))
                .filter(text -> text != null && !text.isBlank())
                .limit(4)
                .toList();
        List<String> innovationPoints = snippets.stream()
                .filter(snippet -> isNoveltySection(snippet.section()))
                .map(snippet -> firstSentence(snippet.text()))
                .filter(text -> text != null && !text.isBlank())
                .limit(3)
                .toList();
        List<String> limitations = snippets.stream()
                .filter(snippet -> snippet.text().toLowerCase(Locale.ROOT).contains("limit")
                        || snippet.text().contains("仍需")
                        || snippet.text().contains("future"))
                .map(snippet -> firstSentence(snippet.text()))
                .filter(text -> text != null && !text.isBlank())
                .limit(3)
                .toList();

        String summary = buildSummary(document, species, genes, processes, methods, keyFindings);
        String searchableText = String.join(" ",
                safe(document.title()),
                safe(document.journal()),
                safe(document.abstractText()),
                String.join(" ", species),
                String.join(" ", genes),
                String.join(" ", processes),
                String.join(" ", stages),
                String.join(" ", methods),
                String.join(" ", keyFindings),
                String.join(" ", innovationPoints),
                snippets.stream().map(ChunkSnippet::section).filter(s -> s != null && !s.isBlank()).distinct().limit(8).reduce("", (a, b) -> a + " " + b),
                snippets.stream().map(ChunkSnippet::text).limit(8).reduce("", (a, b) -> a + " " + b)
        ).replaceAll("\\s+", " ").trim();

        return new RagDocumentSynopsis(
                summary,
                species,
                genes,
                processes,
                stages,
                methods,
                keyFindings,
                innovationPoints,
                limitations,
                searchableText
        );
    }

    private List<ChunkSnippet> loadSnippets(RagDocumentRecord document) {
        if (document.storageRoot() == null || document.storageRoot().isBlank()) {
            return List.of();
        }
        Path jsonlPath = Path.of(document.storageRoot()).resolve("document.jsonl");
        if (!Files.exists(jsonlPath)) {
            return List.of();
        }
        List<ChunkSnippet> snippets = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                String section = text(node, "section_path");
                String contentType = text(node, "content_type");
                String text = text(node, "text");
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (preferSection(section, contentType) || snippets.size() < 10) {
                    snippets.add(new ChunkSnippet(section, contentType, collapse(text)));
                }
                if (snippets.size() >= 16) {
                    break;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load synopsis snippets for {}: {}", document.documentId(), e.getMessage());
        }
        return snippets;
    }

    private boolean preferSection(String section, String contentType) {
        String value = (safe(section) + " " + safe(contentType)).toLowerCase(Locale.ROOT);
        return value.contains("abstract")
                || value.contains("intro")
                || value.contains("discussion")
                || value.contains("result")
                || value.contains("conclusion");
    }

    private boolean isNoveltySection(String section) {
        String value = safe(section).toLowerCase(Locale.ROOT);
        return value.contains("discussion") || value.contains("conclusion") || value.contains("result");
    }

    private String buildSourceText(RagDocumentRecord document, List<ChunkSnippet> snippets) {
        return String.join(" ",
                safe(document.title()),
                safe(document.abstractText()),
                safe(document.journal()),
                snippets.stream().map(ChunkSnippet::section).reduce("", (a, b) -> a + " " + b),
                snippets.stream().map(ChunkSnippet::text).reduce("", (a, b) -> a + " " + b)
        ).replaceAll("\\s+", " ").trim();
    }

    private String buildSummary(RagDocumentRecord document,
                                List<String> species,
                                List<String> genes,
                                List<String> processes,
                                List<String> methods,
                                List<String> keyFindings) {
        List<String> parts = new ArrayList<>();
        if (document.title() != null && !document.title().isBlank()) {
            parts.add(document.title());
        }
        if (!species.isEmpty()) {
            parts.add("Species: " + String.join(", ", species));
        }
        if (!genes.isEmpty()) {
            parts.add("Genes/proteins: " + String.join(", ", genes.stream().limit(6).toList()));
        }
        if (!processes.isEmpty()) {
            parts.add("Processes: " + String.join(", ", processes.stream().limit(6).toList()));
        }
        if (!methods.isEmpty()) {
            parts.add("Methods: " + String.join(", ", methods.stream().limit(4).toList()));
        }
        if (!keyFindings.isEmpty()) {
            parts.add("Findings: " + String.join(" ", keyFindings.stream().limit(2).toList()));
        }
        return String.join(". ", parts);
    }

    private List<String> regexMatches(String text, Pattern pattern) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(queryExpansionService.findCanonical(matcher.group()));
        }
        return new ArrayList<>(matches);
    }

    private List<String> regexGeneMatches(String text) {
        Set<String> matches = new LinkedHashSet<>();
        Matcher matcher = GENE_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            String upper = token.toUpperCase(Locale.ROOT);
            if (GENE_STOPWORDS.contains(upper) || token.length() < 3) {
                continue;
            }
            matches.add(token);
        }
        return new ArrayList<>(matches);
    }

    private List<String> mergeDistinct(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return new ArrayList<>(merged);
    }

    private String firstSentence(String text) {
        String normalized = collapse(text);
        if (normalized.isBlank()) {
            return null;
        }
        int boundary = normalized.indexOf(". ");
        if (boundary < 0) {
            boundary = normalized.indexOf("。");
        }
        if (boundary < 0) {
            return normalized.length() > 220 ? normalized.substring(0, 220) : normalized;
        }
        return normalized.substring(0, Math.min(boundary + 1, normalized.length()));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String collapse(String text) {
        return safe(text).replaceAll("\\s+", " ").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ChunkSnippet(String section, String contentType, String text) {
    }
}
