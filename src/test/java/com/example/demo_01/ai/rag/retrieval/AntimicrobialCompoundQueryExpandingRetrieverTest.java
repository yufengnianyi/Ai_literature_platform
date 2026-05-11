package com.example.demo_01.ai.rag.retrieval;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntimicrobialCompoundQueryExpandingRetrieverTest {

    @Test
    void shouldExpandBroadAntimicrobialCompoundSummaryQuery() {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        ContentRetriever delegate = query -> {
            capturedQuery.set(query.text());
            return List.of();
        };
        AntimicrobialCompoundQueryExpandingRetriever retriever =
                new AntimicrobialCompoundQueryExpandingRetriever(delegate);

        retriever.retrieve(Query.from(
                "\u603b\u7ed3\u6291\u83cc\u5316\u5408\u7269\u4fe1\u606f\n"
                        + "\u8868\u5934\u5185\u5bb9: \u5316\u5408\u7269\u540d\u79f0; "
                        + "\u6291\u83cc\u6d3b\u6027; \u4f5c\u7528\u75c5\u539f\u83cc"));

        assertTrue(capturedQuery.get().contains("\u68c0\u7d22\u6269\u5c55\u8bcd"));
        assertTrue(capturedQuery.get().contains("Oomycetes"));
        assertTrue(capturedQuery.get().contains("EC50"));
        assertTrue(capturedQuery.get().contains("cytotoxicity"));
    }

    @Test
    void shouldPreserveQueriesThatAlreadyDeclareOomyceteScope() {
        AntimicrobialCompoundQueryExpandingRetriever retriever =
                new AntimicrobialCompoundQueryExpandingRetriever(query -> List.<Content>of());
        String query = "\u603b\u7ed3\u5375\u83cc\u6291\u83cc\u5316\u5408\u7269\u4fe1\u606f";

        assertEquals(query, retriever.expand(query));
    }

    @Test
    void shouldPreserveUnrelatedQuestions() {
        AntimicrobialCompoundQueryExpandingRetriever retriever =
                new AntimicrobialCompoundQueryExpandingRetriever(query -> List.<Content>of());
        String query = "RXLR \u6548\u5e94\u5b50\u7684\u529f\u80fd\u662f\u4ec0\u4e48";

        assertEquals(query, retriever.expand(query));
    }
}
