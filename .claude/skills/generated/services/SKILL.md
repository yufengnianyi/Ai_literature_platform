---
name: services
description: "Skill for the Services area of demo_01. 5 symbols across 2 files."
---

# Services

5 symbols | 2 files | Cohesion: 89%

## When to Use

- Working with code in `ai-literature-frontend/`
- Understanding how finalize, streamChat, onError work
- Modifying services-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `ai-literature-frontend/src/services/chat.ts` | decodeMessagePayload, parseEventBlock, streamChat, finalize |
| `src/main/java/com/example/demo_01/ai/listener/ChatModelListenerConfig.java` | onError |

## Entry Points

Start here when exploring this area:

- **`finalize`** (Function) — `ai-literature-frontend/src/services/chat.ts:62`
- **`streamChat`** (Method) — `ai-literature-frontend/src/services/chat.ts:55`
- **`onError`** (Method) — `src/main/java/com/example/demo_01/ai/listener/ChatModelListenerConfig.java:27`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `finalize` | Function | `ai-literature-frontend/src/services/chat.ts` | 62 |
| `streamChat` | Method | `ai-literature-frontend/src/services/chat.ts` | 55 |
| `onError` | Method | `src/main/java/com/example/demo_01/ai/listener/ChatModelListenerConfig.java` | 27 |
| `decodeMessagePayload` | Function | `ai-literature-frontend/src/services/chat.ts` | 18 |
| `parseEventBlock` | Function | `ai-literature-frontend/src/services/chat.ts` | 29 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `SendMessage → ParseEventBlock` | cross_community | 3 |
| `SendMessage → Finalize` | cross_community | 3 |
| `SendMessage → DecodeMessagePayload` | cross_community | 3 |
| `SendMessage → OnError` | cross_community | 3 |

## How to Explore

1. `gitnexus_context({name: "finalize"})` — see callers and callees
2. `gitnexus_query({query: "services"})` — find related execution flows
3. Read key files listed above for implementation details
