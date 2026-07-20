package com.example.demo_01.ai.entitylibrary.api;

import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryEntryView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ExtractRequest;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ExtractResponse;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewCandidateView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewDecisionRequest;
import com.example.demo_01.ai.entitylibrary.service.EntityCandidateExtractionService;
import com.example.demo_01.ai.entitylibrary.service.EntityReviewService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/entity-library")
public class EntityLibraryController {

    @Resource
    private EntityCandidateExtractionService entityCandidateExtractionService;

    @Resource
    private EntityReviewService entityReviewService;

    @PostMapping("/extract")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ExtractResponse> extract(@RequestBody ExtractRequest request) {
        return ResultUtils.success(entityCandidateExtractionService.extract(
                request == null ? null : request.documentIds(),
                request == null ? null : request.question()));
    }

    @GetMapping("/candidates")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<ReviewCandidateView>> listCandidates(
            @RequestParam(required = false, defaultValue = "PENDING") String status) {
        return ResultUtils.success(entityReviewService.listCandidates(status));
    }

    @PostMapping("/candidates/{candidateId}/decision")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ReviewCandidateView> decide(
            @PathVariable UUID candidateId,
            @RequestBody ReviewDecisionRequest request) {
        return ResultUtils.success(entityReviewService.decide(candidateId, request));
    }

    @GetMapping("/entities")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<EntityLibraryEntryView>> listEntities(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q) {
        return ResultUtils.success(entityReviewService.listEntities(type, q));
    }

    @GetMapping("/entities/{entityId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<EntityLibraryEntryView> getEntity(@PathVariable UUID entityId) {
        return ResultUtils.success(entityReviewService.getEntity(entityId));
    }
}
