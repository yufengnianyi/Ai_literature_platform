package com.example.demo_01.ai.kg.api;

import com.example.demo_01.ai.kg.model.KgModels.KgExtractionJobView;
import com.example.demo_01.ai.kg.service.KgGraphPipelineService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/kg/documents")
public class KgController {

    @Resource
    private KgGraphPipelineService kgGraphPipelineService;

    @PostMapping("/{documentId}/extract")
    public BaseResponse<KgTriggerResponse> extract(@PathVariable UUID documentId) {
        UUID jobId = kgGraphPipelineService.enqueueExistingDocument(documentId);
        if (jobId == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "KG pipeline is disabled or no chunks were found");
        }
        return ResultUtils.success(new KgTriggerResponse(documentId, jobId, "QUEUED"));
    }

    @GetMapping("/{documentId}/job")
    public BaseResponse<KgExtractionJobView> latestJob(@PathVariable UUID documentId) {
        KgExtractionJobView job = kgGraphPipelineService.getLatestJob(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "KG job not found for document: " + documentId));
        return ResultUtils.success(job);
    }

    @GetMapping("/{documentId}/payload")
    public BaseResponse<Map<String, Object>> payload(@PathVariable UUID documentId) {
        Map<String, Object> payload = kgGraphPipelineService.getPaperPayload(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "KG payload not found for document: " + documentId));
        return ResultUtils.success(payload);
    }

    @GetMapping("/{documentId}/entities")
    public BaseResponse<List<Map<String, Object>>> entities(@PathVariable UUID documentId) {
        return ResultUtils.success(kgGraphPipelineService.getChunkEntities(documentId));
    }

    @GetMapping("/{documentId}/relations")
    public BaseResponse<List<Map<String, Object>>> relations(@PathVariable UUID documentId) {
        return ResultUtils.success(kgGraphPipelineService.getChunkRelations(documentId));
    }

    public record KgTriggerResponse(UUID documentId, UUID jobId, String status) {
    }
}
