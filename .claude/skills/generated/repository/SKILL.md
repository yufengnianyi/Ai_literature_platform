---
name: repository
description: "Skill for the Repository area of demo_01. 18 symbols across 6 files."
---

# Repository

18 symbols | 6 files | Cohesion: 84%

## When to Use

- Working with code in `src/`
- Understanding how getDocument, findDuplicate, findById work
- Modifying repository-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | findById, findCanonicalByDoi, findCanonicalByPdfSha, selectSql, markProcessing (+5) |
| `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | getDocument, findDuplicate |
| `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionJobRepository.java` | mapRow, toInstant |
| `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionBatchRepository.java` | mapRow, toInstant |
| `src/test/java/com/example/demo_01/ai/rag/RagDocumentApiControllerTest.java` | getDocumentShouldReturnPersistedDocument |
| `src/main/java/com/example/demo_01/ai/rag/api/RagDocumentController.java` | getDocument |

## Entry Points

Start here when exploring this area:

- **`getDocument`** (Method) — `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java:80`
- **`findDuplicate`** (Method) — `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java:240`
- **`findById`** (Method) — `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java:45`
- **`findCanonicalByDoi`** (Method) — `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java:50`
- **`findCanonicalByPdfSha`** (Method) — `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java:58`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `getDocument` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 80 |
| `findDuplicate` | Method | `src/main/java/com/example/demo_01/ai/rag/service/RagDocumentIngestionService.java` | 240 |
| `findById` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 45 |
| `findCanonicalByDoi` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 50 |
| `findCanonicalByPdfSha` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 58 |
| `selectSql` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 153 |
| `getDocument` | Method | `src/main/java/com/example/demo_01/ai/rag/api/RagDocumentController.java` | 29 |
| `markProcessing` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 66 |
| `markCompleted` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 70 |
| `updateDocument` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 86 |
| `toJson` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 167 |
| `mapRow` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionJobRepository.java` | 95 |
| `toInstant` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionJobRepository.java` | 122 |
| `mapRow` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionBatchRepository.java` | 92 |
| `toInstant` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagIngestionBatchRepository.java` | 120 |
| `mapRow` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 127 |
| `fromJson` | Method | `src/main/java/com/example/demo_01/ai/rag/repository/RagDocumentRepository.java` | 175 |
| `getDocumentShouldReturnPersistedDocument` | Method | `src/test/java/com/example/demo_01/ai/rag/RagDocumentApiControllerTest.java` | 49 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `GetDocument → SelectSql` | intra_community | 4 |

## How to Explore

1. `gitnexus_context({name: "getDocument"})` — see callers and callees
2. `gitnexus_query({query: "repository"})` — find related execution flows
3. Read key files listed above for implementation details
