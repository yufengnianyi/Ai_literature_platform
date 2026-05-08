package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CompoundDefinitionAnchorRetriever {

    private static final Pattern P_COMPOUND_DEF = Pattern.compile(
            "\\b(?:compound|cmpd)\\s+(\\d+[a-z]?|[A-Z]\\d?)\\b.{0,80}\\b(?:was|were|is|=|—|named|identified as|characterized as)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SCHEME_TABLE = Pattern.compile(
            "\\b(Scheme|Table|Figure)\\s+\\d+\\b");
    private static final Pattern P_STRUCTURE_LIST = Pattern.compile(
            "structures? of (the )?compounds?|synthesi[sz]ed compounds?",
            Pattern.CASE_INSENSITIVE);

    @Resource
    private ReviewRepository reviewRepository;

    public List<RetrievedChunk> findDefinitionChunks(UUID documentId, int top) {
        List<RetrievedChunk> allChunks = reviewRepository.findAllChunksByDocumentId(documentId);
        List<ScoredDef> scored = new ArrayList<>();

        for (RetrievedChunk chunk : allChunks) {
            if (chunk.text() == null || chunk.text().isBlank()) continue;
            int score = 0;
            if (P_COMPOUND_DEF.matcher(chunk.text()).find()) score += 3;
            if (P_STRUCTURE_LIST.matcher(chunk.text()).find()) score += 2;
            if (P_SCHEME_TABLE.matcher(chunk.text()).find()) score += 1;
            if (score > 0) {
                scored.add(new ScoredDef(chunk, score));
            }
        }

        scored.sort(Comparator.comparingInt(ScoredDef::score).reversed());
        return scored.subList(0, Math.min(top, scored.size())).stream()
                .map(ScoredDef::chunk)
                .toList();
    }

    private record ScoredDef(RetrievedChunk chunk, int score) {}
}
