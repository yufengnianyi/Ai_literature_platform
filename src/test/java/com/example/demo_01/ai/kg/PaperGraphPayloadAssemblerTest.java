package com.example.demo_01.ai.kg;

import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.ChunkRelationExtraction;
import com.example.demo_01.ai.kg.model.KgModels.EntityType;
import com.example.demo_01.ai.kg.model.KgModels.PaperGraphPayload;
import com.example.demo_01.ai.kg.model.KgModels.RelationType;
import com.example.demo_01.ai.kg.service.PaperGraphPayloadAssembler;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperGraphPayloadAssemblerTest {

    private final PaperGraphPayloadAssembler assembler = new PaperGraphPayloadAssembler();

    @Test
    void assembleShouldMergeDuplicateEntitiesAndRelationsWithinPaper() {
        UUID documentId = UUID.randomUUID();
        KgProperties properties = new KgProperties();
        List<RagChunk> chunks = List.of(
                new RagChunk(documentId, "doi:test", "10.1000/test", "chunk-1", 1, "body", "Intro", 1, 1, 1,
                        "Paper", "FLS2 is an LRR-RLK receptor.", "paper.pdf", "paper.tei.xml", "v1"),
                new RagChunk(documentId, "doi:test", "10.1000/test", "chunk-2", 2, "body", "Results", 1, 1, 1,
                        "Paper", "FLS2 responds to flagellin.", "paper.pdf", "paper.tei.xml", "v1")
        );

        List<ChunkEntityExtraction> entities = List.of(
                new ChunkEntityExtraction(documentId, "chunk-1", "FLS2", "FLS2", EntityType.GENE_OR_PROTEIN, "fls2", List.of("AtFLS2"),
                        "FLS2 is", 0.81),
                new ChunkEntityExtraction(documentId, "chunk-2", "FLS2", "FLAGELLIN-SENSING 2", EntityType.GENE_OR_PROTEIN, "fls2", List.of("FLS2"),
                        "FLS2 responds", 0.88),
                new ChunkEntityExtraction(documentId, "chunk-1", "LRR-RLK", "LRR-RLK", EntityType.RLK_FAMILY, "lrr_rlk", List.of(),
                        "LRR-RLK receptor", 0.70)
        );

        List<ChunkRelationExtraction> relations = List.of(
                new ChunkRelationExtraction(documentId, "chunk-1", "fls2", RelationType.BELONGS_TO_FAMILY, "lrr_rlk", "FLS2 is an LRR-RLK receptor", 0.82),
                new ChunkRelationExtraction(documentId, "chunk-2", "fls2", RelationType.BELONGS_TO_FAMILY, "lrr_rlk", "FLS2 responds", 0.64)
        );

        PaperGraphPayload payload = assembler.assemble(
                documentId,
                "doi:test",
                new RagDocumentMetadata("10.1000/test", "10.1000/test", "Paper", List.of(), List.of(), null, null, null, 2024),
                chunks,
                entities,
                relations,
                properties
        );

        assertEquals(2, payload.entities().size());
        assertEquals(1, payload.relations().size());
        assertEquals(2, payload.passages().size());
        assertEquals("FLAGELLIN-SENSING 2", payload.entities().stream()
                .filter(entity -> entity.normalizedKey().equals("fls2"))
                .findFirst()
                .orElseThrow()
                .canonicalName());
        assertEquals(2, payload.relations().get(0).chunkIds().size());
    }
}
