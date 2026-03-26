---
name: parser
description: "Skill for the Parser area of demo_01. 30 symbols across 3 files."
---

# Parser

30 symbols | 3 files | Cohesion: 96%

## When to Use

- Working with code in `src/`
- Understanding how EmptyNodeList, TeiDocumentParser, DoiNormalizer work
- Modifying parser-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | parseMetadata, parse, appendAbstract, appendBody, appendDiv (+19) |
| `src/test/java/com/example/demo_01/ai/rag/TeiDocumentParserTest.java` | parseMetadataShouldExtractCoreFields, parseShouldExposeBodySentencesAndCaptions, sampleTei, setUp |
| `src/main/java/com/example/demo_01/ai/rag/parser/DoiNormalizer.java` | normalize, DoiNormalizer |

## Entry Points

Start here when exploring this area:

- **`EmptyNodeList`** (Class) — `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java:367`
- **`TeiDocumentParser`** (Class) — `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java:27`
- **`DoiNormalizer`** (Class) — `src/main/java/com/example/demo_01/ai/rag/parser/DoiNormalizer.java:8`
- **`parseMetadata`** (Method) — `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java:37`
- **`parse`** (Method) — `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java:73`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `EmptyNodeList` | Class | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 367 |
| `TeiDocumentParser` | Class | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 27 |
| `DoiNormalizer` | Class | `src/main/java/com/example/demo_01/ai/rag/parser/DoiNormalizer.java` | 8 |
| `parseMetadata` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 37 |
| `parse` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 73 |
| `appendAbstract` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 86 |
| `appendBody` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 100 |
| `appendDiv` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 123 |
| `appendCaptions` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 156 |
| `appendNodeSentences` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 177 |
| `appendText` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 192 |
| `extractAbstract` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 220 |
| `extractAuthors` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 224 |
| `extractAuthorName` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 241 |
| `extractDistinctTexts` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 266 |
| `extractYear` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 278 |
| `parseXml` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 286 |
| `firstNode` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 297 |
| `nodes` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 305 |
| `firstString` | Method | `src/main/java/com/example/demo_01/ai/rag/parser/TeiDocumentParser.java` | 313 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Process → EmptyNodeList` | cross_community | 6 |
| `Process → CollectDescendants` | cross_community | 6 |
| `Process → ListBackedNodeList` | cross_community | 6 |
| `Upload → Normalize` | cross_community | 6 |
| `Upload → Nodes` | cross_community | 6 |
| `IngestStoredPdf → Normalize` | cross_community | 6 |
| `IngestFolder → ParseXml` | cross_community | 6 |
| `Upload → ParseXml` | cross_community | 5 |
| `Upload → FirstNonBlank` | cross_community | 5 |
| `ProcessBatch → FirstNonBlank` | cross_community | 5 |

## How to Explore

1. `gitnexus_context({name: "EmptyNodeList"})` — see callers and callees
2. `gitnexus_query({query: "parser"})` — find related execution flows
3. Read key files listed above for implementation details
