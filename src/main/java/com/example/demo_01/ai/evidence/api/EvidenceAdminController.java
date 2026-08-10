package com.example.demo_01.ai.evidence.api;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.ai.evidence.model.EvidenceModels.*;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.evidence.service.EvidenceExtractionService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.user.constant.UserConstant;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/evidence")
public class EvidenceAdminController {

    @Resource
    private EvidenceExtractionService extractionService;

    @Resource
    private EvidenceRepository evidenceRepository;

    @PostMapping("/documents/{documentId}/extract")
    @Deprecated
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractionAcceptedResponse> extract(
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "false") boolean force) {
        return ResultUtils.success(extractionService.enqueue(documentId, force));
    }

    @PostMapping("/extractions/backfill")
    @Deprecated
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<EvidenceBackfillResponse> backfill(
            @RequestBody(required = false) EvidenceBackfillRequest request) {
        return ResultUtils.success(extractionService.backfill(request != null && request.force()));
    }

    @GetMapping("/extractions/{runId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractionRunRecord> getRun(@PathVariable UUID runId) {
        return ResultUtils.success(evidenceRepository.findRun(runId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Evidence extraction run not found: " + runId)));
    }

    @GetMapping("/extractions/batches/{batchId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractionBatchRecord> getBatch(@PathVariable UUID batchId) {
        return ResultUtils.success(evidenceRepository.findBatch(batchId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Evidence extraction batch not found: " + batchId)));
    }

    @GetMapping("/extractions/batches/{batchId}/documents")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractionRunPage> getBatchDocuments(
            @PathVariable UUID batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (evidenceRepository.findBatch(batchId).isEmpty()) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND_ERROR, "Evidence extraction batch not found: " + batchId);
        }
        return ResultUtils.success(evidenceRepository.findBatchRuns(batchId, page, size));
    }
}
