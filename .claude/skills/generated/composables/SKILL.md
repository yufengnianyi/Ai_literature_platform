---
name: composables
description: "Skill for the Composables area of demo_01. 21 symbols across 3 files."
---

# Composables

21 symbols | 3 files | Cohesion: 97%

## When to Use

- Working with code in `ai-literature-frontend/`
- Understanding how loadConversationMessages, sendMessage, stopGenerating work
- Modifying composables-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `ai-literature-frontend/src/composables/useConversationState.ts` | getStorageKey, sortConversations, setActiveConversation, readPersistedActiveConversationId, persistActiveConversationId (+7) |
| `ai-literature-frontend/src/composables/useChat.ts` | syncAssistantMessageState, updateAssistantMessage, toUiMessage, loadConversationMessages, sendMessage (+1) |
| `ai-literature-frontend/src/services/conversation.ts` | listConversations, pinConversation, listConversationMessages |

## Entry Points

Start here when exploring this area:

- **`loadConversationMessages`** (Function) — `ai-literature-frontend/src/composables/useChat.ts:62`
- **`sendMessage`** (Function) — `ai-literature-frontend/src/composables/useChat.ts:100`
- **`stopGenerating`** (Function) — `ai-literature-frontend/src/composables/useChat.ts:174`
- **`listConversations`** (Method) — `ai-literature-frontend/src/services/conversation.ts:10`
- **`pinConversation`** (Method) — `ai-literature-frontend/src/services/conversation.ts:25`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `loadConversationMessages` | Function | `ai-literature-frontend/src/composables/useChat.ts` | 62 |
| `sendMessage` | Function | `ai-literature-frontend/src/composables/useChat.ts` | 100 |
| `stopGenerating` | Function | `ai-literature-frontend/src/composables/useChat.ts` | 174 |
| `listConversations` | Method | `ai-literature-frontend/src/services/conversation.ts` | 10 |
| `pinConversation` | Method | `ai-literature-frontend/src/services/conversation.ts` | 25 |
| `listConversationMessages` | Method | `ai-literature-frontend/src/services/conversation.ts` | 37 |
| `getStorageKey` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 12 |
| `sortConversations` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 17 |
| `setActiveConversation` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 26 |
| `readPersistedActiveConversationId` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 31 |
| `persistActiveConversationId` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 38 |
| `refreshConversations` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 50 |
| `createConversation` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 79 |
| `renameConversation` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 91 |
| `togglePinConversation` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 99 |
| `deleteConversation` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 107 |
| `initializeConversations` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 139 |
| `resetConversationState` | Function | `ai-literature-frontend/src/composables/useConversationState.ts` | 163 |
| `syncAssistantMessageState` | Function | `ai-literature-frontend/src/composables/useChat.ts` | 7 |
| `updateAssistantMessage` | Function | `ai-literature-frontend/src/composables/useChat.ts` | 23 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `DeleteConversation → GetStorageKey` | intra_community | 4 |
| `InitializeConversations → GetStorageKey` | intra_community | 4 |
| `LoadConversationMessages → SyncAssistantMessageState` | intra_community | 3 |
| `SendMessage → ParseEventBlock` | cross_community | 3 |
| `SendMessage → Finalize` | cross_community | 3 |
| `SendMessage → DecodeMessagePayload` | cross_community | 3 |
| `SendMessage → OnError` | cross_community | 3 |
| `DeleteConversation → ListConversations` | intra_community | 3 |
| `DeleteConversation → SortConversations` | intra_community | 3 |
| `InitializeConversations → ListConversations` | intra_community | 3 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Services | 1 calls |

## How to Explore

1. `gitnexus_context({name: "loadConversationMessages"})` — see callers and callees
2. `gitnexus_query({query: "composables"})` — find related execution flows
3. Read key files listed above for implementation details
