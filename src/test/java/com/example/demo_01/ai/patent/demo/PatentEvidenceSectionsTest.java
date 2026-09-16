package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageText;
import com.example.demo_01.ai.patent.demo.PatentEvidenceSections.EvidenceRole;
import com.example.demo_01.ai.patent.demo.PatentEvidenceSections.Section;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.example.demo_01.ai.patent.demo.PatentPageTriageServiceTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;

class PatentEvidenceSectionsTest {

    @Test
    void preservesEveryOriginalCharacterInRealPatentParagraphs() throws IOException {
        for (String patent : List.of("CN106857590B", "CN118339136A", "CN111418597A", "CN109627065A")) {
            var pages = fixture(patent);
            var sections = PatentEvidenceSections.analyze(pages);
            for (var page : pages) {
                var pageSections = sections.stream().filter(section -> section.pageNumber() == page.pageNumber()).toList();
                assertThat(pageSections.stream().map(Section::text).collect(Collectors.joining()))
                        .as("%s page %s", patent, page.pageNumber()).isEqualTo(page.text());
                assertThat(PatentEvidenceSections.annotate(pageSections))
                        .contains(pageSections.stream().map(Section::text).toArray(String[]::new));
            }
        }
    }

    @Test
    void distinguishesAllSixRolesOnMixedPages() {
        var sections = PatentEvidenceSections.analyze(List.of(new PatentPageText(1, """
                \u6743\u5229\u8981\u6c42\u4e66
                1. \u5316\u5408\u7269\u53ef\u7528\u4e8e\u9632\u6cbb\u8150\u9709\u75c5\u3002
                \u80cc\u666f\u6280\u672f
                [0001] \u73b0\u6709\u836f\u5242\u53ef\u4ee5\u63a7\u5236\u5375\u83cc\u7eb2\u75c5\u5bb3\u3002
                \u53d1\u660e\u5185\u5bb9
                [0002] \u672c\u53d1\u660e\u5316\u5408\u7269\u53ef\u7528\u4e8e\u9632\u6cbb\u971c\u9709\u75c5\u3002
                \u5177\u4f53\u5b9e\u65bd\u65b9\u5f0f
                [0003] \u8bd5\u9a8c\u4f8b1\uff1a\u4f9b\u8bd5\u83cc\u682a\u4e3a\u79be\u8c37\u9570\u5200\u83cc\u3002
                [0004] \u63a5\u79cd\u540e\u57f9\u517b4\u5929\uff0c\u6d4b\u5b9a\u751f\u957f\u6291\u5236\u7387\u3002\u7ed3\u679c\uff0c\u6291\u5236\u7387\u4e3a70%\u3002
                """)));

        assertThat(sections).extracting(Section::role).contains(
                EvidenceRole.CLAIMS, EvidenceRole.BACKGROUND, EvidenceRole.CLAIMED_USE,
                EvidenceRole.OTHER, EvidenceRole.METHOD, EvidenceRole.RESULT);
        assertThat(sections).noneMatch(Section::q1Eligible);
        assertThat(sections).filteredOn(section -> section.text().contains("\u73b0\u6709\u836f\u5242"))
                .allSatisfy(section -> assertThat(section.role()).isEqualTo(EvidenceRole.BACKGROUND));
    }

    @Test
    void keepsRealBackgroundAndCrossPageBenefitsOutOfAssays() throws IOException {
        var background = PatentEvidenceSections.analyze(fixture("CN111418597A"));
        assertThat(background).filteredOn(section -> section.text().contains("\u5375\u83cc\u7eb2"))
                .isNotEmpty().allSatisfy(section -> {
                    assertThat(section.role()).isEqualTo(EvidenceRole.BACKGROUND);
                    assertThat(section.assayId()).isZero();
                });

        var benefit = PatentEvidenceSections.analyze(fixture("CN109627065A"));
        assertThat(benefit).filteredOn(section -> section.text().contains("\u5236\u8150\u9709\u83cc"))
                .isNotEmpty().allSatisfy(section -> {
                    assertThat(section.role()).isEqualTo(EvidenceRole.CLAIMED_USE);
                    assertThat(section.q1Eligible()).isFalse();
                });
        assertThat(benefit).filteredOn(section -> section.pageNumber() == 12 && section.text().contains("\u88681\u4e0d\u540c"))
                .allSatisfy(section -> {
                    assertThat(section.role()).isEqualTo(EvidenceRole.RESULT);
                    assertThat(section.activityTable()).isFalse();
                });
    }

    @Test
    void resolvesTargetsAcrossPagesButNotAcrossDifferentAssays() {
        var sections = PatentEvidenceSections.analyze(List.of(
                page(10, "TEST A\nSeedlings were inoculated with Pythium ultimum.\n"),
                page(11, "[0031] EC50 inhibition results were measured at 48 hours.\n"),
                page(12, "TEST B\nSeedlings were inoculated with Ustilago maydis. Inhibition was measured.\n"),
                page(13, "Compound No. Test B\n1 80\n2 90\n"),
                page(14, "Compound No. Test A Test H\n1 90 30\n2 95 40\n")));

        assertThat(sections).filteredOn(section -> section.pageNumber() <= 11)
                .allMatch(Section::q1Eligible);
        assertThat(sections).filteredOn(section -> section.pageNumber() == 12 || section.pageNumber() == 13)
                .noneMatch(Section::q1Eligible);
        assertThat(sections).filteredOn(section -> section.pageNumber() == 14).allMatch(Section::q1Eligible);
    }

    @Test
    void retainsCaptionOnlyImageTableAndRecognizesTextBodyOnNextPage() {
        var sections = PatentEvidenceSections.analyze(List.of(
                page(3, "[0001] \u751f\u7269\u6d4b\u5b9a\uff1a\u63a5\u79cd\u75ab\u9709\u83cc\uff0c\u6d4b\u5b9a\u6291\u5236\u7387\u3002\n"),
                page(4, "[0002] \u88681 \u6bd2\u529b\u6d4b\u5b9a\u7ed3\u679c EC50\n[0003]\n"),
                page(5, "1 0.25\n2 0.50\n"),
                page(6, "[0004] \u88682 \u751f\u7269\u6d3b\u6027\u6d4b\u5b9a\u7ed3\u679c\n[0005]\n")));

        assertThat(sections).filteredOn(section -> section.pageNumber() == 4 && section.activityTable())
                .isNotEmpty().noneMatch(Section::imageReadRequired);
        assertThat(sections).filteredOn(section -> section.pageNumber() == 5)
                .allMatch(Section::activityTable);
        assertThat(sections).filteredOn(section -> section.pageNumber() == 6 && section.activityTable())
                .isNotEmpty().allMatch(Section::imageReadRequired);
    }

    @Test
    void doesNotInferTablesFromProseOrTransferTargetsAcrossMissingPages() {
        var sections = PatentEvidenceSections.analyze(List.of(
                page(1, "\u5316\u5408\u7269\u5bf9\u75ab\u9709\u7684\u9632\u6548\u8f83\u597d\uff0c\u5305\u542b\u975e\u6d3b\u6027\u62c5\u8f7d\u4f53\u53ca\u8868\u9762\u6d3b\u6027\u5242\u3002\n"),
                page(2, "TEST A Pythium was inoculated. Inhibition was measured.\n"),
                page(20, "Table 1 EC50 results\n1 0.25\n2 0.50\n")));

        assertThat(sections).filteredOn(section -> section.pageNumber() == 1).noneMatch(Section::activityTable);
        assertThat(sections).filteredOn(section -> section.pageNumber() == 20).noneMatch(Section::q1Eligible);
    }

    @Test
    void handlesEmptyTextAndDoesNotShareStateBetweenDocuments() {
        assertThat(PatentEvidenceSections.analyze(List.of(page(1, null), page(2, "")))).isEmpty();
        PatentEvidenceSections.analyze(List.of(page(1, "TEST A Pythium was inoculated. Inhibition was measured.")));
        assertThat(PatentEvidenceSections.analyze(List.of(page(1, "Compound No. Test A\n1 100\n"))))
                .noneMatch(Section::q1Eligible);
    }

    @Test
    void carriesNamedTestColumnsOntoFollowingTablePages() {
        var sections = PatentEvidenceSections.analyze(List.of(
                page(1, "TEST H Pythium was inoculated. Inhibition was measured.\n"),
                page(2, "Compound No. Test H\n1 100\n2 90\n"),
                page(3, "3 80\n4 70\n")));

        assertThat(sections).filteredOn(section -> section.pageNumber() == 3)
                .isNotEmpty().allSatisfy(section -> {
                    assertThat(section.activityTable()).isTrue();
                    assertThat(section.q1Eligible()).isTrue();
                    assertThat(section.imageReadRequired()).isFalse();
                });
    }

    private PatentPageText page(int number, String text) {
        return new PatentPageText(number, text);
    }
}
