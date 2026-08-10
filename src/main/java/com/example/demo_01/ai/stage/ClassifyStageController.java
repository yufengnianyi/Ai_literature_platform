package com.example.demo_01.ai.stage;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.BatchAcceptedResponse;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.BatchRecord;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.BatchRequest;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ClassificationStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.DocumentPage;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ProfileExtractionStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceService;
import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.user.constant.UserConstant;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/stages/classify/runs")
public class ClassifyStageController {

    @Resource
    private MultiProfileEvidenceService service;

    @PostMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<BatchAcceptedResponse> submit(
            @RequestBody(required = false) BatchRequest request) {
        BatchRequest classifyOnly = request == null
                ? new BatchRequest(null, null, null, false, null, false)
                : new BatchRequest(
                        request.sourceExperimentId(),
                        request.pretreatmentRunId(),
                        request.cohortId(),
                        request.force(),
                        request.expectedDocuments(),
                        false);
        return ResultUtils.success(service.submit(classifyOnly));
    }

    @GetMapping("/{runId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<BatchRecord> getRun(@PathVariable UUID runId) {
        return ResultUtils.success(service.requireBatch(runId));
    }

    @GetMapping("/{runId}/documents")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<DocumentPage> getDocuments(
            @PathVariable UUID runId,
            @RequestParam(required = false) String questionId,
            @RequestParam(required = false) ClassificationStatus classificationStatus,
            @RequestParam(required = false) ProfileExtractionStatus extractionStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResultUtils.success(service.findDocuments(
                runId, questionId, classificationStatus, extractionStatus, page, size));
    }
}
