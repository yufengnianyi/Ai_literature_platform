package com.example.demo_01.ai.rag.api;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityBatchExtractionRequest;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityExtraction;
import com.example.demo_01.ai.rag.entity.model.RagDocumentEntityModels.RagDocumentEntityExtractionRequest;
import com.example.demo_01.ai.rag.entity.service.RagDocumentEntityExtractionService;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagBatchAcceptedResponse;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatsResponse;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagUploadAcceptedResponse;
import com.example.demo_01.ai.rag.service.RagBatchIngestionService;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import com.example.demo_01.ai.rag.service.RagIngestionFromArtifactService;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/rag/documents")
public class RagDocumentController {

    @Resource
    private RagDocumentIngestionService ragDocumentIngestionService;

    @Resource
    private RagBatchIngestionService ragBatchIngestionService;

    @Resource
    private RagIngestionFromArtifactService ragIngestionFromArtifactService;

    @Resource
    private RagDocumentEntityExtractionService ragDocumentEntityExtractionService;

    @PostMapping(consumes = "multipart/form-data")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public ResponseEntity<BaseResponse<RagUploadAcceptedResponse>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.accepted().body(ResultUtils.success(ragDocumentIngestionService.upload(file)));
    }

    @PostMapping(value = "/batch", consumes = "multipart/form-data")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public ResponseEntity<BaseResponse<RagBatchAcceptedResponse>> uploadBatch(@RequestPart("files") MultipartFile[] files) {
        return ResponseEntity.accepted().body(ResultUtils.success(ragBatchIngestionService.uploadFiles(files)));
    }

    @PostMapping("/{documentId}/ingest")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public ResponseEntity<BaseResponse<RagUploadAcceptedResponse>> ingest(@PathVariable UUID documentId) {
        return ResponseEntity.accepted().body(ResultUtils.success(ragIngestionFromArtifactService.enqueueDocument(documentId, null)));
    }

    @GetMapping("/stats")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagDocumentStatsResponse> getStats() {
        return ResultUtils.success(ragDocumentIngestionService.getStats());
    }

    @GetMapping("/{documentId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagDocumentRecord> getDocument(@PathVariable UUID documentId) {
        return ResultUtils.success(ragDocumentIngestionService.getDocument(documentId));
    }

    @PostMapping("/{documentId}/entities/extract")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagDocumentEntityExtraction> extractEntities(
            @PathVariable UUID documentId,
            @RequestBody(required = false) RagDocumentEntityExtractionRequest request) {
        String question = request == null ? null : request.question();
        return ResultUtils.success(ragDocumentEntityExtractionService.extractDocument(documentId, question));
    }

    @PostMapping("/entities/extract-batch")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<RagDocumentEntityExtraction>> extractEntitiesBatch(
            @RequestBody RagDocumentEntityBatchExtractionRequest request) {
        return ResultUtils.success(ragDocumentEntityExtractionService.extractBatch(
                request == null ? null : request.documentIds(),
                request == null ? null : request.question()));
    }
}
