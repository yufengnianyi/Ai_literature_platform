package com.example.demo_01.ai.evidence.api;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.EvidencePage;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.DryRunRequest;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.DryRunResult;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionDocumentStatus;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunAccepted;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunDocumentPage;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunRecord;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionModels.ExtractionRunRequest;
import com.example.demo_01.ai.evidence.multiprofile.QuestionExtractionService;
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

/**
 * Stage-4 API: extract evidence for exactly one question against a chosen document set.
 */
@RestController
@RequestMapping("/admin/evidence/question-extractions")
public class QuestionExtractionAdminController {

    @Resource
    private QuestionExtractionService service;

    @PostMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractionRunAccepted> submit(@RequestBody ExtractionRunRequest request) {
        return ResultUtils.success(service.submit(request));
    }

    @GetMapping("/{runId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractionRunRecord> getRun(@PathVariable UUID runId) {
        return ResultUtils.success(service.requireRun(runId));
    }

    @GetMapping("/{runId}/documents")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractionRunDocumentPage> getDocuments(
            @PathVariable UUID runId,
            @RequestParam(required = false) ExtractionDocumentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResultUtils.success(service.findDocuments(runId, status, page, size));
    }

    @GetMapping("/{runId}/records")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<EvidencePage> getRecords(
            @PathVariable UUID runId,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) ReviewStatus reviewStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResultUtils.success(service.findEvidence(runId, documentId, reviewStatus, page, size));
    }

    @PostMapping("/documents/{documentId}/dry-run")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<DryRunResult> dryRun(
            @PathVariable UUID documentId,
            @RequestBody DryRunRequest request) {
        return ResultUtils.success(service.dryRun(documentId, request));
    }
}
