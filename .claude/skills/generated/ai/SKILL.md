---
name: ai
description: "Skill for the Ai area of demo_01. 11 symbols across 2 files."
---

# Ai

11 symbols | 2 files | Cohesion: 100%

## When to Use

- Working with code in `src/`
- Understanding how chatWithTools, chat, chatWithMemory work
- Modifying ai-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/test/java/com/example/demo_01/ai/AiCodeHelperServiceTest.java` | chatWithTools, chatWithInputGuardrail, chat, chatWithMemory, chatForReport (+1) |
| `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | chatWithTools, chat, chatWithMemory, chatForReport, chatWithSources |

## Entry Points

Start here when exploring this area:

- **`chatWithTools`** (Method) — `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java:31`
- **`chat`** (Method) — `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java:15`
- **`chatWithMemory`** (Method) — `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java:18`
- **`chatForReport`** (Method) — `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java:25`
- **`chatWithSources`** (Method) — `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java:28`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `chatWithTools` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | 31 |
| `chat` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | 15 |
| `chatWithMemory` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | 18 |
| `chatForReport` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | 25 |
| `chatWithSources` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelperService.java` | 28 |
| `chatWithTools` | Method | `src/test/java/com/example/demo_01/ai/AiCodeHelperServiceTest.java` | 52 |
| `chatWithInputGuardrail` | Method | `src/test/java/com/example/demo_01/ai/AiCodeHelperServiceTest.java` | 59 |
| `chat` | Method | `src/test/java/com/example/demo_01/ai/AiCodeHelperServiceTest.java` | 15 |
| `chatWithMemory` | Method | `src/test/java/com/example/demo_01/ai/AiCodeHelperServiceTest.java` | 21 |
| `chatForReport` | Method | `src/test/java/com/example/demo_01/ai/AiCodeHelperServiceTest.java` | 29 |
| `chatWithSources` | Method | `src/test/java/com/example/demo_01/ai/AiCodeHelperServiceTest.java` | 36 |

## How to Explore

1. `gitnexus_context({name: "chatWithTools"})` — see callers and callees
2. `gitnexus_query({query: "ai"})` — find related execution flows
3. Read key files listed above for implementation details
