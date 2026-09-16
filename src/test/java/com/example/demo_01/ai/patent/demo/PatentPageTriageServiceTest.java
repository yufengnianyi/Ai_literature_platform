package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageText;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatentPageTriageServiceTest {

    private final PatentPdfTextService textService = mock(PatentPdfTextService.class);
    private final PatentPageTriageService service = new PatentPageTriageService(textService);
    private final Path pdf = Path.of("demo.pdf");

    @Test
    void selectsTestDefinitionActivityTableAndCompoundMapPages() throws IOException {
        when(textService.extractAllPages(pdf)).thenReturn(List.of(
                page(1, "Abstract. Fungicidal compound compositions."),
                page(2, "What is claimed is a compound of formula 1."),
                page(10, """
                        TEST A Grape seedlings were inoculated with Plasmopara viticola.
                        TEST D Tomato seedlings were inoculated with Phytophthora infestans.
                        Visual disease ratings were made after treatment.
                        """),
                page(11, """
                        Cmpd No.   Test A   Test B   Test D
                        49         99       0        100
                        96         0        0        100
                        125        -        0        78
                        183        88       0        0
                        224        72       0        91
                        """),
                page(12, """
                        49 A is A-1b and R2 is 6-Cl.
                        96 A is A-1b and R2 is 6-Br.
                        125 A is A-1c and G is N.
                        """)
        ));

        var result = service.triage(pdf);

        assertThat(result.selectedPages())
                .extracting(page -> page.pageNumber())
                .contains(10, 11, 12)
                .doesNotContain(2);
        assertThat(result.pages().stream()
                .filter(page -> page.pageNumber() == 10)
                .findFirst().orElseThrow().roles())
                .contains(PatentPageRole.TEST_DEFINITION);
        assertThat(result.pages().stream()
                .filter(page -> page.pageNumber() == 11)
                .findFirst().orElseThrow().roles())
                .contains(PatentPageRole.ACTIVITY_TABLE);
        assertThat(result.pages().stream()
                .filter(page -> page.pageNumber() == 12)
                .findFirst().orElseThrow().roles())
                .contains(PatentPageRole.COMPOUND_MAP);
    }

    @Test
    void capsSelectedPagesAtForty() throws IOException {
        List<PatentPageText> pages = java.util.stream.IntStream.rangeClosed(1, 60)
                .mapToObj(page -> page(page, """
                        Cmpd No. Test A Test D
                        1 100 100
                        2 90 90
                        3 80 80
                        4 70 70
                        5 60 60
                        """))
                .toList();
        when(textService.extractAllPages(pdf)).thenReturn(pages);

        var result = service.triage(pdf);

        assertThat(result.selectedPages()).hasSize(40);
    }

    @Test
    void recognizesStarRatingActivityTables() throws IOException {
        when(textService.extractAllPages(pdf)).thenReturn(List.of(
                page(7, """
                        Compound No.   Test A   Test D
                        1              *        ***
                        2              **       *
                        3              ***      -
                        4              -        **
                        5              *        *
                        """)
        ));

        var result = service.triage(pdf);

        assertThat(result.selectedPages()).extracting(page -> page.pageNumber()).containsExactly(7);
        assertThat(result.pages().getFirst().roles()).contains(PatentPageRole.ACTIVITY_TABLE);
    }

    @Test
    void selectsRealCrossPageMethodAndImageTableWithoutInventingTheBody() throws IOException {
        List<PatentPageText> source = fixture("CN106857590B");
        when(textService.extractAllPages(pdf)).thenReturn(source);

        var result = service.triage(pdf);

        assertThat(result.selectedPages()).extracting(PatentPageAssessment::pageNumber).contains(3, 4);
        var method = result.pages().get(2);
        var table = result.pages().get(3);
        assertThat(method.roles()).contains(PatentPageRole.TEST_DEFINITION, PatentPageRole.Q1_ACTIVITY);
        assertThat(method.text()).contains("[evidence_role=METHOD]", "[0012]");
        assertThat(table.roles()).contains(PatentPageRole.ACTIVITY_TABLE, PatentPageRole.Q1_ACTIVITY);
        assertThat(table.reason()).contains("table-image-read-required");
        assertThat(table.text()).contains("[evidence_role=METHOD]", "[evidence_role=RESULT]",
                "[table_status=IMAGE_READ_REQUIRED]", "[0018]", "EC50");
        assertThat(table.text()).doesNotContain("0.881", "147.97");
        assertThat(table.textChars()).isEqualTo((int) source.get(3).text().codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint)).count());
    }

    @ParameterizedTest
    @CsvSource({"CN118339136A,17", "CN111418597A,12", "CN109627065A,11"})
    void inspectsRealTrialsWithoutPromotingBackgroundOrBenefitsToQ1(String patent, int trialPage)
            throws IOException {
        when(textService.extractAllPages(pdf)).thenReturn(fixture(patent));

        var result = service.triage(pdf);

        assertThat(result.pages()).allSatisfy(page ->
                assertThat(page.roles()).doesNotContain(PatentPageRole.Q1_ACTIVITY));
        assertThat(result.selectedPages()).extracting(PatentPageAssessment::pageNumber).contains(trialPage);
        assertThat(result.pages().get(trialPage - 1).text()).contains("[evidence_role=METHOD]");
        assertThat(result.pages().get(trialPage - 1).reason()).contains("no-confirmed-q1-assay");
    }

    @Test
    void doesNotLabelRealCarrierClaimsAndFormulationsAsActivityTables() throws IOException {
        when(textService.extractAllPages(pdf)).thenReturn(fixture("CN118339136A"));

        var result = service.triage(pdf);

        assertThat(result.pages().stream().filter(page -> List.of(2, 5, 7, 14).contains(page.pageNumber())))
                .allSatisfy(page -> assertThat(page.roles()).doesNotContain(PatentPageRole.ACTIVITY_TABLE));
        assertThat(result.pages().get(1).text()).contains("[evidence_role=CLAIMS]");
        assertThat(result.pages().get(16).text()).contains("[evidence_role=METHOD]", "[evidence_role=RESULT]");
    }

    @Test
    void prioritizesConfirmedAssayOverEarlierUnresolvedTablesWithinCap() throws IOException {
        List<PatentPageText> pages = new java.util.ArrayList<>();
        for (int i = 1; i <= 45; i++) {
            pages.add(page(i, "Compound No. Test B\n1 80\n2 90\n"));
        }
        pages.add(page(80, "TEST H Pythium ultimum was inoculated. Inhibition was measured."));
        pages.add(page(81, "Table 1. EC50 toxicity results\n1 0.25\n2 0.50\n"));
        when(textService.extractAllPages(pdf)).thenReturn(pages);

        var result = service.triage(pdf);

        assertThat(result.selectedPages()).hasSize(40);
        assertThat(result.selectedPages()).extracting(PatentPageAssessment::pageNumber).contains(80, 81);
    }

    static List<PatentPageText> fixture(String patent) throws IOException {
        try (var input = PatentPageTriageServiceTest.class.getResourceAsStream(
                "/patent-triage/real-patent-pages.json")) {
            if (input == null) {
                throw new IOException("Missing offline patent triage fixture");
            }
            Map<String, List<PatentPageText>> fixtures = new ObjectMapper().readValue(input,
                    new TypeReference<>() { });
            return fixtures.get(patent);
        }
    }

    private PatentPageText page(int pageNumber, String text) {
        return new PatentPageText(pageNumber, text);
    }
}
