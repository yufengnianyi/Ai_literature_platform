---
name: cluster-8
description: "Skill for the Cluster_8 area of demo_01. 6 symbols across 1 files."
---

# Cluster_8

6 symbols | 1 files | Cohesion: 100%

## When to Use

- Working with code in `ai-literature-frontend/`
- Understanding how prepareCitations, replacer work
- Modifying cluster_8-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `ai-literature-frontend/src/utils/markdown.ts` | normalizeReferenceScope, buildReferenceId, buildSourceLookup, parseCitationTokenBody, prepareCitations (+1) |

## Entry Points

Start here when exploring this area:

- **`prepareCitations`** (Function) — `ai-literature-frontend/src/utils/markdown.ts:376`
- **`replacer`** (Function) — `ai-literature-frontend/src/utils/markdown.ts:390`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `prepareCitations` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 376 |
| `replacer` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 390 |
| `normalizeReferenceScope` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 276 |
| `buildReferenceId` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 285 |
| `buildSourceLookup` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 288 |
| `parseCitationTokenBody` | Function | `ai-literature-frontend/src/utils/markdown.ts` | 322 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `PrepareCitations → ParseCitationTokenBody` | intra_community | 3 |
| `PrepareCitations → BuildReferenceId` | intra_community | 3 |

## How to Explore

1. `gitnexus_context({name: "prepareCitations"})` — see callers and callees
2. `gitnexus_query({query: "cluster_8"})` — find related execution flows
3. Read key files listed above for implementation details
