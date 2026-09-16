package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoDocumentPage;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunAccepted;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDemoRunRequest;
import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.user.constant.UserConstant;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/patents/demo-runs")
public class PatentDemoAdminController {

    @Resource
    private PatentDemoPipelineService service;

    @PostMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public ResponseEntity<BaseResponse<PatentDemoRunAccepted>> submit(
            @RequestBody(required = false) PatentDemoRunRequest request) {
        return ResponseEntity.accepted().body(ResultUtils.success(service.submit(request)));
    }

    @GetMapping("/{runId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PatentDemoRunRecord> getRun(@PathVariable UUID runId) {
        return ResultUtils.success(service.requireRun(runId));
    }

    @GetMapping("/{runId}/documents")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PatentDemoDocumentPage> getDocuments(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResultUtils.success(service.findDocuments(runId, page, size));
    }
}
