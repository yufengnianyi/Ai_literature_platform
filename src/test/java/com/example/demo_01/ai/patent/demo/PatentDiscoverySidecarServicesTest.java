package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageIndexRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageReadStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentSubjectType;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableCandidateSource;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableKind;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PatentDiscoverySidecarServicesTest {

    @Test
    void subjectResolverUsesAbstractAsRankingSignalOnly() {
        var page = new PatentPageAssessment(1, 8, Set.of(PatentPageRole.ABSTRACT), true,
                "abstract-context", 120, "摘要 本发明涉及一种含噻菌灵和苯噻菌胺的杀菌组合物，可防治马铃薯晚疫病。");
        var subject = new PatentSubjectResolver().resolve(new PatentCandidate(
                "CN-DEMO", "一种含噻菌灵和苯噻菌胺的杀菌组合物", "cat", Path.of("demo.pdf")),
                List.of(page));

        assertThat(subject.subjectType()).isEqualTo(PatentSubjectType.COMBINATION);
        assertThat(subject.coreComponents()).contains("噻菌灵", "苯噻菌胺");
        assertThat(subject.claimedTargets()).contains("马铃薯晚疫病");
    }

    @Test
    void tableCandidateServiceKeepsWeakCaptionOnAssayPage() {
        var page = new PatentPageAssessment(4, 30,
                Set.of(PatentPageRole.TEST_DEFINITION, PatentPageRole.TABLE_CANDIDATE),
                true, "assay-inspection|table-image-read-required", 300,
                """
                试验例1 毒力测定
                采用菌落直径法测定。
                表 1
                处理药剂 72h EC50(mg/L) 共毒系数
                """);
        var triage = new PatentTriageResult(List.of(page), List.of(page));
        var index = List.of(new PatentPageIndexRecord(4, PatentPageReadStatus.READ_OK, null,
                300, 0, List.of()));

        var candidates = new PatentTableCandidateService().candidates(triage, index, null);

        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.source()).isEqualTo(PatentTableCandidateSource.TEXT_CAPTION);
            assertThat(candidate.caption()).contains("表 1");
            assertThat(candidate.selectedForRead()).isTrue();
        });
        assertThat(candidates).anySatisfy(candidate -> {
            assertThat(candidate.source()).isEqualTo(PatentTableCandidateSource.TEXT_MATRIX);
            assertThat(candidate.tableKind()).isEqualTo(PatentTableKind.ACTIVITY);
        });
    }
}
