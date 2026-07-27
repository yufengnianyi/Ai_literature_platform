package com.example.demo_01.ai.evidence.table;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeiTableParserTest {

    private static final String TABLE_1 = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0"><text><body>
            <figure xmlns="http://www.tei-c.org/ns/1.0" type="table" xml:id="tab_1"><head>Table 1 .</head><label>1</label><figDesc><div><p><s>Radial Growth-Inhibitory Activity ( IC 50 [mg/ml]) of the Mycelial Extracts from A. camptosporum against Phytopathogenic Microorganisms</s></p></div></figDesc><table><row><cell>Microorganism</cell><cell>IC 50 [mg/ml] a )</cell><cell></cell></row><row><cell></cell><cell>Mycelium extract</cell><cell>Metalaxyl</cell></row><row><cell>Pythium ultimum b )</cell><cell>166.1</cell><cell>296.9</cell></row><row><cell>Pythium debaryanum b )</cell><cell>371.0</cell><cell>531.0</cell></row><row><cell>Pythium polytylum b )</cell><cell>363.2</cell><cell>513.7</cell></row><row><cell>Pythium aphanidermatum c )</cell><cell>36.7</cell><cell>0.05</cell></row><row><cell>Phytophthora cactorum b )</cell><cell>182.2</cell><cell>502.6</cell></row><row><cell>Phytophthora cinnamomi b )</cell><cell>54.3</cell><cell>8.1</cell></row><row><cell>Phytophthora palmivora b )</cell><cell>263.6</cell><cell>494.6</cell></row><row><cell>Phytophthora capsici b )</cell><cell>52.4</cell><cell>0.28</cell></row><row><cell>Phytophthora parasitica d )</cell><cell>117.9</cell><cell>0.07</cell></row><row><cell>Fusarium oxysporum d )</cell><cell>420.0</cell><cell>339.9</cell></row><row><cell>Pestalotiopsis sp. b )</cell><cell>438.6</cell><cell>105.0</cell></row></table><note><p><s>a ) The effective concentration for 50% diameter growth reduction.</s><s>b ) Results after 3 d of incubation.</s><s>c ) Results after 1 d of incubation.</s><s>d ) Results after 4 d of incubation.</s></p></note></figure>
            </body></text></TEI>
            """;

    private TeiTableParser parser;

    @BeforeEach
    void setUp() {
        parser = new TeiTableParser();
        ReflectionTestUtils.setField(parser, "tableSerializer", new TableSerializer());
    }

    @Test
    void parsesSingleStructuredTable() {
        List<ParsedTable> tables = parser.parseAll(TABLE_1);
        assertEquals(1, tables.size());
        ParsedTable table = tables.getFirst();
        assertEquals("T1", table.tableRef());
        assertEquals("1", table.label());
        assertTrue(table.structured());
        assertTrue(table.caption().contains("Radial Growth-Inhibitory Activity"));
        assertTrue(table.caption().contains("A. camptosporum"));
    }

    @Test
    void mergesTwoRowSpanningHeader() {
        ParsedTable table = parser.parseAll(TABLE_1).getFirst();
        assertEquals(3, table.headers().size());
        assertEquals("Microorganism", table.headers().get(0));
        // The IC50 top header spans both value columns and must be forward-filled into each.
        assertTrue(table.headers().get(1).contains("IC 50"));
        assertTrue(table.headers().get(1).contains("Mycelium extract"));
        assertTrue(table.headers().get(2).contains("IC 50"));
        assertTrue(table.headers().get(2).contains("Metalaxyl"));
    }

    @Test
    void extractsElevenDataRows() {
        ParsedTable table = parser.parseAll(TABLE_1).getFirst();
        assertEquals(11, table.rows().size());
        List<String> capsici = table.rows().stream()
                .filter(row -> row.get(0).startsWith("Phytophthora capsici"))
                .findFirst().orElseThrow();
        assertEquals("52.4", capsici.get(1));
        assertEquals("0.28", capsici.get(2));
    }

    @Test
    void extractsFourFootnotes() {
        ParsedTable table = parser.parseAll(TABLE_1).getFirst();
        assertEquals(4, table.footnotes().size());
        assertTrue(table.footnotes().getFirst().startsWith("a )"));
    }

    @Test
    void markdownIsAnchorableForActivityValues() {
        ParsedTable table = parser.parseAll(TABLE_1).getFirst();
        String markdown = table.markdown();
        assertTrue(markdown.contains("Phytophthora capsici b ) | 52.4 | 0.28"));
        assertTrue(markdown.contains("Footnotes:"));
        assertFalse(markdown.isBlank());
    }
}
