---
name: markdown
description: "Skill for the Markdown area of demo_01. 11 symbols across 2 files."
---

# Markdown

11 symbols | 2 files | Cohesion: 83%

## When to Use

- Working with code in `src/`
- Understanding how append, drain, shouldFallbackEmit work
- Modifying markdown-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | append, drain, shouldFallbackEmit, scanBoundaries, isFenceClose (+2) |
| `src/test/java/com/example/demo_01/ai/markdown/MarkdownChunkBufferTest.java` | shouldEmitAtBlankLineBoundary, shouldHoldOpenFencedCodeBlockUntilClosed, shouldFallbackToLatestNewlineWhenThresholdExceeded, shouldFlushRemainingWithoutChangingRawContent |

## Entry Points

Start here when exploring this area:

- **`append`** (Method) — `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java:35`
- **`drain`** (Method) — `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java:48`
- **`shouldFallbackEmit`** (Method) — `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java:73`
- **`scanBoundaries`** (Method) — `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java:89`
- **`isFenceClose`** (Method) — `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java:134`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `append` | Method | `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | 35 |
| `drain` | Method | `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | 48 |
| `shouldFallbackEmit` | Method | `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | 73 |
| `scanBoundaries` | Method | `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | 89 |
| `isFenceClose` | Method | `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | 134 |
| `parseFence` | Method | `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | 141 |
| `flushRemaining` | Method | `src/main/java/com/example/demo_01/ai/markdown/MarkdownChunkBuffer.java` | 44 |
| `shouldEmitAtBlankLineBoundary` | Method | `src/test/java/com/example/demo_01/ai/markdown/MarkdownChunkBufferTest.java` | 11 |
| `shouldHoldOpenFencedCodeBlockUntilClosed` | Method | `src/test/java/com/example/demo_01/ai/markdown/MarkdownChunkBufferTest.java` | 21 |
| `shouldFallbackToLatestNewlineWhenThresholdExceeded` | Method | `src/test/java/com/example/demo_01/ai/markdown/MarkdownChunkBufferTest.java` | 33 |
| `shouldFlushRemainingWithoutChangingRawContent` | Method | `src/test/java/com/example/demo_01/ai/markdown/MarkdownChunkBufferTest.java` | 47 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Append → NormalizeConversationId` | cross_community | 6 |
| `Append → ParseFence` | intra_community | 5 |
| `Append → GetLoginUser` | cross_community | 5 |
| `Append → GetUserId` | cross_community | 5 |
| `Append → ShouldFallbackEmit` | intra_community | 3 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Conversation | 1 calls |

## How to Explore

1. `gitnexus_context({name: "append"})` — see callers and callees
2. `gitnexus_query({query: "markdown"})` — find related execution flows
3. Read key files listed above for implementation details
