package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PretreatmentTitleMetadataResolverTest {

    @Test
    void leavesMetadataUnchanged() {
        PretreatmentTitleMetadataResolver resolver = new PretreatmentTitleMetadataResolver();
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

        assertThat(resolved).isSameAs(metadata);
    }
}
