package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.kg.KgProperties;
import com.example.demo_01.ai.kg.model.KgModels.ChunkEntityExtraction;
import com.example.demo_01.ai.kg.model.KgModels.ChunkRelationExtraction;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncResult;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncStatus;
import com.example.demo_01.ai.kg.model.KgModels.KgExtractionJobView;
import com.example.demo_01.ai.kg.model.KgModels.PaperGraphPayload;
import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactLoader;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessStatus;
import com.example.demo_01.ai.kg.repository.KgExtractionRepository;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class KgGraphPipelineService {

    @Resource
    private KgProperties properties;

    @Resource(name = "kgTaskExecutor")
    private TaskExecutor taskExecutor;

    @Resource
    private ChunkEntityExtractionService chunkEntityExtractionService;

    @Resource
    private ChunkRelationExtractionService chunkRelationExtractionService;

    @Resource
    private PaperGraphPayloadAssembler paperGraphPayloadAssembler;

    @Resource
    private Neo4jGraphBuilderClient graphBuilderClient;

    @Resource
    private Neo4jGraphWriter graphWriter;

    @Resource
    private KgExtractionRepository repository;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RagDocumentRepository ragDocumentRepository;

    @Resource
    private PreprocessArtifactLoader preprocessArtifactLoader;

    public UUID enqueue(UUID documentId,
                        String canonicalKey,
                        RagDocumentMetadata metadata,
                        List<RagChunk> chunks,
                        Path storageDir) {
        if (!properties.isEnabled() || chunks == null || chunks.isEmpty()) {
            return null;
        }
        UUID jobId = UUID.randomUUID();
        GraphBuilderSyncStatus initialStatus = properties.getGraphBuilder().isEnabled()
                ? GraphBuilderSyncStatus.PENDING
                : GraphBuilderSyncStatus.SKIPPED;
        repository.insertJob(jobId, documentId, initialStatus);
        taskExecutor.execute(() -> process(jobId, documentId, canonicalKey, metadata, chunks, storageDir));
        return jobId;
    }

    public UUID enqueueExistingDocument(UUID documentId) {
        RagDocumentRecord document = ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        if (ragDocumentRepository.findPreprocessStatus(documentId) != PreprocessStatus.COMPLETED) {
            return null;
        }
        Path storageDir = Path.of(document.storageRoot());
        PreprocessArtifact artifact = preprocessArtifactLoader.loadManifest(storageDir);
        List<RagChunk> chunks = preprocessArtifactLoader.loadChunks(storageDir);
        return enqueue(documentId, artifact.canonicalKey(), artifact.metadata(), chunks, storageDir);
    }

    public Optional<KgExtractionJobView> getLatestJob(UUID documentId) {
        return repository.findLatestJob(documentId);
    }

    public Optional<Map<String, Object>> getPaperPayload(UUID documentId) {
        return repository.findPaperPayload(documentId);
    }

    public List<Map<String, Object>> getChunkEntities(UUID documentId) {
        return repository.findChunkEntities(documentId);
    }

    public List<Map<String, Object>> getChunkRelations(UUID documentId) {
        return repository.findChunkRelations(documentId);
    }

    private void process(UUID jobId,
                         UUID documentId,
                         String canonicalKey,
                         RagDocumentMetadata metadata,
                         List<RagChunk> chunks,
                         Path storageDir) {
        repository.markJobRunning(jobId);
        int entityCount = 0;
        int relationCount = 0;
        String payloadPath = null;
        GraphBuilderSyncStatus syncStatus = properties.getGraphBuilder().isEnabled()
                ? GraphBuilderSyncStatus.PENDING
                : GraphBuilderSyncStatus.SKIPPED;
        try {
            List<ChunkEntityExtraction> entities = new ArrayList<>();
            List<ChunkRelationExtraction> relations = new ArrayList<>();
            for (RagChunk chunk : chunks) {
                List<ChunkEntityExtraction> chunkEntities = chunkEntityExtractionService.extract(chunk);
                entities.addAll(chunkEntities);
                relations.addAll(chunkRelationExtractionService.extract(chunk, chunkEntities));
            }
            entityCount = entities.size();
            relationCount = relations.size();
            repository.replaceChunkEntities(documentId, entities);
            repository.replaceChunkRelations(documentId, relations);

            PaperGraphPayload payload = paperGraphPayloadAssembler.assemble(documentId, canonicalKey, metadata, chunks, entities, relations, properties);
            String payloadJson = objectMapper.writeValueAsString(payload);
            payloadPath = writePayload(storageDir, payloadJson);
            repository.upsertPaperPayload(documentId, payloadJson, payload.entities().size(), payload.relations().size(), payloadPath);

            graphWriter.write(payload);

            GraphBuilderSyncResult syncResult = graphBuilderClient.sync(payload);
            syncStatus = syncResult.status();
            repository.insertSyncLog(
                    UUID.randomUUID(),
                    documentId,
                    syncResult.status(),
                    graphBuilderClient.endpoint(),
                    syncResult.requestBody(),
                    syncResult.responseBody(),
                    syncResult.errorMessage()
            );

            if (syncResult.status() == GraphBuilderSyncStatus.FAILED) {
                repository.markJobFailed(jobId, entityCount, relationCount, payloadPath, syncStatus, "GRAPH_BUILDER_SYNC", syncResult.errorMessage());
                return;
            }
            repository.markJobCompleted(jobId, entityCount, relationCount, payloadPath, syncStatus);
        } catch (Exception ex) {
            log.warn("KG pipeline failed for document {}", documentId, ex);
            repository.markJobFailed(jobId, entityCount, relationCount, payloadPath, syncStatus, "KG_PIPELINE", ex.getMessage());
        }
    }

    private String writePayload(Path storageDir, String payloadJson) {
        if (storageDir == null) {
            return null;
        }
        Path path = storageDir.resolve("graph-payload.json");
        try {
            Files.writeString(path, payloadJson);
            return path.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write graph payload artifact", e);
        }
    }
}
