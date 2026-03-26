---
name: conversation
description: "Skill for the Conversation area of demo_01. 45 symbols across 10 files."
---

# Conversation

45 symbols | 10 files | Cohesion: 59%

## When to Use

- Working with code in `src/`
- Understanding how listConversationMessages, assertConversationExists, listMessages work
- Modifying conversation-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/main/java/com/example/demo_01/conversation/ConversationService.java` | listConversationMessages, assertConversationExists, pinConversation, normalizeConversationId, createConversationIfAbsent (+12) |
| `src/test/java/com/example/demo_01/conversation/ConversationControllerTest.java` | mockLoginUser, shouldListConversationMessages, shouldReturnEmptyConversationMessages, shouldReturnNotFoundWhenConversationMessagesMissing, shouldPinConversation (+8) |
| `src/main/java/com/example/demo_01/conversation/ConversationController.java` | listMessages, pin, list, create, rename (+1) |
| `src/test/java/com/example/demo_01/ai/controller/AiControllerTest.java` | shouldReturnUnauthorizedWhenNotLoggedIn, shouldChatWithUserConversationScopedMemory |
| `src/main/java/com/example/demo_01/ai/controller/AiController.java` | chat, resolveConversationId |
| `src/main/java/com/example/demo_01/user/UserService.java` | getLoginUser |
| `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | chatWithFlux |
| `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | take |
| `src/test/java/com/example/demo_01/ai/integration/PostgresIntegrationTestSupport.java` | setUpDatabase |
| `src/main/java/com/example/demo_01/user/model/entity/User.java` | getUsername |

## Entry Points

Start here when exploring this area:

- **`listConversationMessages`** (Method) — `src/main/java/com/example/demo_01/conversation/ConversationService.java:57`
- **`assertConversationExists`** (Method) — `src/main/java/com/example/demo_01/conversation/ConversationService.java:175`
- **`listMessages`** (Method) — `src/main/java/com/example/demo_01/conversation/ConversationController.java:43`
- **`getLoginUser`** (Method) — `src/main/java/com/example/demo_01/user/UserService.java:22`
- **`pinConversation`** (Method) — `src/main/java/com/example/demo_01/conversation/ConversationService.java:108`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `listConversationMessages` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 57 |
| `assertConversationExists` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 175 |
| `listMessages` | Method | `src/main/java/com/example/demo_01/conversation/ConversationController.java` | 43 |
| `getLoginUser` | Method | `src/main/java/com/example/demo_01/user/UserService.java` | 22 |
| `pinConversation` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 108 |
| `pin` | Method | `src/main/java/com/example/demo_01/conversation/ConversationController.java` | 60 |
| `normalizeConversationId` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 127 |
| `createConversationIfAbsent` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 138 |
| `defaultTitle` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 171 |
| `chatWithFlux` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | 34 |
| `chat` | Method | `src/main/java/com/example/demo_01/ai/controller/AiController.java` | 32 |
| `resolveConversationId` | Method | `src/main/java/com/example/demo_01/ai/controller/AiController.java` | 67 |
| `listConversations` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 48 |
| `mapConversationResponse` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 235 |
| `list` | Method | `src/main/java/com/example/demo_01/conversation/ConversationController.java` | 37 |
| `createConversation` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 37 |
| `resolveTitle` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 149 |
| `create` | Method | `src/main/java/com/example/demo_01/conversation/ConversationController.java` | 29 |
| `renameConversation` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 87 |
| `normalizeRenameTitle` | Method | `src/main/java/com/example/demo_01/conversation/ConversationService.java` | 160 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Append → NormalizeConversationId` | cross_community | 6 |
| `Append → GetLoginUser` | cross_community | 5 |
| `Append → GetUserId` | cross_community | 5 |
| `Create → DefaultTitle` | cross_community | 4 |
| `Chat → NormalizeConversationId` | intra_community | 3 |
| `Chat → DefaultTitle` | intra_community | 3 |
| `List → MapConversationResponse` | intra_community | 3 |
| `ListMessages → NormalizeConversationId` | cross_community | 3 |
| `ListMessages → AssertConversationExists` | intra_community | 3 |
| `Rename → NormalizeConversationId` | cross_community | 3 |

## Connected Areas

| Area | Connections |
|------|-------------|
| User | 12 calls |
| Memory | 1 calls |
| Markdown | 1 calls |

## How to Explore

1. `gitnexus_context({name: "listConversationMessages"})` — see callers and callees
2. `gitnexus_query({query: "conversation"})` — find related execution flows
3. Read key files listed above for implementation details
