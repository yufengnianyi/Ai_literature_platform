package com.example.demo_01.ai.kg.model;

import java.util.List;

public final class QuestionGraphModels {

    private QuestionGraphModels() {
    }

    public record QuestionGraphNode(
            String id,
            String label,
            String entityType,
            boolean matched,
            int degree,
            List<String> papers
    ) {
        public QuestionGraphNode {
            papers = papers == null ? List.of() : List.copyOf(papers);
        }
    }

    public record QuestionGraphEdge(
            String id,
            String source,
            String target,
            String relationType
    ) {
    }

    public record QuestionGraphView(
            String prompt,
            String status,
            List<String> matchedEntities,
            List<QuestionGraphNode> nodes,
            List<QuestionGraphEdge> edges,
            List<String> papers
    ) {
        public QuestionGraphView {
            prompt = prompt == null ? "" : prompt;
            status = status == null || status.isBlank() ? "EMPTY" : status;
            matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
            papers = papers == null ? List.of() : List.copyOf(papers);
        }

        public static QuestionGraphView empty(String prompt, String status) {
            return new QuestionGraphView(prompt, status, List.of(), List.of(), List.of(), List.of());
        }
    }
}
