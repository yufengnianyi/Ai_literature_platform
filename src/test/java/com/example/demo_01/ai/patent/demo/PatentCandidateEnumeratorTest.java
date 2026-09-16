package com.example.demo_01.ai.patent.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatentCandidateEnumeratorTest {

    @TempDir
    private Path tempDir;

    @Test
    void filtersPublicationNumbersBeforeShuffleAndLimit() throws Exception {
        Path manifest = tempDir.resolve("manifest.csv");
        Files.writeString(manifest, """
                publication_number,title,simple_category,pdf_path
                CN-OTHER,Other,cat,
                CN106857590B,Target,cat,
                CN-WRONG-CAT,Wrong category,other,
                CN-MISSING,Missing PDF,cat,
                """);
        for (String publication : List.of("CN-OTHER", "CN106857590B", "CN-WRONG-CAT")) {
            Files.writeString(tempDir.resolve(publication + ".pdf"), "pdf");
        }
        PatentCandidateEnumerator enumerator = new PatentCandidateEnumerator();
        for (Long seed : new Long[]{null, 1L, 2L, 3L}) {
            var result = enumerator.enumerate(manifest, tempDir, "cat", 1, seed,
                    List.of("CN106857590B", "CN-WRONG-CAT", "CN-MISSING"));
            assertThat(result.totalCandidates()).isEqualTo(1);
            assertThat(result.selectedCandidates()).extracting(candidate -> candidate.publicationNumber())
                    .containsExactly("CN106857590B");
        }
        assertThat(enumerator.enumerate(manifest, tempDir, "cat", 1, null).selectedCandidates())
                .extracting(candidate -> candidate.publicationNumber()).containsExactly("CN-OTHER");
        assertThat(enumerator.enumerate(manifest, tempDir, "cat", 1, null, List.of("UNKNOWN"))
                .selectedCandidates()).isEmpty();
    }

    @Test
    void readsGb18030ManifestAndFallsBackToPublicationPdfPath() throws Exception {
        String category = "\u5316\u5b66\u519c\u836f/\u6740\u83cc\u5242/\u6d3b\u6027\u5316\u5408\u7269";
        Path pdfRoot = tempDir.resolve("pdf");
        Files.createDirectories(pdfRoot);
        Files.writeString(pdfRoot.resolve("AU1.pdf"), "pdf");
        Path manifest = tempDir.resolve("patent_classified_simple.csv");
        Files.writeString(manifest, """
                publication_number,title,simple_category,pdf_path
                AU1,"Fungicidal, heterocyclic compounds","%s",
                AU2,Other patent,Other,
                AU3,Missing PDF,"%s",
                """.formatted(category, category), Charset.forName("GB18030"));

        var result = new PatentCandidateEnumerator()
                .enumerate(manifest, pdfRoot, category, 10, null);

        assertThat(result.totalCandidates()).isEqualTo(1);
        assertThat(result.selectedCandidates()).hasSize(1);
        assertThat(result.selectedCandidates().get(0).publicationNumber()).isEqualTo("AU1");
        assertThat(result.selectedCandidates().get(0).title())
                .isEqualTo("Fungicidal, heterocyclic compounds");
    }
}
