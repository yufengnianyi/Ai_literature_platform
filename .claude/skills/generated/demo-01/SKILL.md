---
name: demo-01
description: "Skill for the Demo_01 area of demo_01. 4 symbols across 2 files."
---

# Demo_01

4 symbols | 2 files | Cohesion: 100%

## When to Use

- Working with code in `src/`
- Understanding how test, test2, chat work
- Modifying demo_01-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/test/java/com/example/demo_01/AiChatTest.java` | test, test2 |
| `src/main/java/com/example/demo_01/ai/AiCodeHelper.java` | chat, chatMessage |

## Entry Points

Start here when exploring this area:

- **`test`** (Method) — `src/test/java/com/example/demo_01/AiChatTest.java:18`
- **`test2`** (Method) — `src/test/java/com/example/demo_01/AiChatTest.java:24`
- **`chat`** (Method) — `src/main/java/com/example/demo_01/ai/AiCodeHelper.java:32`
- **`chatMessage`** (Method) — `src/main/java/com/example/demo_01/ai/AiCodeHelper.java:43`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `test` | Method | `src/test/java/com/example/demo_01/AiChatTest.java` | 18 |
| `test2` | Method | `src/test/java/com/example/demo_01/AiChatTest.java` | 24 |
| `chat` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelper.java` | 32 |
| `chatMessage` | Method | `src/main/java/com/example/demo_01/ai/AiCodeHelper.java` | 43 |

## How to Explore

1. `gitnexus_context({name: "test"})` — see callers and callees
2. `gitnexus_query({query: "demo_01"})` — find related execution flows
3. Read key files listed above for implementation details
