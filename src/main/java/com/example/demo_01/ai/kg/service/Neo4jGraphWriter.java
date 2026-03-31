package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.kg.KgProperties;
import com.example.demo_01.ai.kg.model.KgModels.EntityPayload;
import com.example.demo_01.ai.kg.model.KgModels.PaperGraphPayload;
import com.example.demo_01.ai.kg.model.KgModels.PassagePayload;
import com.example.demo_01.ai.kg.model.KgModels.RelationPayload;
import com.example.demo_01.ai.kg.model.KgModels.RelationType;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link PaperGraphPayload} directly into Neo4j using the Java Driver,
 * producing the exact node/relationship structure that
 * {@link com.example.demo_01.ai.kg.retrieval.Neo4jGraphContentRetriever} expects.
 */
@Slf4j
@Component
public class Neo4jGraphWriter {

    private static final EnumSet<RelationType> ALLOWED_RELATION_TYPES = EnumSet.allOf(RelationType.class);

    private final ObjectProvider<Driver> driverProvider;
    private final KgProperties properties;

    public Neo4jGraphWriter(ObjectProvider<Driver> driverProvider, KgProperties properties) {
        this.driverProvider = driverProvider;
        this.properties = properties;
    }

    /**
     * @return true if nodes were written, false if skipped (driver unavailable or KG disabled)
     */
    public boolean write(PaperGraphPayload payload) {
        Driver driver = driverProvider.getIfAvailable();
        if (!properties.isEnabled() || driver == null) {
            log.debug("Neo4jGraphWriter skipped: enabled={}, driver={}", properties.isEnabled(), driver != null);
            return false;
        }

        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                mergePaper(tx, payload);

                for (PassagePayload passage : payload.passages()) {
                    mergePassage(tx, payload, passage);
                }

                for (EntityPayload entity : payload.entities()) {
                    mergeEntity(tx, entity);
                    linkEntityToPassages(tx, entity);
                }

                for (RelationPayload relation : payload.relations()) {
                    mergeRelation(tx, relation);
                }
                return null;
            });
            log.info("Neo4j graph written for document {} — {} entities, {} relations, {} passages",
                    payload.documentId(), payload.entities().size(),
                    payload.relations().size(), payload.passages().size());
            return true;
        } catch (Exception ex) {
            log.error("Failed to write graph to Neo4j for document {}", payload.documentId(), ex);
            throw new IllegalStateException("Neo4j graph write failed for document " + payload.documentId(), ex);
        }
    }

    private void mergePaper(org.neo4j.driver.Transaction tx, PaperGraphPayload payload) {
        tx.run("""
                MERGE (paper:Paper {document_id: $documentId})
                SET paper.title          = $title,
                    paper.doi            = $doi,
                    paper.canonical_key  = $canonicalKey,
                    paper.publication_year = $publicationYear,
                    paper.schema_version = $schemaVersion
                """,
                Values.parameters(
                        "documentId", payload.documentId().toString(),
                        "title", payload.title(),
                        "doi", payload.doi(),
                        "canonicalKey", payload.canonicalKey(),
                        "publicationYear", payload.publicationYear(),
                        "schemaVersion", payload.schemaVersion()
                ));
    }

    private void mergePassage(org.neo4j.driver.Transaction tx,
                              PaperGraphPayload payload,
                              PassagePayload passage) {
        tx.run("""
                MERGE (p:Passage {chunk_id: $chunkId})
                SET p.text          = $text,
                    p.section_path  = $sectionPath,
                    p.chunk_index   = $chunkIndex
                WITH p
                MATCH (paper:Paper {document_id: $documentId})
                MERGE (paper)-[:HAS_PASSAGE]->(p)
                """,
                Values.parameters(
                        "chunkId", passage.chunkId(),
                        "text", passage.text(),
                        "sectionPath", passage.sectionPath(),
                        "chunkIndex", passage.chunkIndex(),
                        "documentId", payload.documentId().toString()
                ));
    }

    private void mergeEntity(org.neo4j.driver.Transaction tx, EntityPayload entity) {
        tx.run("""
                MERGE (e:Entity {normalized_key: $normalizedKey})
                SET e.canonical_name = $canonicalName,
                    e.entity_type    = $entityType,
                    e.aliases        = $aliases,
                    e.confidence     = $confidence
                """,
                Values.parameters(
                        "normalizedKey", entity.normalizedKey(),
                        "canonicalName", entity.canonicalName(),
                        "entityType", entity.entityType().name(),
                        "aliases", entity.aliases() != null ? entity.aliases() : List.of(),
                        "confidence", entity.confidence()
                ));
    }

    private void linkEntityToPassages(org.neo4j.driver.Transaction tx, EntityPayload entity) {
        if (entity.chunkIds() == null || entity.chunkIds().isEmpty()) {
            return;
        }
        tx.run("""
                MATCH (e:Entity {normalized_key: $normalizedKey})
                UNWIND $chunkIds AS cid
                MATCH (p:Passage {chunk_id: cid})
                MERGE (e)-[:MENTIONED_IN]->(p)
                """,
                Values.parameters(
                        "normalizedKey", entity.normalizedKey(),
                        "chunkIds", entity.chunkIds()
                ));
    }

    private void mergeRelation(org.neo4j.driver.Transaction tx, RelationPayload relation) {
        if (!ALLOWED_RELATION_TYPES.contains(relation.relationType())) {
            log.warn("Skipping unknown relation type: {}", relation.relationType());
            return;
        }
        String relType = relation.relationType().name();
        // Neo4j does not support parameterised relationship types, so we inject the
        // validated enum name directly. This is safe because RelationType is a closed enum.
        String cypher = String.format("""
                MATCH (head:Entity {normalized_key: $headKey})
                MATCH (tail:Entity {normalized_key: $tailKey})
                MERGE (head)-[r:%s]->(tail)
                SET r.confidence = $confidence
                """, relType);
        tx.run(cypher,
                Values.parameters(
                        "headKey", relation.headNormalizedKey(),
                        "tailKey", relation.tailNormalizedKey(),
                        "confidence", relation.confidence()
                ));
    }
}
