---
name: cluster-6
description: "Skill for the Cluster_6 area of demo_01. 5 symbols across 1 files."
---

# Cluster_6

5 symbols | 1 files | Cohesion: 91%

## When to Use

- Working with code in `ai-literature-frontend/`
- Understanding how parseAIResponse work
- Modifying cluster_6-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `ai-literature-frontend/src/utils/markdown.ts` | sanitizeHtml, escapeHtml, renderPlaintextHtml, shouldFallbackToPlaintext, parseAIResponse |

## Entry Points

Start here when exploring this area:

- **`parseAIResponse`** (Function) — `ai-literature-frontend/src/utils/markdown.ts:537`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `parseAIResponse` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 537 |
| `sanitizeHtml` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 69 |
| `escapeHtml` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 79 |
| `renderPlaintextHtml` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 432 |
| `shouldFallbackToPlaintext` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 497 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Cluster_7 | 1 calls |

## How to Explore

1. `gitnexus_context({name: "parseAIResponse"})` — see callers and callees
2. `gitnexus_query({query: "cluster_6"})` — find related execution flows
3. Read key files listed above for implementation details
