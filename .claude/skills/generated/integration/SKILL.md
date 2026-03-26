---
name: integration
description: "Skill for the Integration area of demo_01. 5 symbols across 3 files."
---

# Integration

5 symbols | 3 files | Cohesion: 100%

## When to Use

- Working with code in `src/`
- Understanding how PostgresIntegrationTestSupport, PgVectorEmbeddingStoreIntegrationTest, PersistentChatMemoryStoreIntegrationTest work
- Modifying integration-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/test/java/com/example/demo_01/ai/integration/PgVectorEmbeddingStoreIntegrationTest.java` | PgVectorEmbeddingStoreIntegrationTest, shouldPersistAndSearchEmbeddingsInPgVector, vectorWithValue |
| `src/test/java/com/example/demo_01/ai/integration/PostgresIntegrationTestSupport.java` | PostgresIntegrationTestSupport |
| `src/test/java/com/example/demo_01/ai/integration/PersistentChatMemoryStoreIntegrationTest.java` | PersistentChatMemoryStoreIntegrationTest |

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `PostgresIntegrationTestSupport` | Class | `src/test/java/com/example/demo_01/ai/integration/PostgresIntegrationTestSupport.java` | 16 |
| `PgVectorEmbeddingStoreIntegrationTest` | Class | `src/test/java/com/example/demo_01/ai/integration/PgVectorEmbeddingStoreIntegrationTest.java` | 12 |
| `PersistentChatMemoryStoreIntegrationTest` | Class | `src/test/java/com/example/demo_01/ai/integration/PersistentChatMemoryStoreIntegrationTest.java` | 17 |
| `shouldPersistAndSearchEmbeddingsInPgVector` | Method | `src/test/java/com/example/demo_01/ai/integration/PgVectorEmbeddingStoreIntegrationTest.java` | 14 |
| `vectorWithValue` | Method | `src/test/java/com/example/demo_01/ai/integration/PgVectorEmbeddingStoreIntegrationTest.java` | 38 |

## How to Explore

1. `gitnexus_context({name: "PostgresIntegrationTestSupport"})` — see callers and callees
2. `gitnexus_query({query: "integration"})` — find related execution flows
3. Read key files listed above for implementation details
