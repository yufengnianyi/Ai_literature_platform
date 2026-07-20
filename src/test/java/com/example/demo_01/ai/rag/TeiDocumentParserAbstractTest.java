package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.rag.parser.DoiNormalizer;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TeiDocumentParserAbstractTest {

    @Test
    void readsProfileDescAbstractAsMetadataAndChunk() {
        TeiDocumentParser parser = new TeiDocumentParser();
        ReflectionTestUtils.setField(parser, "doiNormalizer", new DoiNormalizer());
        String tei = """
                <TEI xmlns="http://www.tei-c.org/ns/1.0">
                  <teiHeader>
                    <fileDesc>
                      <titleStmt><title>Test paper</title></titleStmt>
                      <sourceDesc><biblStruct><analytic><idno type="DOI">10.1000/test</idno></analytic></biblStruct></sourceDesc>
                    </fileDesc>
                    <profileDesc>
                      <abstract><p>Profile abstract sentence one. Profile abstract sentence two.</p></abstract>
                    </profileDesc>
                  </teiHeader>
                  <text>
                    <body><div><head>Introduction</head><p>Body text.</p></div></body>
                  </text>
                </TEI>
                """;

        var parsed = parser.parse(tei);

        assertThat(parsed.metadata().abstractText()).isEqualTo("Profile abstract sentence one. Profile abstract sentence two.");
        assertThat(parsed.chunkUnits())
                .anySatisfy(unit -> {
                    assertThat(unit.contentType()).isEqualTo("abstract");
                    assertThat(unit.sectionPath()).isEqualTo("Abstract");
                    assertThat(unit.text()).contains("Profile abstract sentence");
                });
    }
}
