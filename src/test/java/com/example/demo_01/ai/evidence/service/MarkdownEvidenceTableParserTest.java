package com.example.demo_01.ai.evidence.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarkdownEvidenceTableParserTest {

    private final MarkdownEvidenceTableParser parser = new MarkdownEvidenceTableParser();

    @Test
    void parsesExactSixteenColumnTableAndUnescapesPipe() {
        List<String> cells = row();
        cells.set(0, "compound 7");
        cells.set(7, "24h EC50=2.3 \\| 48h EC50=1.8");

        var parsed = parser.parse(table(cells));

        assertEquals(1, parsed.rows().size());
        assertEquals("compound 7", parsed.rows().getFirst().compoundOriginalName());
        assertEquals("24h EC50=2.3 | 48h EC50=1.8", parsed.rows().getFirst().activityData());
    }

    @Test
    void removesBlankAndDuplicateRows() {
        List<String> cells = row();
        cells.set(0, "eugenol");
        cells.set(5, "Phytophthora infestans");
        cells.set(6, "菌丝生长抑制");
        cells.set(7, "EC50 = 12.5 μg/mL");
        String markdown = header() + separator() + markdownRow(cells) + markdownRow(cells)
                + markdownRow(row());

        assertEquals(1, parser.parse(markdown).rows().size());
    }

    @Test
    void rejectsAdditionalTextAndWrongColumnCount() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("说明文字\n" + header() + separator()));

        List<String> fifteenCells = new ArrayList<>(row());
        fifteenCells.removeLast();
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(header() + separator() + markdownRow(fifteenCells)));
    }

    @Test
    void rejectsMultipleMarkdownTables() {
        List<String> first = row();
        first.set(0, "compound 1");
        List<String> second = row();
        second.set(0, "compound 2");

        String markdown = header() + separator() + markdownRow(first)
                + header() + separator() + markdownRow(second);

        assertThrows(IllegalArgumentException.class, () -> parser.parse(markdown));
    }

    @Test
    void rendersDeterministicTableAndEscapesPipe() {
        List<String> cells = row();
        cells.set(0, "compound | 7");
        cells.set(7, "24h EC50=2.3 | 48h EC50=1.8");
        var source = EvidenceModels.CompoundEvidenceRow.fromCells(cells);

        String rendered = parser.render(List.of(source));
        var parsed = parser.parse(rendered);

        assertEquals(1, parsed.rows().size());
        assertEquals(source.cells(), parsed.rows().getFirst().cells());
        assertEquals(18, rendered.lines().findFirst().orElseThrow().split("\\|", -1).length);
    }

    @Test
    void rendersHeaderOnlyForEmptyRows() {
        String rendered = parser.render(List.of());

        assertEquals(2, rendered.lines().count());
        assertEquals(0, parser.parse(rendered).rows().size());
    }

    private String table(List<String> cells) {
        return header() + separator() + markdownRow(cells);
    }

    private String header() {
        return markdownRow(EvidenceModels.HEADERS);
    }

    private String separator() {
        return markdownRow(java.util.Collections.nCopies(16, "---"));
    }

    private String markdownRow(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |\n";
    }

    private List<String> row() {
        return new ArrayList<>(java.util.Collections.nCopies(16, ""));
    }
}
