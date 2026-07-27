package com.example.demo_01.ai.evidence.table;

import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TableLegendResolverTest {

    private final TableLegendResolver resolver = new TableLegendResolver();

    private ParsedTable table() {
        return new ParsedTable(
                "T1", "1",
                "Radial Growth-Inhibitory Activity (IC 50 [mg/ml]) of the Mycelial Extracts "
                        + "from A. camptosporum against Phytopathogenic Microorganisms",
                List.of("Microorganism", "Mycelium extract", "Metalaxyl"),
                List.of(List.of("Phytophthora capsici b )", "52.4", "0.28")),
                List.of("a ) The effective concentration for 50% diameter growth reduction."),
                "| ... |", true, "<figure/>");
    }

    @Test
    void expandsAbbreviatedGenusFromBodyContext() {
        List<EvidenceChunk> context = List.of(
                new EvidenceChunk("doc:3", "Introduction", 1, 1, 1,
                        "The endophytic fungus Arthrinium camptosporum was isolated from ...",
                        "body", "/x/document.tei.xml"));
        TableLegendResolver.TableLegend legend = resolver.resolve(table(), context);
        assertTrue(legend.legendText().contains("A. camptosporum = Arthrinium camptosporum"),
                legend.legendText());
        assertTrue(legend.supportingChunkIds().contains("doc:3"));
        // The verbatim defining sentence must be captured so it can be embedded with the table.
        assertTrue(legend.supportingQuotes().stream()
                        .anyMatch(quote -> quote.contains("Arthrinium camptosporum was isolated")),
                legend.supportingQuotes().toString());
    }

    @Test
    void resolvesExplicitAbbreviationDefinition() {
        List<EvidenceChunk> context = List.of(
                new EvidenceChunk("doc:4", "Methods", 1, 1, 1,
                        "Isolates tested were Pc = Phytophthora capsici and others.",
                        "body", "/x/document.tei.xml"));
        TableLegendResolver.TableLegend legend = resolver.resolve(table(), context);
        assertTrue(legend.legendText().contains("Pc = Phytophthora capsici"), legend.legendText());
    }
}
