---
name: aop
description: "Skill for the Aop area of demo_01. 6 symbols across 3 files."
---

# Aop

6 symbols | 3 files | Cohesion: 67%

## When to Use

- Working with code in `src/`
- Understanding how AuthInterceptor, isAdmin, doInterceptor work
- Modifying aop-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/test/java/com/example/demo_01/aop/AuthInterceptorTest.java` | shouldRejectWhenUserIsNotAdmin, injectUserService, getAuthCheck |
| `src/main/java/com/example/demo_01/aop/AuthInterceptor.java` | AuthInterceptor, doInterceptor |
| `src/main/java/com/example/demo_01/user/UserService.java` | isAdmin |

## Entry Points

Start here when exploring this area:

- **`AuthInterceptor`** (Class) — `src/main/java/com/example/demo_01/aop/AuthInterceptor.java:17`
- **`isAdmin`** (Method) — `src/main/java/com/example/demo_01/user/UserService.java:40`
- **`doInterceptor`** (Method) — `src/main/java/com/example/demo_01/aop/AuthInterceptor.java:24`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `AuthInterceptor` | Class | `src/main/java/com/example/demo_01/aop/AuthInterceptor.java` | 17 |
| `isAdmin` | Method | `src/main/java/com/example/demo_01/user/UserService.java` | 40 |
| `doInterceptor` | Method | `src/main/java/com/example/demo_01/aop/AuthInterceptor.java` | 24 |
| `shouldRejectWhenUserIsNotAdmin` | Method | `src/test/java/com/example/demo_01/aop/AuthInterceptorTest.java` | 28 |
| `injectUserService` | Method | `src/test/java/com/example/demo_01/aop/AuthInterceptorTest.java` | 48 |
| `getAuthCheck` | Method | `src/test/java/com/example/demo_01/aop/AuthInterceptorTest.java` | 58 |

## Connected Areas

| Area | Connections |
|------|-------------|
| User | 3 calls |
| Conversation | 2 calls |

## How to Explore

1. `gitnexus_context({name: "AuthInterceptor"})` — see callers and callees
2. `gitnexus_query({query: "aop"})` — find related execution flows
3. Read key files listed above for implementation details
