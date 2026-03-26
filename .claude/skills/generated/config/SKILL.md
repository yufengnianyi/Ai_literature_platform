---
name: config
description: "Skill for the Config area of demo_01. 8 symbols across 3 files."
---

# Config

8 symbols | 3 files | Cohesion: 100%

## When to Use

- Working with code in `src/`
- Understanding how dashScopeEnvironmentValidator, validateDashScopeApiKey, estimateTokenCountInText work
- Modifying config-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/test/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfigTest.java` | shouldRejectMissingApiKey, shouldRejectPlaceholderApiKey, shouldAcceptConfiguredApiKeys |
| `src/main/java/com/example/demo_01/ai/config/HeuristicTokenCountEstimator.java` | estimateTokenCountInText, estimateTokenCountInMessage, estimateTokenCountInMessages |
| `src/main/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfig.java` | dashScopeEnvironmentValidator, validateDashScopeApiKey |

## Entry Points

Start here when exploring this area:

- **`dashScopeEnvironmentValidator`** (Method) — `src/main/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfig.java:15`
- **`validateDashScopeApiKey`** (Method) — `src/main/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfig.java:27`
- **`estimateTokenCountInText`** (Method) — `src/main/java/com/example/demo_01/ai/config/HeuristicTokenCountEstimator.java:17`
- **`estimateTokenCountInMessage`** (Method) — `src/main/java/com/example/demo_01/ai/config/HeuristicTokenCountEstimator.java:37`
- **`estimateTokenCountInMessages`** (Method) — `src/main/java/com/example/demo_01/ai/config/HeuristicTokenCountEstimator.java:45`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `dashScopeEnvironmentValidator` | Method | `src/main/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfig.java` | 15 |
| `validateDashScopeApiKey` | Method | `src/main/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfig.java` | 27 |
| `estimateTokenCountInText` | Method | `src/main/java/com/example/demo_01/ai/config/HeuristicTokenCountEstimator.java` | 17 |
| `estimateTokenCountInMessage` | Method | `src/main/java/com/example/demo_01/ai/config/HeuristicTokenCountEstimator.java` | 37 |
| `estimateTokenCountInMessages` | Method | `src/main/java/com/example/demo_01/ai/config/HeuristicTokenCountEstimator.java` | 45 |
| `shouldRejectMissingApiKey` | Method | `src/test/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfigTest.java` | 13 |
| `shouldRejectPlaceholderApiKey` | Method | `src/test/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfigTest.java` | 22 |
| `shouldAcceptConfiguredApiKeys` | Method | `src/test/java/com/example/demo_01/config/DashScopeEnvironmentValidationConfigTest.java` | 31 |

## How to Explore

1. `gitnexus_context({name: "dashScopeEnvironmentValidator"})` — see callers and callees
2. `gitnexus_query({query: "config"})` — find related execution flows
3. Read key files listed above for implementation details
