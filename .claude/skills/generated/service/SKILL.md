---
name: service
description: "Skill for the Service area of demo_01. 44 symbols across 11 files."
---

# Service

44 symbols | 11 files | Cohesion: 77%

## When to Use

- Working with code in `src/`
- Understanding how RagJobMetrics, RagBatchMetrics, removeDocument work
- Modifying service-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | process, formatErrorMessage, enrichTitle, writeArtifact, updateJob (+9) |
| `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | removeDocument, vectorTable, ingestChunks, ingestDocuments, ingestSegments (+4) |
| `src/main/java/com/example/demo_01/ai/rag/service/RagBatchIngestionService.java` | processBatch, accumulate, resolveBatchStatus, defaultInt, defaultLong (+3) |
| `src/main/java/com/example/demo_01/ai/rag/model/RagPipelineModels.java` | merge, firstNonBlank, RagJobMetrics, RagBatchMetrics |
| `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | markFailed, insertInitial |
| `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | composeEmbeddingText, deterministicEmbeddingId |
| `src/main/java/com/example/demo_01/ai/rag/support/Sha256Support.java` | hash |
| `src/test/java/com/example/demo_01/ai/rag/RagBatchApiControllerTest.java` | folderBatchShouldReturnAcceptedPayload |
| `src/main/java/com/example/demo_01/ai/rag/api/RagBatchController.java` | ingestFolder |
| `src/test/java/com/example/demo_01/ai/rag/RagBatchIngestionServiceTest.java` | ingestFolderShouldAggregateBatchMetrics |

## Entry Points

Start here when exploring this area:

- **`RagJobMetrics`** (Class) — `src/main/java/com/example/demo_01/ai/rag/model/RagPipelineModels.java:244`
- **`RagBatchMetrics`** (Class) — `src/main/java/com/example/demo_01/ai/rag/model/RagPipelineModels.java:258`
- **`removeDocument`** (Method) — `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java:103`
- **`vectorTable`** (Method) — `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java:144`
- **`process`** (Method) — `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java:90`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `RagJobMetrics` | Class | `src/main/java/com/example/demo_01/ai/rag/model/RagPipelineModels.java` | 244 |
| `RagBatchMetrics` | Class | `src/main/java/com/example/demo_01/ai/rag/model/RagPipelineModels.java` | 258 |
| `removeDocument` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 103 |
| `vectorTable` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 144 |
| `process` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 90 |
| `formatErrorMessage` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 210 |
| `enrichTitle` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 218 |
| `writeArtifact` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 310 |
| `updateJob` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 344 |
| `toOutcome` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 355 |
| `markFailed` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 78 |
| `merge` | Method | `src/main/java/com/example/demo_01/ai/rag/model/RagPipelineModels.java` | 64 |
| `firstNonBlank` | Method | `src/main/java/com/example/demo_01/ai/rag/model/RagPipelineModels.java` | 81 |
| `ingestChunks` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 53 |
| `ingestDocuments` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 83 |
| `ingestSegments` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 107 |
| `tokenCount` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 137 |
| `safeEstimateTokenCount` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 152 |
| `firstNonBlank` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 161 |
| `rootMessage` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagVectorIngestionService.java` | 170 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `IngestFolder → Hash` | cross_community | 7 |
| `IngestFolder → OriginalFilename` | cross_community | 7 |
| `IngestFolder → InsertInitial` | cross_community | 7 |
| `Process → EmptyNodeList` | cross_community | 6 |
| `Process → CollectDescendants` | cross_community | 6 |
| `Process → ListBackedNodeList` | cross_community | 6 |
| `Upload → SleepBeforeRetry` | cross_community | 6 |
| `Upload → Normalize` | cross_community | 6 |
| `Upload → Nodes` | cross_community | 6 |
| `IngestFolder → UpdateJob` | cross_community | 6 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Rag | 4 calls |
| Repository | 3 calls |
| Parser | 1 calls |

## How to Explore

1. `gitnexus_context({name: "RagJobMetrics"})` — see callers and callees
2. `gitnexus_query({query: "service"})` — find related execution flows
3. Read key files listed above for implementation details
