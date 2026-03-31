package com.example.demo_01.ai.kg.retrieval;

import com.example.demo_01.ai.kg.KgProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class Neo4jGraphContentRetriever implements ContentRetriever {

    private final ObjectProvider<Driver> driverProvider;
    private final KgProperties properties;

    public Neo4jGraphContentRetriever(ObjectProvider<Driver> driverProvider, KgProperties properties) {
        this.driverProvider = driverProvider;
        this.properties = properties;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Driver driver = driverProvider.getIfAvailable();
        if (!properties.isEnabled() || driver == null || query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        List<String> terms = Arrays.stream(query.text().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(term -> term.length() > 1)
                .distinct()
                .limit(8)
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }

        try (Session session = driver.session()) {
            return session.readTransaction(tx -> tx.run("""
                            MATCH (e)
                            WHERE e.canonical_name IS NOT NULL
                              AND any(term IN $terms WHERE toLower(e.canonical_name) CONTAINS term
                                  OR any(alias IN coalesce(e.aliases, []) WHERE toLower(alias) CONTAINS term))
                            OPTIONAL MATCH (e)-[:MENTIONED_IN]->(p:Passage)
                            OPTIONAL MATCH (paper:Paper)-[:HAS_PASSAGE]->(p)
                            OPTIONAL MATCH (e)-[r]-(other)
                            WHERE other.canonical_name IS NOT NULL AND type(r) <> 'MENTIONED_IN'
                            RETURN e.canonical_name AS entity,
                                   coalesce(e.entity_type, head(labels(e))) AS entityType,
                                   collect(DISTINCT type(r))[0..5] AS relationTypes,
                                   collect(DISTINCT other.canonical_name)[0..5] AS neighbors,
                                   collect(DISTINCT p.text)[0..2] AS passages,
                                   collect(DISTINCT paper.title)[0..2] AS papers
                            LIMIT $limit
                            """, Values.parameters("terms", terms, "limit", properties.getGraphMaxResults()))
                    .list()
                    .stream()
                    .map(this::toContent)
                    .toList());
        }
    }

    private Content toContent(Record record) {
        String entity = record.get("entity").asString("");
        String entityType = record.get("entityType").asString("");
        List<Object> relations = record.get("relationTypes").asList();
        List<Object> neighbors = record.get("neighbors").asList();
        List<Object> passages = record.get("passages").asList();
        List<Object> papers = record.get("papers").asList();

        StringBuilder builder = new StringBuilder();
        builder.append("Graph evidence for ").append(entity);
        if (!entityType.isBlank()) {
            builder.append(" (").append(entityType).append(")");
        }
        if (!relations.isEmpty() && !neighbors.isEmpty()) {
            builder.append(". Related facts: ").append(relations).append(" -> ").append(neighbors).append(".");
        }
        if (!papers.isEmpty()) {
            builder.append(" Supporting papers: ").append(papers).append(".");
        }
        if (!passages.isEmpty()) {
            builder.append(" Passage evidence: ").append(passages.get(0)).append(".");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("graph_entity", entity);
        metadata.put("graph_entity_type", entityType);
        metadata.put("source", "neo4j");
        return Content.from(
                TextSegment.from(builder.toString(), new dev.langchain4j.data.document.Metadata(metadata)),
                Map.of(ContentMetadata.SCORE, 0.8)
        );
    }
}
