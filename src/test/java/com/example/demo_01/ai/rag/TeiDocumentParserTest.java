package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.rag.model.RagPipelineModels.ParsedTeiDocument;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.parser.DoiNormalizer;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeiDocumentParserTest {

    private TeiDocumentParser teiDocumentParser;

    @BeforeEach
    void setUp() {
        teiDocumentParser = new TeiDocumentParser();
        ReflectionTestUtils.setField(teiDocumentParser, "doiNormalizer", new DoiNormalizer());
    }

    @Test
    void parseMetadataShouldExtractCoreFields() {
        RagDocumentMetadata metadata = teiDocumentParser.parseMetadata(sampleTei());

        assertEquals("10.1234/example.doi", metadata.doiNormalized());
        assertEquals("Sample Paper", metadata.title());
        assertEquals("Journal of Testing", metadata.journal());
        assertEquals(2024, metadata.publicationYear());
        assertEquals(2, metadata.authors().size());
        assertEquals(2, metadata.affiliations().size());
        assertTrue(metadata.abstractText().contains("This is the abstract."));
    }

    @Test
    void parseShouldExposeBodySentencesAndCaptions() {
        ParsedTeiDocument parsed = teiDocumentParser.parse(sampleTei());

        assertTrue(parsed.chunkUnits().stream().anyMatch(unit -> unit.contentType().equals("abstract")));
        assertTrue(parsed.chunkUnits().stream().anyMatch(unit -> unit.contentType().equals("body") && unit.sectionPath().equals("Introduction")));
        assertTrue(parsed.chunkUnits().stream().anyMatch(unit -> unit.contentType().equals("figure_caption")));
        assertTrue(parsed.chunkUnits().stream().anyMatch(unit -> unit.contentType().equals("table_caption")));
    }

    private String sampleTei() {
        return """
                <TEI xmlns=\"http://www.tei-c.org/ns/1.0\">
                  <teiHeader>
                    <fileDesc>
                      <titleStmt>
                        <title>Sample Paper</title>
                        <author><persName><forename>Alice</forename><surname>Ng</surname></persName></author>
                        <author><persName><forename>Bob</forename><surname>Li</surname></persName></author>
                      </titleStmt>
                      <sourceDesc>
                        <biblStruct>
                          <analytic>
                            <title>Sample Paper</title>
                            <author><persName>Alice Ng</persName><affiliation>Institute A</affiliation></author>
                            <author><persName>Bob Li</persName><affiliation>Institute B</affiliation></author>
                          </analytic>
                          <monogr>
                            <title>Journal of Testing</title>
                            <imprint><date when=\"2024-01-15\">2024</date></imprint>
                          </monogr>
                          <idno type=\"DOI\">10.1234/Example.DOI</idno>
                        </biblStruct>
                      </sourceDesc>
                    </fileDesc>
                  </teiHeader>
                  <text>
                    <front>
                      <abstract>
                        <p><s>This is the abstract.</s><s>It has two sentences.</s></p>
                      </abstract>
                    </front>
                    <body>
                      <div>
                        <head>Introduction</head>
                        <p><s>Sentence one.</s><s>Sentence two.</s></p>
                      </div>
                      <figure>
                        <head>Figure 1</head>
                        <figDesc>Figure caption text.</figDesc>
                      </figure>
                      <figure type=\"table\">
                        <head>Table 1</head>
                        <figDesc>Table caption text.</figDesc>
                      </figure>
                    </body>
                  </text>
                </TEI>
                """;
    }
}
