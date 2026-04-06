package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QueryExpansionService {

    private final Map<String, VocabEntry> canonicalIndex = new HashMap<>();
    private final Map<String, String> aliasToCanonical = new HashMap<>();

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
        queries.add(analysis.mainQuestion());

        if (analysis.subQuestions() != null) {
            queries.addAll(analysis.subQuestions());
        }

        Set<String> expandedTerms = new LinkedHashSet<>();
        collectExpansions(analysis.keyEntities(), expandedTerms);
        collectExpansions(analysis.keyConcepts(), expandedTerms);

        for (String term : expandedTerms) {
            queries.add(analysis.mainQuestion() + " " + term);
        }

        List<String> result = new ArrayList<>(queries);
        log.info("Query expansion: {} original queries → {} expanded queries", 
                1 + (analysis.subQuestions() == null ? 0 : analysis.subQuestions().size()),
                result.size());
        return result;
    }

    public String findCanonical(String term) {
        if (term == null) return null;
        String key = term.toLowerCase(Locale.ROOT).trim();
        String canonical = aliasToCanonical.get(key);
        return canonical != null ? canonical : key;
    }

    private void collectExpansions(List<String> terms, Set<String> out) {
        if (terms == null) return;
        for (String term : terms) {
            String key = term.toLowerCase(Locale.ROOT).trim();
            String canonical = aliasToCanonical.get(key);
            if (canonical != null) {
                VocabEntry entry = canonicalIndex.get(canonical);
                if (entry != null) {
                    if (entry.aliases() != null) {
                        out.addAll(entry.aliases());
                    }
                    if (entry.related() != null) {
                        out.addAll(entry.related());
                    }
                }
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VocabFile(List<VocabEntry> terms) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VocabEntry(
            String canonical,
            List<String> aliases,
            List<String> related
    ) {}
}
