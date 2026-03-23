package com.example.demo_01.ai.rag.api;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagBatchAcceptedResponse;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagFolderBatchRequest;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionBatchRecord;
import com.example.demo_01.ai.rag.service.RagBatchIngestionService;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/rag/batches")
public class RagBatchController {

    @Resource
    private RagBatchIngestionService ragBatchIngestionService;

    @PostMapping("/folder")
    public ResponseEntity<RagBatchAcceptedResponse> ingestFolder(@RequestBody RagFolderBatchRequest request) {
        return ResponseEntity.accepted().body(ragBatchIngestionService.ingestFolder(request.folderPath()));
    }

    @GetMapping("/{batchId}")
    public RagIngestionBatchRecord getBatch(@PathVariable UUID batchId) {
        return ragBatchIngestionService.getBatch(batchId);
    }
}
