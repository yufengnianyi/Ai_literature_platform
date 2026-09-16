package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactLoader;
import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactManifestWriter;
import com.example.demo_01.ai.rag.artifact.JsonlArtifactWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatentStructuredArtifactServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void writesChunksThatExistingArtifactLoaderCanRead() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AiPersistenceProperties properties = new AiPersistenceProperties();
        properties.getRag().setStorageRoot(tempDir.toString());
        JsonlArtifactWriter jsonlWriter = new JsonlArtifactWriter();
        ReflectionTestUtils.setField(jsonlWriter, "objectMapper", objectMapper);
        PreprocessArtifactManifestWriter manifestWriter = new PreprocessArtifactManifestWriter();
        ReflectionTestUtils.setField(manifestWriter, "objectMapper", objectMapper);
        PatentStructuredArtifactService service = new PatentStructuredArtifactService(
                properties, jsonlWriter, manifestWriter, objectMapper,
                org.mockito.Mockito.mock(PatentTableRecoveryService.class));

        Path pdf = tempDir.resolve("AU-DEMO.pdf");
        Files.writeString(pdf, "pdf bytes");
        UUID runId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        PatentCandidate candidate = new PatentCandidate(
                "AU-DEMO", "Fungicidal demo compounds", "category", pdf);
        PatentPageAssessment page = new PatentPageAssessment(
                7,
                20,
                Set.of(PatentPageRole.ACTIVITY_TABLE),
                true,
                "activity-table",
                120,
                "Cmpd No. Test A Test D\n49 99 100\n");
        PatentTriageResult triage = new PatentTriageResult(List.of(page), List.of(page));

        var result = service.write(runId, documentId, candidate, triage);

        PreprocessArtifactLoader loader = new PreprocessArtifactLoader();
        ReflectionTestUtils.setField(loader, "objectMapper", objectMapper);
        var chunks = loader.loadChunks(result.storageDir());

        assertThat(result.chunkCount()).isEqualTo(1);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionPath()).isEqualTo("patent/result/activity-table");
        assertThat(chunks.get(0).sourceTei()).isEmpty();
        assertThat(chunks.get(0).text()).contains("[publication_number=AU-DEMO][patent_page=7]");
        assertThat(Files.exists(result.storageDir().resolve("artifact-manifest.json"))).isTrue();
        assertThat(Files.exists(result.storageDir().resolve("patent-demo-pages.jsonl"))).isTrue();
        assertThat(Files.exists(result.storageDir().resolve("patent-page-index.jsonl"))).isTrue();
        assertThat(Files.exists(result.storageDir().resolve("patent-subject.json"))).isTrue();
        assertThat(Files.exists(result.storageDir().resolve("patent-table-candidates.jsonl"))).isTrue();
        assertThat(Files.exists(result.storageDir().resolve("patent-evidence-bundles.jsonl"))).isTrue();
        assertThat(Files.exists(result.storageDir().resolve("patent-discovery-audit.json"))).isTrue();
        assertThat(Files.exists(result.storageDir().resolve("patent-stage-metrics.jsonl"))).isTrue();
        assertThat(result.discoveryOutcome()).isEqualTo(PatentDemoModels.PatentDiscoveryOutcome.EVIDENCE_FOUND);
    }
}
