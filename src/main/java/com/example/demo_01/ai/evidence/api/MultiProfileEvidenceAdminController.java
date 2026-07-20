package com.example.demo_01.ai.evidence.api;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceService;
import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.user.constant.UserConstant;
import jakarta.annotation.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/admin/evidence/multi-profile-batches")
public class MultiProfileEvidenceAdminController {

    @Resource
    private MultiProfileEvidenceService service;

    @PostMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<BatchAcceptedResponse> submit(
            @RequestBody(required = false) BatchRequest request) {
        return ResultUtils.success(service.submit(request));
    }

    @GetMapping("/{batchId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<BatchRecord> getBatch(@PathVariable UUID batchId) {
        return ResultUtils.success(service.requireBatch(batchId));
    }

    @GetMapping("/{batchId}/documents")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<DocumentPage> getDocuments(
            @PathVariable UUID batchId,
            @RequestParam(required = false) String questionId,
            @RequestParam(required = false) ClassificationStatus classificationStatus,
            @RequestParam(required = false) ProfileExtractionStatus extractionStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResultUtils.success(service.findDocuments(
                batchId, questionId, classificationStatus, extractionStatus, page, size));
    }

    @GetMapping("/{batchId}/records")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<EvidencePage> getRecords(
            @PathVariable UUID batchId,
            @RequestParam(required = false) String questionId,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) ReviewStatus reviewStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResultUtils.success(service.findEvidence(
                batchId, questionId, documentId, reviewStatus, page, size));
    }

    @GetMapping("/{batchId}/export")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public ResponseEntity<FileSystemResource> export(@PathVariable UUID batchId) {
        Path path = service.exportPath(batchId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("oomycete-evidence-" + batchId + ".xlsx",
                                        StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(path.toFile().length())
                .body(new FileSystemResource(path));
    }
}
