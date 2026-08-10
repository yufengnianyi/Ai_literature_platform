package com.example.demo_01.ai.preprocessing.api;

import com.example.demo_01.ai.evidence.table.TableJsonlMaterializationService;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.FolderPreprocessRequest;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessAcceptedResponse;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchAcceptedResponse;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessBatchRecord;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessJobRecord;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.TableBackfillRequest;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.TableBackfillResponse;
import com.example.demo_01.ai.preprocessing.service.DocumentPreprocessBatchService;
import com.example.demo_01.ai.preprocessing.service.DocumentPreprocessService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
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

import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequestMapping("/preprocess")
public class PreprocessController {

    @Resource
    private DocumentPreprocessService documentPreprocessService;

    @Resource
    private DocumentPreprocessBatchService documentPreprocessBatchService;

    @Resource
    private TableJsonlMaterializationService tableJsonlMaterializationService;

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    public ResponseEntity<BaseResponse<PreprocessAcceptedResponse>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.accepted().body(ResultUtils.success(documentPreprocessService.upload(file)));
    }

    @GetMapping("/jobs/{jobId}")
    public BaseResponse<PreprocessJobRecord> getJob(@PathVariable UUID jobId) {
        return ResultUtils.success(documentPreprocessService.getJob(jobId));
    }

    @PostMapping("/batches/folder")
    public ResponseEntity<BaseResponse<PreprocessBatchAcceptedResponse>> preprocessFolder(@RequestBody FolderPreprocessRequest request) {
        return ResponseEntity.accepted().body(ResultUtils.success(documentPreprocessBatchService.preprocessFolder(request.folderPath())));
    }

    @GetMapping("/batches/{batchId}")
    public BaseResponse<PreprocessBatchRecord> getBatch(@PathVariable UUID batchId) {
        return ResultUtils.success(documentPreprocessBatchService.getBatch(batchId));
    }

    @PostMapping("/tables/backfill")
    public BaseResponse<TableBackfillResponse> backfillTables(
            @RequestBody(required = false) TableBackfillRequest request) {
        String artifactRoot = request == null || request.artifactRoot() == null
                || request.artifactRoot().isBlank()
                ? "data/rag" : request.artifactRoot();
        int maxDocuments = request == null || request.maxDocuments() == null
                ? 0 : request.maxDocuments();
        return ResultUtils.success(tableJsonlMaterializationService.backfill(
                Path.of(artifactRoot).toAbsolutePath().normalize(), maxDocuments));
    }
}
