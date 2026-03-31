package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.kg.KgProperties;
import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphEdge;
import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphNode;
import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphView;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QuestionGraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(QuestionGraphQueryService.class);

    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private static final int MAX_TERMS = 10;
    private static final int MAX_NEIGHBORS = 4;

    private final ObjectProvider<Driver> driverProvider;
    private final KgProperties properties;

    public QuestionGraphQueryService(ObjectProvider<Driver> driverProvider, KgProperties properties) {
        this.driverProvider = driverProvider;
        this.properties = properties;
    }

    public QuestionGraphView query(String prompt) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        if (normalizedPrompt.isBlank()) {
            return QuestionGraphView.empty(normalizedPrompt, "EMPTY");
        }
        if (!properties.isEnabled()) {
            return QuestionGraphView.empty(normalizedPrompt, "UNAVAILABLE");
        }

        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return QuestionGraphView.empty(normalizedPrompt, "UNAVAILABLE");
        }

        List<String> terms = extractTerms(normalizedPrompt);
        if (terms.isEmpty()) {
            return QuestionGraphView.empty(normalizedPrompt, "EMPTY");
        }

        try (Session session = driver.session()) {
            List<Record> records = session.readTransaction(tx -> tx.run("""
                            MATCH (e)
                            WHERE e.canonical_name IS NOT NULL
                              AND any(term IN $terms WHERE toLower(e.canonical_name) CONTAINS term
                                  OR any(alias IN coalesce(e.aliases, []) WHERE toLower(alias) CONTAINS term))
                            WITH collect(DISTINCT e)[0..$entityLimit] AS hits
                            UNWIND hits AS e
                            OPTIONAL MATCH (e)-[r]-(other)
                            WHERE other.canonical_name IS NOT NULL AND type(r) <> 'MENTIONED_IN'
                            WITH e, collect(DISTINCT {
                                id: coalesce(other.normalized_key, other.canonical_name),
                                label: other.canonical_name,
                                entityType: coalesce(other.entity_type, head(labels(other))),
                                relationType: type(r)
                            })[0..$neighborLimit] AS neighbors
                            OPTIONAL MATCH (e)-[:MENTIONED_IN]->(:Passage)<-[:HAS_PASSAGE]-(paper:Paper)
                            RETURN coalesce(e.normalized_key, e.canonical_name) AS id,
                                   e.canonical_name AS label,
                                   coalesce(e.entity_type, head(labels(e))) AS entityType,
                                   neighbors,
                                   collect(DISTINCT paper.title)[0..3] AS papers
                            """, Values.parameters(
                    "terms", terms,
                    "entityLimit", properties.getGraphMaxResults(),
                    "neighborLimit", MAX_NEIGHBORS
            ))
                    .list());
            return toView(normalizedPrompt, records);
        } catch (Exception ex) {
            log.warn("Question graph query failed for prompt: {}", normalizedPrompt, ex);
            return QuestionGraphView.empty(normalizedPrompt, "UNAVAILABLE");
        }
    }

    private QuestionGraphView toView(String prompt, List<Record> records) {
        if (records == null || records.isEmpty()) {
            return QuestionGraphView.empty(prompt, "EMPTY");
        }

        Map<String, NodeAccumulator> nodes = new LinkedHashMap<>();
        Map<String, QuestionGraphEdge> edges = new LinkedHashMap<>();
        LinkedHashSet<String> matchedEntities = new LinkedHashSet<>();
        LinkedHashSet<String> papers = new LinkedHashSet<>();

        for (Record record : records) {
            String matchedId = record.get("id").asString("");
            String matchedLabel = record.get("label").asString("");
            String matchedType = record.get("entityType").asString("");
            List<String> matchedPapers = record.get("papers").asList(value -> value.asString("")).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList();

            if (matchedId.isBlank() || matchedLabel.isBlank()) {
                continue;
            }

            matchedEntities.add(matchedLabel);
            papers.addAll(matchedPapers);
            nodes.computeIfAbsent(matchedId, key -> new NodeAccumulator(matchedId, matchedLabel, matchedType))
                    .merge(true, matchedPapers);

            for (Object rawNeighbor : record.get("neighbors").asList()) {
                if (!(rawNeighbor instanceof Map<?, ?> neighbor)) {
                    continue;
                }

                String neighborId = stringValue(neighbor.get("id"));
                String neighborLabel = stringValue(neighbor.get("label"));
                String neighborType = stringValue(neighbor.get("entityType"));
                String relationType = stringValue(neighbor.get("relationType"));

                if (neighborId.isBlank() || neighborLabel.isBlank() || relationType.isBlank()) {
                    continue;
                }

                nodes.computeIfAbsent(neighborId, key -> new NodeAccumulator(neighborId, neighborLabel, neighborType))
                        .merge(false, List.of());

                String edgeId = matchedId + "|" + relationType + "|" + neighborId;
                edges.putIfAbsent(edgeId, new QuestionGraphEdge(edgeId, matchedId, neighborId, relationType));
                nodes.get(matchedId).incrementDegree();
                nodes.get(neighborId).incrementDegree();
            }
        }

        if (nodes.isEmpty()) {
            return QuestionGraphView.empty(prompt, "EMPTY");
        }

        List<QuestionGraphNode> graphNodes = nodes.values().stream()
                .map(NodeAccumulator::toNode)
                .toList();

        return new QuestionGraphView(
                prompt,
                "READY",
                new ArrayList<>(matchedEntities),
                graphNodes,
                new ArrayList<>(edges.values()),
                new ArrayList<>(papers)
        );
    }

    private List<String> extractTerms(String prompt) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM_PATTERN.matcher(prompt.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < MAX_TERMS) {
            String term = matcher.group();
            if (term != null && !term.isBlank()) {
                terms.add(term);
            }
        }
        return new ArrayList<>(terms);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text;
    }

    private static final class NodeAccumulator {

        private final String id;
        private final String label;
        private final String entityType;
        private boolean matched;
        private int degree;
        private final LinkedHashSet<String> papers = new LinkedHashSet<>();

        private NodeAccumulator(String id, String label, String entityType) {
            this.id = id;
            this.label = label;
            this.entityType = entityType == null ? "" : entityType;
        }

        private NodeAccumulator merge(boolean nextMatched, List<String> nextPapers) {
            matched = matched || nextMatched;
            if (nextPapers != null) {
                for (String paper : nextPapers) {
                    if (paper != null && !paper.isBlank()) {
                        papers.add(paper);
                    }
                }
            }
            return this;
        }

        private void incrementDegree() {
            degree += 1;
        }

        private QuestionGraphNode toNode() {
            return new QuestionGraphNode(id, label, entityType, matched, degree, new ArrayList<>(papers));
        }
    }
}
