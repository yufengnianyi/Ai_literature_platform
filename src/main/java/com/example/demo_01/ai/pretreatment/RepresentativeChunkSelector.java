package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.RepresentativeChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class RepresentativeChunkSelector {

    public List<RepresentativeChunk> select(List<RagChunk> chunks, int maxChunks, int maxChars) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<RagChunk> ordered = chunks.stream()
                .filter(chunk -> chunk.text() != null && !chunk.text().isBlank())
                .sorted(Comparator
                        .comparingInt((RagChunk chunk) -> sectionRank(chunk.sectionPath()))
                        .thenComparingInt(RagChunk::chunkIndex))
                .toList();
        List<RepresentativeChunk> selected = new ArrayList<>();
        int usedChars = 0;
        for (RagChunk chunk : ordered) {
            if (selected.size() >= Math.max(1, maxChunks) || usedChars >= maxChars) {
                break;
            }
            int remaining = Math.max(0, maxChars - usedChars);
            String text = chunk.text();
            if (text.length() > remaining) {
                text = text.substring(0, remaining);
            }
            selected.add(new RepresentativeChunk(chunk.chunkId(), chunk.chunkIndex(), chunk.sectionPath(), text));
            usedChars += text.length();
        }
        return selected;
    }

    private int sectionRank(String sectionPath) {
        String section = sectionPath == null ? "" : sectionPath.toLowerCase(Locale.ROOT);
        if (section.contains("abstract")) return 1;
        if (section.contains("intro")) return 2;
        if (section.contains("result")) return 3;
        if (section.contains("discussion")) return 4;
        if (section.contains("conclusion")) return 5;
        if (section.contains("method")) return 6;
        return 7;
    }
}
