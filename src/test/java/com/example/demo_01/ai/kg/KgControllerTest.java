package com.example.demo_01.ai.kg;

import com.example.demo_01.ai.kg.api.KgController;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncStatus;
import com.example.demo_01.ai.kg.model.KgModels.KgExtractionJobView;
import com.example.demo_01.ai.kg.model.KgModels.KgExtractionStatus;
import com.example.demo_01.ai.kg.service.KgGraphPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KgControllerTest {

    private KgGraphPipelineService kgGraphPipelineService;
    private KgController controller;

    @BeforeEach
    void setUp() {
        kgGraphPipelineService = mock(KgGraphPipelineService.class);
        controller = new KgController();
        ReflectionTestUtils.setField(controller, "kgGraphPipelineService", kgGraphPipelineService);
    }

    @Test
    void payloadShouldReturnStoredPayload() {
        UUID documentId = UUID.randomUUID();
        when(kgGraphPipelineService.getPaperPayload(documentId)).thenReturn(Optional.of(Map.of("documentId", documentId.toString(), "schemaVersion", "v1")));

        var response = controller.payload(documentId);

        assertEquals(documentId.toString(), response.getData().get("documentId"));
    }

    @Test
    void latestJobShouldReturnStoredJob() {
        UUID documentId = UUID.randomUUID();
        KgExtractionJobView job = new KgExtractionJobView(UUID.randomUUID(), documentId, KgExtractionStatus.COMPLETED, null, null,
                2, 1, "payload.json", GraphBuilderSyncStatus.SKIPPED);
        when(kgGraphPipelineService.getLatestJob(documentId)).thenReturn(Optional.of(job));

        var response = controller.latestJob(documentId);

        assertEquals(KgExtractionStatus.COMPLETED, response.getData().status());
    }
}
