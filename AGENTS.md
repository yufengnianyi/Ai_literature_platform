<!-- project-spec:start -->
# demo_01 AI Coding Project Rules

This file is the first project-level instruction source for AI coding agents working in this repository. Apply these rules before making code changes, then follow the GitNexus section below for symbol navigation, impact analysis, and pre-commit checks.

## Project Snapshot

- Backend: Spring Boot 3.5.x on Java 21, Maven, MyBatis-Flex, Flyway, PostgreSQL with pgvector, LangChain4j, DashScope/Qwen models, Lucene BM25, Neo4j, GROBID, Knife4j/OpenAPI.
- Frontend: `ai-literature-frontend`, Vue 3, Vite, TypeScript, Pinia, Vue Router, Ant Design Vue, generated OpenAPI client files.
- Core domains: user/session/auth, conversation memory, AI chat, RAG ingestion and retrieval, literature preprocessing, review pipeline, evidence fusion, report generation, knowledge graph extraction/querying.
- Runtime data and generated artifacts include `data/`, `target/`, `node_modules/`, `ai-literature-frontend/dist/`, `.npm-cache/`, and presentation output. Do not treat these as source unless the user explicitly asks.

## Working Principles

- State assumptions before coding when requirements are ambiguous. If two interpretations are plausible, ask or document the chosen one.
- Prefer the smallest change that satisfies the request. Do not add speculative extension points, new frameworks, or broad rewrites.
- Keep edits surgical: touch only files that directly support the task, and preserve existing style even when it differs from personal preference.
- Do not clean up unrelated code, generated files, caches, indexes, or formatting drift unless the user asks.
- Define a verification target before implementation: unit test, integration test, build command, manual endpoint check, or documented reason why verification is not possible.

## Repository Boundaries

- Backend source lives under `src/main/java/com/example/demo_01`.
- Backend config lives under `src/main/resources`; profile-specific config belongs in `application-local.yml`, `application-prod.yml`, or `application-test.yml`.
- Database schema changes must be Flyway migrations in `src/main/resources/db/migration`; never edit an already-applied migration casually. Add a new versioned migration instead.
- Frontend source lives under `ai-literature-frontend/src`; generated API clients live under `ai-literature-frontend/src/api`.
- Do not manually edit generated API typings/client files if the change should come from OpenAPI generation. Update backend contract and regenerate instead.
- Avoid committing runtime/cache output such as `.npm-cache`, `data/bm25-index`, `target`, `node_modules`, local logs, and built frontend output unless explicitly requested.

## Backend Conventions

- Follow existing layering: controller/API classes handle HTTP contracts, services own business flow, repositories own persistence queries, config classes own beans/properties.
- Use existing response and error patterns: `BaseResponse`, `ResultUtils`, `BusinessException`, `ErrorCode`, and `ThrowUtils`.
- Validate request DTOs with Jakarta validation where possible; keep controller logic thin.
- Keep AI/RAG/review/KG behavior deterministic around boundaries: normalize inputs, validate structured LLM outputs, record failed artifacts where existing support classes do so, and avoid hiding partial failures.
- Use existing property classes for configurable behavior. Add new `@ConfigurationProperties` fields rather than scattering `@Value` strings.
- For persistence, prefer existing MyBatis-Flex repository/mapper patterns. Schema changes and repository changes must be reviewed together.
- Do not hard-code API keys, model credentials, local absolute paths, or machine-specific endpoints. Use environment variables or Spring configuration placeholders.

## AI Agent And LLM-Specific Rules

- Treat prompts, retrieval thresholds, token budgets, chunking strategy, reranking, and KG extraction schema as product behavior, not incidental strings.
- When changing prompts or structured output models, update tests or add focused fixtures that cover malformed, partial, and low-confidence outputs.
- Keep model/provider assumptions explicit. Existing defaults use DashScope/Qwen-style configuration; do not silently switch provider semantics.
- RAG changes must consider ingestion, storage, retrieval, reranking, and downstream citation/report behavior together.
- KG changes must consider Neo4j schema/write path, extraction confidence thresholds, graph retrieval, and query service behavior together.

## Frontend Conventions

- Use Vue 3 Composition API, TypeScript, Pinia, Vue Router, and Ant Design Vue in the established style.
- Keep page components workflow-oriented and move reusable logic to `components`, `composables`, `services`, `stores`, `types`, or `utils` only when reuse is real.
- Use service modules for API calls; do not scatter raw `axios` calls through pages.
- Preserve generated API boundaries. If a backend endpoint changes, regenerate or update the corresponding client consistently.
- For review and chat UI, optimize for dense operational workflows: clear state, loading/error handling, source traceability, and no decorative layout rewrites unrelated to the task.

## Testing And Verification

- Backend default checks:
  - `./mvnw test`
  - For narrower work, run the relevant test class with `./mvnw -Dtest=ClassName test`.
- Frontend default checks from `ai-literature-frontend`:
  - `npm run type-check`
  - `npm run build`
  - `npm run test:markdown` when markdown rendering/parsing changes.
- If tests require PostgreSQL, pgvector, Neo4j, GROBID, or external model credentials, state the missing dependency clearly and run the narrowest feasible check.
- Add or update tests when changing parsing, retrieval ranking, auth/session behavior, repository SQL, prompt canonicalization, structured output validation, report generation, or API contracts.

## Git And Change Hygiene

- The worktree may contain user changes. Inspect `git status --short` before edits and do not revert unrelated changes.
- Before editing functions, classes, or methods, follow the GitNexus impact-analysis rules below.
- Before committing, run the GitNexus change detector required below and verify the changed symbols and flows match the task.
- Keep documentation-only changes separate from code changes when possible.

## Task Completion Checklist

1. Scope is clear, assumptions are stated, and no speculative behavior was added.
2. GitNexus impact analysis was run for every edited symbol when code symbols changed.
3. Config, migration, backend contract, frontend client, and tests are consistent.
4. Verification command was run, or the reason it could not be run is documented.
5. Final response names the files changed, the verification result, and any residual risk.

<!-- project-spec:end -->

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **demo_01** (1189 symbols, 3187 relationships, 95 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## When Debugging

1. `gitnexus_query({query: "<error or symptom>"})` — find execution flows related to the issue
2. `gitnexus_context({name: "<suspect function>"})` — see all callers, callees, and process participation
3. `READ gitnexus://repo/demo_01/process/{processName}` — trace the full execution flow step by step
4. For regressions: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` — see what your branch changed

## When Refactoring

- **Renaming**: MUST use `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` first. Review the preview — graph edits are safe, text_search edits need manual review. Then run with `dry_run: false`.
- **Extracting/Splitting**: MUST run `gitnexus_context({name: "target"})` to see all incoming/outgoing refs, then `gitnexus_impact({target: "target", direction: "upstream"})` to find all external callers before moving code.
- After any refactor: run `gitnexus_detect_changes({scope: "all"})` to verify only expected files changed.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Tools Quick Reference

| Tool | When to use | Command |
|------|-------------|---------|
| `query` | Find code by concept | `gitnexus_query({query: "auth validation"})` |
| `context` | 360-degree view of one symbol | `gitnexus_context({name: "validateUser"})` |
| `impact` | Blast radius before editing | `gitnexus_impact({target: "X", direction: "upstream"})` |
| `detect_changes` | Pre-commit scope check | `gitnexus_detect_changes({scope: "staged"})` |
| `rename` | Safe multi-file rename | `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` |
| `cypher` | Custom graph queries | `gitnexus_cypher({query: "MATCH ..."})` |

## Impact Risk Levels

| Depth | Meaning | Action |
|-------|---------|--------|
| d=1 | WILL BREAK — direct callers/importers | MUST update these |
| d=2 | LIKELY AFFECTED — indirect deps | Should test |
| d=3 | MAY NEED TESTING — transitive | Test if critical path |

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/demo_01/context` | Codebase overview, check index freshness |
| `gitnexus://repo/demo_01/clusters` | All functional areas |
| `gitnexus://repo/demo_01/processes` | All execution flows |
| `gitnexus://repo/demo_01/process/{name}` | Step-by-step execution trace |

## Self-Check Before Finishing

Before completing any code modification task, verify:
1. `gitnexus_impact` was run for all modified symbols
2. No HIGH/CRITICAL risk warnings were ignored
3. `gitnexus_detect_changes()` confirms changes match expected scope
4. All d=1 (WILL BREAK) dependents were updated

## Keeping the Index Fresh

After committing code changes, the GitNexus index becomes stale. Re-run analyze to update it:

```bash
npx gitnexus analyze
```

If the index previously included embeddings, preserve them by adding `--embeddings`:

```bash
npx gitnexus analyze --embeddings
```

To check whether embeddings exist, inspect `.gitnexus/meta.json` — the `stats.embeddings` field shows the count (0 means no embeddings). **Running analyze without `--embeddings` will delete any previously generated embeddings.**

> Claude Code users: A PostToolUse hook handles this automatically after `git commit` and `git merge`.

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |
| Work in the User area (78 symbols) | `.claude/skills/generated/user/SKILL.md` |
| Work in the Conversation area (45 symbols) | `.claude/skills/generated/conversation/SKILL.md` |
| Work in the Service area (44 symbols) | `.claude/skills/generated/service/SKILL.md` |
| Work in the Rag area (31 symbols) | `.claude/skills/generated/rag/SKILL.md` |
| Work in the Parser area (30 symbols) | `.claude/skills/generated/parser/SKILL.md` |
| Work in the Composables area (21 symbols) | `.claude/skills/generated/composables/SKILL.md` |
| Work in the Memory area (19 symbols) | `.claude/skills/generated/memory/SKILL.md` |
| Work in the Cluster_7 area (18 symbols) | `.claude/skills/generated/cluster-7/SKILL.md` |
| Work in the Repository area (18 symbols) | `.claude/skills/generated/repository/SKILL.md` |
| Work in the Markdown area (11 symbols) | `.claude/skills/generated/markdown/SKILL.md` |
| Work in the Ai area (11 symbols) | `.claude/skills/generated/ai/SKILL.md` |
| Work in the Config area (8 symbols) | `.claude/skills/generated/config/SKILL.md` |
| Work in the Cluster_8 area (6 symbols) | `.claude/skills/generated/cluster-8/SKILL.md` |
| Work in the Aop area (6 symbols) | `.claude/skills/generated/aop/SKILL.md` |
| Work in the Chunk area (6 symbols) | `.claude/skills/generated/chunk/SKILL.md` |
| Work in the Services area (5 symbols) | `.claude/skills/generated/services/SKILL.md` |
| Work in the Cluster_5 area (5 symbols) | `.claude/skills/generated/cluster-5/SKILL.md` |
| Work in the Cluster_6 area (5 symbols) | `.claude/skills/generated/cluster-6/SKILL.md` |
| Work in the Integration area (5 symbols) | `.claude/skills/generated/integration/SKILL.md` |
| Work in the Demo_01 area (4 symbols) | `.claude/skills/generated/demo-01/SKILL.md` |

<!-- gitnexus:end -->
