---
name: chunk
description: "Skill for the Chunk area of demo_01. 6 symbols across 1 files."
---

# Chunk

6 symbols | 1 files | Cohesion: 89%

## When to Use

- Working with code in `src/`
- Understanding how appendGroupChunks, seedNextBuffer, flushChunk work
- Modifying chunk-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | appendGroupChunks, seedNextBuffer, flushChunk, splitOversizedUnit, estimateTokens (+1) |

## Entry Points

Start here when exploring this area:

- **`appendGroupChunks`** (Method) — `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java:44`
- **`seedNextBuffer`** (Method) — `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java:81`
- **`flushChunk`** (Method) — `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java:95`
- **`splitOversizedUnit`** (Method) — `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java:129`
- **`estimateTokens`** (Method) — `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java:153`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `appendGroupChunks` | Method | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 44 |
| `seedNextBuffer` | Method | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 81 |
| `flushChunk` | Method | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 95 |
| `splitOversizedUnit` | Method | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 129 |
| `estimateTokens` | Method | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 153 |
| `joinText` | Method | `src/main/java/com/example/demo_01/ai/rag/chunk/TeiChunker.java` | 175 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `AppendGroupChunks → ComposeEmbeddingText` | cross_community | 4 |
| `AppendGroupChunks → JoinText` | intra_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Service | 1 calls |

## How to Explore

1. `gitnexus_context({name: "appendGroupChunks"})` — see callers and callees
2. `gitnexus_query({query: "chunk"})` — find related execution flows
3. Read key files listed above for implementation details
