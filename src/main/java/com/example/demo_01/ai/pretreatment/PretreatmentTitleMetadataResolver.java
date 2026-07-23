package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import org.springframework.stereotype.Component;

@Component
public class PretreatmentTitleMetadataResolver {

    public RagDocumentMetadata resolve(RagDocumentMetadata metadata) {
        return metadata;
    }
}
