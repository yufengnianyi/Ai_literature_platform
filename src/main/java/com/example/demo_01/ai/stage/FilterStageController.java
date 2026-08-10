package com.example.demo_01.ai.stage;

import com.example.demo_01.ai.pretreatment.PretreatmentModels.FinalDecision;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FilterRunAccepted;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.FilterRunRequest;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentDocumentPage;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunRecord;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.PretreatmentRunSummary;
import com.example.demo_01.ai.pretreatment.PretreatmentService;
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
@RequestMapping("/stages/filter/runs")
public class FilterStageController {

    @Resource
    private PretreatmentService pretreatmentService;

    @PostMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<FilterRunAccepted> submit(
            @RequestBody(required = false) FilterRunRequest request) {
        return ResultUtils.success(pretreatmentService.submitFilterRun());
    }

    @GetMapping("/{runId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PretreatmentRunRecord> getRun(@PathVariable UUID runId) {
        return ResultUtils.success(pretreatmentService.requireRun(runId));
    }

    @GetMapping("/{runId}/documents")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PretreatmentDocumentPage> getDocuments(
            @PathVariable UUID runId,
            @RequestParam(required = false) FinalDecision finalDecision,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResultUtils.success(pretreatmentService.findDocuments(
                runId, finalDecision, page, size));
    }

    @PostMapping("/{runId}/vector-gc")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PretreatmentRunSummary> garbageCollectVectors(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return ResultUtils.success(pretreatmentService.garbageCollectRejectedVectors(runId, dryRun));
    }
}
