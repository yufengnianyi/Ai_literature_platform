package com.example.demo_01.ai.rag.retrieval;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;
import java.util.Locale;

public class AntimicrobialCompoundQueryExpandingRetriever implements ContentRetriever {

    private static final String EXPANSION = String.join(" ",
            "\u5375\u83cc", "Oomycetes", "oomycete",
            "Phytophthora", "Pythium", "Saprolegnia",
            "antimicrobial compound", "antifungal compound", "fungicide", "inhibitor",
            "mycelial growth inhibition", "zoospore germination",
            "EC50", "IC50", "MIC", "MFC",
            "mode of action", "target", "mechanism", "cytotoxicity", "safety"
    );

    private final ContentRetriever delegate;

    public AntimicrobialCompoundQueryExpandingRetriever(ContentRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return delegate.retrieve(query);
        }
        return delegate.retrieve(Query.from(expand(query.text())));
    }

    String expand(String queryText) {
        if (!looksLikeAntimicrobialCompoundSummary(queryText) || alreadyHasOomyceteScope(queryText)) {
            return queryText;
        }
        return queryText + "\n\n\u68c0\u7d22\u6269\u5c55\u8bcd: " + EXPANSION;
    }

    private boolean looksLikeAntimicrobialCompoundSummary(String queryText) {
        String lower = queryText.toLowerCase(Locale.ROOT);
        return queryText.contains("\u6291\u83cc\u5316\u5408\u7269")
                || queryText.contains("\u6291\u83cc\u6d3b\u6027")
                || lower.contains("antimicrobial compound")
                || lower.contains("antifungal compound")
                || (queryText.contains("\u5316\u5408\u7269\u540d\u79f0")
                && queryText.contains("\u4f5c\u7528\u75c5\u539f\u83cc"));
    }

    private boolean alreadyHasOomyceteScope(String queryText) {
        String lower = queryText.toLowerCase(Locale.ROOT);
        return queryText.contains("\u5375\u83cc")
                || lower.contains("oomycete")
                || lower.contains("phytophthora")
                || lower.contains("pythium")
                || lower.contains("saprolegnia");
    }
}
