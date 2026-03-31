package com.example.demo_01.ai.rag.retrieval;

import dev.langchain4j.data.document.Metadata;

public record Bm25IndexEntry(
        String id,
        String documentId,
        String chunkId,
        String title,
        String sectionPath,
        String text,
        Metadata metadata
) {
}
