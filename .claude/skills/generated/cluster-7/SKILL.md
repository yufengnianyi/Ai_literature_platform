---
name: cluster-7
description: "Skill for the Cluster_7 area of demo_01. 18 symbols across 1 files."
---

# Cluster_7

18 symbols | 1 files | Cohesion: 98%

## When to Use

- Working with code in `ai-literature-frontend/`
- Understanding how normalizeMarkdownSyntax, splitMarkdownStream work
- Modifying cluster_7-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `ai-literature-frontend/src/utils/markdown.ts` | toHalfWidthDigits, parseFence, isFenceClose, isBlankLine, isHeadingLine (+13) |

## Entry Points

Start here when exploring this area:

- **`normalizeMarkdownSyntax`** (Function) — `ai-literature-frontend/src/utils/markdown.ts:367`
- **`splitMarkdownStream`** (Function) — `ai-literature-frontend/src/utils/markdown.ts:474`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `normalizeMarkdownSyntax` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 367 |
| `splitMarkdownStream` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 474 |
| `toHalfWidthDigits` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 87 |
| `parseFence` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 90 |
| `isFenceClose` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 107 |
| `isBlankLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 120 |
| `isHeadingLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 121 |
| `isBlockquoteLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 122 |
| `isBulletListLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 123 |
| `isOrderedListLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 124 |
| `isListLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 125 |
| `isTableDelimiterLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 126 |
| `isTableLikeLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 127 |
| `normalizeMarkdownLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 130 |
| `normalizeTextOutsideFences` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 178 |
| `shouldInsertBlankLine` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 221 |
| `insertBlankLinesBeforeBlocks` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 241 |
| `scanMarkdownBoundaries` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 435 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `NormalizeMarkdownSyntax → IsTableDelimiterLine` | intra_community | 5 |
| `NormalizeMarkdownSyntax → ParseFence` | intra_community | 4 |
| `NormalizeMarkdownSyntax → ToHalfWidthDigits` | intra_community | 4 |
| `NormalizeMarkdownSyntax → IsBlankLine` | intra_community | 4 |
| `NormalizeMarkdownSyntax → IsHeadingLine` | intra_community | 4 |
| `SplitMarkdownStream → ParseFence` | intra_community | 4 |
| `SplitMarkdownStream → IsBlankLine` | intra_community | 3 |

## How to Explore

1. `gitnexus_context({name: "normalizeMarkdownSyntax"})` — see callers and callees
2. `gitnexus_query({query: "cluster_7"})` — find related execution flows
3. Read key files listed above for implementation details
