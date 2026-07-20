package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.pretreatment.CrossrefClient.CrossrefWork;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PretreatmentTitleMetadataResolverTest {

    @Test
    void replacesAcceptedArticleBoilerplateTitleWithCrossrefTitle() {
        CrossrefClient crossrefClient = mock(CrossrefClient.class);
        when(crossrefClient.findByDoi("10.1002/csc2.20584"))
                .thenReturn(new CrossrefWork(
                        "Spatial modeling increases accuracy of selection for <i>Phytophthora infestans</i>-resistant tomato genotypes",
                        "Crop Science",
                        List.of("1435-0653"),
                        "Wiley"));
        PretreatmentTitleMetadataResolver resolver = new PretreatmentTitleMetadataResolver();
        ReflectionTestUtils.setField(resolver, "crossrefClient", crossrefClient);
        RagDocumentMetadata metadata = new RagDocumentMetadata(
                "10.1002/csc2.20584",
                "10.1002/csc2.20584",
                "This article has been accepted for publication and undergone full peer review but has not been through the copyediting, typesetting, pagination and proofreading process, which may lead to differences between this version and the Version of Record. Please cite this article as",
                List.of(),
                List.of(),
                "Abstract text",
                "This article has been accepted for publication and undergone full peer review but has not been through the copyediting, typesetting, pagination and proofreading process, which may lead to differences between this version and the Version of Record. Please cite this article as",
                null,
                null);

        RagDocumentMetadata resolved = resolver.resolve(metadata);

        assertThat(resolved.title()).isEqualTo("Spatial modeling increases accuracy of selection for Phytophthora infestans-resistant tomato genotypes");
        assertThat(resolved.journal()).isEqualTo("Crop Science");
    }
}
