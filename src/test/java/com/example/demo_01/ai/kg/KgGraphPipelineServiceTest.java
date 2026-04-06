package com.example.demo_01.ai.kg;

import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.ChunkRelationExtraction;
import com.example.demo_01.ai.kg.model.KgModels.EntityType;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncResult;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncStatus;
import com.example.demo_01.ai.kg.model.KgModels.PaperGraphPayload;
import com.example.demo_01.ai.kg.model.KgModels.RelationType;
import com.example.demo_01.ai.kg.repository.KgExtractionRepository;
import com.example.demo_01.ai.kg.service.ChunkEntityExtractionService;
import com.example.demo_01.ai.kg.service.ChunkRelationExtractionService;
import com.example.demo_01.ai.kg.service.KgGraphPipelineService;
import com.example.demo_01.ai.kg.service.Neo4jGraphBuilderClient;
import com.example.demo_01.ai.kg.service.Neo4jGraphWriter;
import com.example.demo_01.ai.kg.service.PaperGraphPayloadAssembler;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KgGraphPipelineServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private ChunkEntityExtractionService chunkEntityExtractionService;

    @Mock
    private ChunkRelationExtractionService chunkRelationExtractionService;

    @Mock
    private PaperGraphPayloadAssembler paperGraphPayloadAssembler;

    @Mock
    private Neo4jGraphBuilderClient graphBuilderClient;

    @Mock
    private Neo4jGraphWriter graphWriter;

    @Mock
    private KgExtractionRepository repository;

    private KgGraphPipelineService service;

    @BeforeEach
    void setUp() {
        service = new KgGraphPipelineService();
        KgProperties properties = new KgProperties();
        properties.setEnabled(true);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "taskExecutor", taskExecutor);
        ReflectionTestUtils.setField(service, "chunkEntityExtractionService", chunkEntityExtractionService);
        ReflectionTestUtils.setField(service, "chunkRelationExtractionService", chunkRelationExtractionService);
        ReflectionTestUtils.setField(service, "paperGraphPayloadAssembler", paperGraphPayloadAssembler);
        ReflectionTestUtils.setField(service, "graphBuilderClient", graphBuilderClient);
        ReflectionTestUtils.setField(service, "graphWriter", graphWriter);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void enqueueShouldPersistPayloadAndMarkJobFailedWhenGraphBuilderSyncFails() {
        UUID documentId = UUID.randomUUID();
        RagChunk chunk = new RagChunk(documentId, "doi:test", "10.1000/test", "chunk-1", 1, "body", "Intro", 1, 1, 1,
                "Paper", "FLS2 is an LRR-RLK receptor.", "paper.pdf", "paper.tei.xml", "v1");
        ChunkEntityExtraction entityA = new ChunkEntityExtraction(documentId, "chunk-1", "FLS2", "FLS2", EntityType.GENE_OR_PROTEIN, "fls2", List.of(),
                "FLS2 is", 0.9);
        ChunkEntityExtraction entityB = new ChunkEntityExtraction(documentId, "chunk-1", "LRR-RLK", "LRR-RLK", EntityType.RLK_FAMILY, "lrr_rlk", List.of(),
                "LRR-RLK receptor", 0.8);
        ChunkRelationExtraction relation = new ChunkRelationExtraction(documentId, "chunk-1", "fls2", RelationType.BELONGS_TO_FAMILY, "lrr_rlk",
                "FLS2 is an LRR-RLK receptor", 0.85);
        PaperGraphPayload payload = new PaperGraphPayload(
                documentId, "doi:test", "10.1000/test", "Paper", 2024,
                List.of(), List.of(), List.of(), "v1");

        when(chunkEntityExtractionService.extract(chunk)).thenReturn(List.of(entityA, entityB));
        when(chunkRelationExtractionService.extract(chunk, List.of(entityA, entityB))).thenReturn(List.of(relation));
        when(paperGraphPayloadAssembler.assemble(eq(documentId), eq("doi:test"), any(RagDocumentMetadata.class), eq(List.of(chunk)),
                eq(List.of(entityA, entityB)), eq(List.of(relation)), any(KgProperties.class))).thenReturn(payload);
        when(graphBuilderClient.sync(payload)).thenReturn(new GraphBuilderSyncResult(GraphBuilderSyncStatus.FAILED, "{}", "{\"error\":true}", "sync failed"));
        when(graphBuilderClient.endpoint()).thenReturn("http://graph-builder.test");

        service.enqueue(documentId, "doi:test",
                new RagDocumentMetadata("10.1000/test", "10.1000/test", "Paper", List.of(), List.of(), null, null, null, 2024),
                List.of(chunk),
                tempDir);

        verify(repository).replaceChunkEntities(documentId, List.of(entityA, entityB));
        verify(repository).replaceChunkRelations(documentId, List.of(relation));
        verify(repository).upsertPaperPayload(eq(documentId), any(String.class), eq(0), eq(0), any(String.class));
        verify(repository).markJobFailed(any(UUID.class), eq(2), eq(1), any(String.class), eq(GraphBuilderSyncStatus.FAILED), eq("GRAPH_BUILDER_SYNC"), eq("sync failed"));
    }
}
