---
name: rag
description: "Skill for the Rag area of demo_01. 31 symbols across 16 files."
---

# Rag

31 symbols | 16 files | Cohesion: 86%

## When to Use

- Working with code in `src/`
- Understanding how AiPersistenceProperties, RagDocumentIngestionService, TeiChunker work
- Modifying rag-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/test/java/com/example/demo_01/ai/rag/TeiChunkerTest.java` | chunkShouldHonorSectionBoundariesAndAddOverlap, setUp, estimateTokenCountInText, estimateTokenCountInMessage, estimateTokenCountInMessages |
| `src/test/java/com/example/demo_01/ai/rag/RagDocumentIngestionServiceTest.java` | uploadShouldShortCircuitWhenDoiAlreadyExists, uploadShouldRunHappyPathAndPersistEmbeddings, uploadShouldRecordFailedLiteratureWhenHeaderParsingFails, setUp |
| `src/main/java/com/example/demo_01/ai/rag/client/GrobidClient.java` | processHeaderDocument, processFulltextDocument, execute, sleepBeforeRetry |
| `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | upload, RagDocumentIngestionService, getJob |
| `src/test/java/com/example/demo_01/ai/rag/RagDocumentApiControllerTest.java` | uploadShouldReturnAcceptedPayload, getJobShouldReturnPersistedJob |
| `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | chunk, TeiChunker |
| `src/main/java/com/example/demo_01/ai/rag/service/RagBatchIngestionService.java` | getBatch, RagBatchIngestionService |
| `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | markDuplicate |
| `src/main/java/com/example/demo_01/ai/rag/api/RagDocumentController.java` | upload |
| `src/main/java/com/example/demo_01/ai/config/AiPersistenceProperties.java` | AiPersistenceProperties |

## Entry Points

Start here when exploring this area:

- **`AiPersistenceProperties`** (Class) — `src/main/java/com/example/demo_01/ai/config/AiPersistenceProperties.java:10`
- **`RagDocumentIngestionService`** (Class) — `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java:33`
- **`TeiChunker`** (Class) — `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java:16`
- **`RagBatchIngestionService`** (Class) — `src/main/java/com/example/demo_01/ai/rag/service/RagBatchIngestionService.java:24`
- **`upload`** (Method) — `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java:66`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `AiPersistenceProperties` | Class | `src/main/java/com/example/demo_01/ai/config/AiPersistenceProperties.java` | 10 |
| `RagDocumentIngestionService` | Class | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 33 |
| `TeiChunker` | Class | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 16 |
| `RagBatchIngestionService` | Class | `src/main/java/com/example/demo_01/ai/rag/service/RagBatchIngestionService.java` | 24 |
| `upload` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 66 |
| `markDuplicate` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 74 |
| `processHeaderDocument` | Method | `src/main/java/com/example/demo_01/ai/rag/client/GrobidClient.java` | 23 |
| `processFulltextDocument` | Method | `src/main/java/com/example/demo_01/ai/rag/client/GrobidClient.java` | 30 |
| `execute` | Method | `src/main/java/com/example/demo_01/ai/rag/client/GrobidClient.java` | 37 |
| `sleepBeforeRetry` | Method | `src/main/java/com/example/demo_01/ai/rag/client/GrobidClient.java` | 59 |
| `chunk` | Method | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 25 |
| `upload` | Method | `src/main/java/com/example/demo_01/ai/rag/api/RagDocumentController.java` | 24 |
| `getJob` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 85 |
| `findById` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionJobRepository.java` | 39 |
| `getJob` | Method | `src/main/java/com/example/demo_01/ai/rag/api/RagJobController.java` | 19 |
| `getBatch` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagBatchIngestionService.java` | 51 |
| `findById` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionBatchRepository.java` | 38 |
| `getBatch` | Method | `src/main/java/com/example/demo_01/ai/rag/api/RagBatchController.java` | 29 |
| `estimateTokenCountInText` | Method | `src/test/java/com/example/demo_01/ai/rag/TeiChunkerTest.java` | 31 |
| `estimateTokenCountInMessage` | Method | `src/test/java/com/example/demo_01/ai/rag/TeiChunkerTest.java` | 36 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Upload → SleepBeforeRetry` | cross_community | 6 |
| `Upload → Normalize` | cross_community | 6 |
| `Upload → Nodes` | cross_community | 6 |
| `ProcessBatch → SleepBeforeRetry` | cross_community | 6 |
| `Upload → Hash` | cross_community | 5 |
| `Upload → OriginalFilename` | cross_community | 5 |
| `Upload → InsertInitial` | cross_community | 5 |
| `Upload → RagJobMetrics` | cross_community | 5 |
| `Upload → ParseXml` | cross_community | 5 |
| `Upload → FirstNonBlank` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Service | 5 calls |
| Repository | 4 calls |
| Parser | 3 calls |
| Chunk | 1 calls |

## How to Explore

1. `gitnexus_context({name: "AiPersistenceProperties"})` — see callers and callees
2. `gitnexus_query({query: "rag"})` — find related execution flows
3. Read key files listed above for implementation details
