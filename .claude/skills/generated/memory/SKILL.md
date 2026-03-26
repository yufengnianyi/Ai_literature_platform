---
name: memory
description: "Skill for the Memory area of demo_01. 19 symbols across 5 files."
---

# Memory

19 symbols | 5 files | Cohesion: 94%

## When to Use

- Working with code in `src/`
- Understanding how compose, parse, isBlank work
- Modifying memory-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | shouldReturnFullConversationHistoryBeyondSnapshotWindow, shouldStripRagInjectedChunksFromPersistedUserMessage, shouldCascadeDeleteConversationHistoryAndSnapshot, createConversationHistory, prepareUserAndConversation (+1) |
| `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java` | getMessages, updateMessages, deleteMessages, extractAppendedMessages, normalize |
| `src/test/java/com/example/demo_01/ai/integration/PersistentChatMemoryStoreIntegrationTest.java` | shouldPersistSnapshotAndAppendOnlyNewHistoryMessages, deleteMessagesShouldDeleteConversationAndCascadeMemoryData, sameConversationIdShouldBeIsolatedAcrossUsers, prepareUserAndConversation |
| `src/main/java/com/example/demo_01/ai/memory/UserConversationKey.java` | compose, parse, isBlank |
| `src/test/java/com/example/demo_01/ai/memory/UserConversationKeyTest.java` | shouldComposeAndParse |

## Entry Points

Start here when exploring this area:

- **`compose`** (Method) — `src/main/java/com/example/demo_01/ai/memory/UserConversationKey.java:6`
- **`parse`** (Method) — `src/main/java/com/example/demo_01/ai/memory/UserConversationKey.java:13`
- **`isBlank`** (Method) — `src/main/java/com/example/demo_01/ai/memory/UserConversationKey.java:27`
- **`getMessages`** (Method) — `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java:31`
- **`updateMessages`** (Method) — `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java:47`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `compose` | Method | `src/main/java/com/example/demo_01/ai/memory/UserConversationKey.java` | 6 |
| `parse` | Method | `src/main/java/com/example/demo_01/ai/memory/UserConversationKey.java` | 13 |
| `isBlank` | Method | `src/main/java/com/example/demo_01/ai/memory/UserConversationKey.java` | 27 |
| `getMessages` | Method | `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java` | 31 |
| `updateMessages` | Method | `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java` | 47 |
| `deleteMessages` | Method | `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java` | 101 |
| `extractAppendedMessages` | Method | `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java` | 122 |
| `normalize` | Method | `src/main/java/com/example/demo_01/ai/memory/PersistentChatMemoryStore.java` | 153 |
| `shouldReturnFullConversationHistoryBeyondSnapshotWindow` | Method | `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | 240 |
| `shouldStripRagInjectedChunksFromPersistedUserMessage` | Method | `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | 268 |
| `shouldCascadeDeleteConversationHistoryAndSnapshot` | Method | `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | 300 |
| `createConversationHistory` | Method | `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | 349 |
| `prepareUserAndConversation` | Method | `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | 358 |
| `countRows` | Method | `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | 370 |
| `shouldPersistSnapshotAndAppendOnlyNewHistoryMessages` | Method | `src/test/java/com/example/demo_01/ai/integration/PersistentChatMemoryStoreIntegrationTest.java` | 19 |
| `deleteMessagesShouldDeleteConversationAndCascadeMemoryData` | Method | `src/test/java/com/example/demo_01/ai/integration/PersistentChatMemoryStoreIntegrationTest.java` | 55 |
| `sameConversationIdShouldBeIsolatedAcrossUsers` | Method | `src/test/java/com/example/demo_01/ai/integration/PersistentChatMemoryStoreIntegrationTest.java` | 84 |
| `prepareUserAndConversation` | Method | `src/test/java/com/example/demo_01/ai/integration/PersistentChatMemoryStoreIntegrationTest.java` | 111 |
| `shouldComposeAndParse` | Method | `src/test/java/com/example/demo_01/ai/memory/UserConversationKeyTest.java` | 9 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Conversation | 3 calls |

## How to Explore

1. `gitnexus_context({name: "compose"})` — see callers and callees
2. `gitnexus_query({query: "memory"})` — find related execution flows
3. Read key files listed above for implementation details
