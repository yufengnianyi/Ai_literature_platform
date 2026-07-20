package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.rag.parser.DoiNormalizer;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TeiDocumentParserTitleTest {

    @Test
    void rejectsAcceptedArticleBoilerplateTitleAndExtractsDoiFromHeaderText() {
        TeiDocumentParser parser = new TeiDocumentParser();
        ReflectionTestUtils.setField(parser, "doiNormalizer", new DoiNormalizer());
        String tei = """
                <TEI xmlns="http://www.tei-c.org/ns/1.0">
                  <teiHeader>
                    <fileDesc>
                      <titleStmt>
                        <title>This article has been accepted for publication and undergone full peer review but has not been through the copyediting, typesetting, pagination and proofreading process, which may lead to differences between this version and the Version of Record. Please cite this article as</title>
                      </titleStmt>
                      <sourceDesc>
                        <p>Please cite this article as doi: 10.1002/csc2.20424. This article is protected by copyright.</p>
                      </sourceDesc>
                    </fileDesc>
                  </teiHeader>
                  <text><body><p>Body text.</p></body></text>
                </TEI>
                """;

        var metadata = parser.parseMetadata(tei);

        assertThat(metadata.title()).isNull();
        assertThat(metadata.doiNormalized()).isEqualTo("10.1002/csc2.20424");
    }
}
