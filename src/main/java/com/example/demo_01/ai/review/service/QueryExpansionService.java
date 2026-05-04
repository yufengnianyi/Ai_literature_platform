package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QueryExpansionService {

    private static final int MAX_EXPANDED_QUERIES = 24;

    private final Map<String, VocabEntry> canonicalIndex = new HashMap<>();
    private final Map<String, String> aliasToCanonical = new HashMap<>();
    private final Map<String, String> canonicalToCategory = new HashMap<>();

    @PostConstruct
    void loadVocabulary() {
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            ClassPathResource resource = new ClassPathResource("review/domain-vocab.yml");
            try (InputStream is = resource.getInputStream()) {
                VocabFile vocab = yamlMapper.readValue(is, VocabFile.class);
                if (vocab.terms() != null) {
                    for (VocabEntry entry : vocab.terms()) {
                        String key = entry.canonical().toLowerCase(Locale.ROOT).trim();
                        canonicalIndex.put(key, entry);
                        canonicalToCategory.put(key, normalizeCategory(entry.category()));
                        aliasToCanonical.put(key, key);
                        if (entry.aliases() != null) {
                            for (String alias : entry.aliases()) {
                                aliasToCanonical.put(alias.toLowerCase(Locale.ROOT).trim(), key);
                            }
                        }
                    }
                }
            }
            log.info("Loaded domain vocabulary: {} terms, {} aliases",
                    canonicalIndex.size(), aliasToCanonical.size());
        } catch (Exception e) {
            log.warn("Failed to load domain vocabulary, query expansion disabled: {}", e.getMessage());
        }
    }

    public List<String> expand(QueryAnalysis analysis) {
        Set<String> queries = new LinkedHashSet<>();
        addQuery(queries, analysis.mainQuestion());

        if (analysis.subQuestions() != null) {
            analysis.subQuestions().forEach(query -> addQuery(queries, query));
        }

        QueryBuckets buckets = collectQueryBuckets(analysis);
        Set<String> expandedTerms = new LinkedHashSet<>();
        collectExpansions(analysis.keyEntities(), expandedTerms);
        collectExpansions(analysis.keyConcepts(), expandedTerms);

        queries.addAll(buildKeywordQueries(buckets));
        for (String term : expandedTerms) {
            addQuery(queries, joinTerms(analysis.mainQuestion(), term));
        }
        queries.addAll(buildTranslatedQueries(queries));

        List<String> result = queries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(query -> !query.isBlank())
                .limit(MAX_EXPANDED_QUERIES)
                .toList();
        log.info("Query expansion: {} original queries -> {} expanded queries",
                1 + (analysis.subQuestions() == null ? 0 : analysis.subQuestions().size()),
                result.size());
        return result;
    }

    public String findCanonical(String term) {
        if (term == null) {
            return null;
        }
        String key = term.toLowerCase(Locale.ROOT).trim();
        String canonical = aliasToCanonical.get(key);
        return canonical != null ? canonical : key;
    }

    public List<String> findCanonicalTerms(String text, String... categories) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> wantedCategories = Arrays.stream(categories)
                .map(this::normalizeCategory)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String normalizedText = text.toLowerCase(Locale.ROOT);
        Set<String> matches = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : aliasToCanonical.entrySet()) {
            String alias = entry.getKey();
            String canonical = entry.getValue();
            if (alias.length() < 2) {
                continue;
            }
            if (!wantedCategories.isEmpty() && !wantedCategories.contains(canonicalToCategory.get(canonical))) {
                continue;
            }
            if (normalizedText.contains(alias)) {
                matches.add(canonical);
            }
        }
        return new ArrayList<>(matches);
    }

    private QueryBuckets collectQueryBuckets(QueryAnalysis analysis) {
        Set<String> species = new LinkedHashSet<>();
        Set<String> processes = new LinkedHashSet<>();
        Set<String> stages = new LinkedHashSet<>();
        Set<String> genes = new LinkedHashSet<>();
        Set<String> geneFamilies = new LinkedHashSet<>();

        addCategorizedTerms(analysis.keyEntities(), species, "SPECIES");
        addCategorizedTerms(analysis.keyEntities(), genes, "GENE");
        addCategorizedTerms(analysis.keyEntities(), geneFamilies, "GENE_FAMILY");

        addCategorizedTerms(analysis.keyConcepts(), processes, "PROCESS");
        addCategorizedTerms(analysis.keyConcepts(), stages, "STAGE");
        addCategorizedTerms(analysis.keyConcepts(), geneFamilies, "GENE_FAMILY");

        addDetectedTerms(analysis.mainQuestion(), species, "SPECIES");
        addDetectedTerms(analysis.mainQuestion(), processes, "PROCESS");
        addDetectedTerms(analysis.mainQuestion(), stages, "STAGE");
        addDetectedTerms(analysis.mainQuestion(), genes, "GENE");
        addDetectedTerms(analysis.mainQuestion(), geneFamilies, "GENE_FAMILY");

        if (analysis.subQuestions() != null) {
            for (String subQuestion : analysis.subQuestions()) {
                addDetectedTerms(subQuestion, species, "SPECIES");
                addDetectedTerms(subQuestion, processes, "PROCESS");
                addDetectedTerms(subQuestion, stages, "STAGE");
                addDetectedTerms(subQuestion, genes, "GENE");
                addDetectedTerms(subQuestion, geneFamilies, "GENE_FAMILY");
            }
        }

        return new QueryBuckets(species, processes, stages, genes, geneFamilies, looksLikeGeneReview(analysis));
    }

    private void addCategorizedTerms(List<String> input, Set<String> output, String category) {
        if (input == null) {
            return;
        }
        for (String item : input) {
            String canonical = findCanonical(item);
            if (canonical != null && category.equals(canonicalToCategory.get(canonical))) {
                output.add(canonical);
            }
        }
    }

    private void addDetectedTerms(String text, Set<String> output, String category) {
        output.addAll(findCanonicalTerms(text, category));
    }

    private void collectExpansions(List<String> terms, Set<String> out) {
        if (terms == null) {
            return;
        }
        for (String term : terms) {
            String key = term.toLowerCase(Locale.ROOT).trim();
            String canonical = aliasToCanonical.get(key);
            if (canonical == null) {
                continue;
            }
            VocabEntry entry = canonicalIndex.get(canonical);
            if (entry == null) {
                continue;
            }
            if (entry.aliases() != null) {
                out.addAll(entry.aliases());
            }
            if (entry.related() != null) {
                out.addAll(entry.related());
            }
        }
    }

    private List<String> buildKeywordQueries(QueryBuckets buckets) {
        Set<String> queries = new LinkedHashSet<>();

        for (String species : buckets.species()) {
            addQuery(queries, species);
            if (buckets.geneReview()) {
                addQuery(queries, joinTerms(species, "gene"));
                addQuery(queries, joinTerms(species, "protein"));
            }
        }

        for (String process : buckets.processes()) {
            addQuery(queries, process);
            if (buckets.geneReview()) {
                addQuery(queries, joinTerms(process, "gene"));
            }
        }

        for (String stage : buckets.stages()) {
            addQuery(queries, stage);
            if (buckets.geneReview()) {
                addQuery(queries, joinTerms(stage, "gene"));
            }
        }

        buckets.geneFamilies().forEach(family -> addQuery(queries, family));
        buckets.genes().forEach(gene -> addQuery(queries, gene));

        for (String species : buckets.species()) {
            for (String process : buckets.processes()) {
                addQuery(queries, joinTerms(species, process));
                if (buckets.geneReview()) {
                    addQuery(queries, joinTerms(species, process, "gene"));
                }
            }
            for (String stage : buckets.stages()) {
                addQuery(queries, joinTerms(species, stage));
                if (buckets.geneReview()) {
                    addQuery(queries, joinTerms(species, stage, "gene"));
                }
            }
            for (String family : buckets.geneFamilies()) {
                addQuery(queries, joinTerms(species, family));
            }
        }

        return new ArrayList<>(queries);
    }

    private List<String> buildTranslatedQueries(Set<String> queries) {
        Set<String> expanded = new LinkedHashSet<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            Set<String> translations = new LinkedHashSet<>();
            translations.addAll(findCanonicalTerms(query, "PROCESS", "STAGE"));
            translations.addAll(findCanonicalTerms(query, "GENE", "GENE_FAMILY"));
            translations.addAll(findCanonicalTerms(query, "SPECIES"));
            if (!translations.isEmpty()) {
                addQuery(expanded, joinTerms(query, String.join(" ", translations)));
            }
        }
        return new ArrayList<>(expanded);
    }

    private void addQuery(Set<String> queries, String query) {
        if (query == null) {
            return;
        }
        String normalized = query.trim().replaceAll("\\s+", " ");
        if (!normalized.isBlank()) {
            queries.add(normalized);
        }
    }

    private String joinTerms(String... parts) {
        return Arrays.stream(parts)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(" "));
    }

    private boolean looksLikeGeneReview(QueryAnalysis analysis) {
        String combined = String.join(" ",
                analysis.mainQuestion() == null ? "" : analysis.mainQuestion(),
                analysis.subQuestions() == null ? "" : String.join(" ", analysis.subQuestions()),
                analysis.keyConcepts() == null ? "" : String.join(" ", analysis.keyConcepts()));
        String lower = combined.toLowerCase(Locale.ROOT);
        return lower.contains("gene")
                || lower.contains("protein")
                || combined.contains("基因")
                || combined.contains("蛋白");
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    private record QueryBuckets(
            Set<String> species,
            Set<String> processes,
            Set<String> stages,
            Set<String> genes,
            Set<String> geneFamilies,
            boolean geneReview
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VocabFile(List<VocabEntry> terms) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VocabEntry(
            String canonical,
            String category,
            List<String> aliases,
            List<String> related
    ) {}
}
