package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceConfigScope;
import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidationStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.example.demo_01.ai.evidence.table.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MultiProfileEvidenceServicePatentTest {
    @TempDir Path root;

    @Test
    void nativePipelineRetainsInvalidRowsAndLimitsOverridesToPatentVerification() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EvidenceProperties properties = new EvidenceProperties();
        properties.getAgents().getVerifier().setDropInvalidRows(true);
        properties.getAgents().getTelemetry().setEnabled(false);
        EvidenceConfigScope scope = new EvidenceConfigScope();
        ReflectionTestUtils.setField(scope, "global", properties);
        ReflectionTestUtils.setField(scope, "objectMapper", mapper);
        var registry = new EvidenceProfileRegistry();
        var support = new PatentQ1EvidenceSupport(mapper, new TableSerializer());
        var tableContext = new TableContextService();
        ReflectionTestUtils.setField(tableContext, "properties", properties);
        ReflectionTestUtils.setField(tableContext, "patentQ1EvidenceSupport", support);
        ReflectionTestUtils.setField(tableContext, "legendResolver", new TableLegendResolver());
        var telemetry = new EvidenceAgentTelemetryService();
        ReflectionTestUtils.setField(telemetry, "properties", properties);
        var extractor = mock(EvidenceExtractionAgent.class);
        var verifier = mock(EvidenceVerifierAgent.class);
        var retriever = mock(EvidenceRetrievalAgent.class);
        var coverage = mock(EvidenceCoverageAgent.class);
        var reconciler = mock(EvidenceReconcilerAgent.class);
        var service = new MultiProfileEvidenceService();
        ReflectionTestUtils.setField(service, "configScope", scope);
        ReflectionTestUtils.setField(service, "profileRegistry", registry);
        ReflectionTestUtils.setField(service, "patentQ1EvidenceSupport", support);
        ReflectionTestUtils.setField(service, "tableContextService", tableContext);
        ReflectionTestUtils.setField(service, "telemetryService", telemetry);
        ReflectionTestUtils.setField(service, "extractionAgent", extractor);
        ReflectionTestUtils.setField(service, "verifierAgent", verifier);
        ReflectionTestUtils.setField(service, "retrievalAgent", retriever);
        ReflectionTestUtils.setField(service, "coverageAgent", coverage);
        ReflectionTestUtils.setField(service, "reconcilerAgent", reconciler);
        ReflectionTestUtils.setField(service, "objectMapper", mapper);

        mapper.writeValue(root.resolve("patent-table-manifest.json").toFile(), Map.of(
                "kind", "PATENT_Q1", "publicationNumber", "TEST", "pdfSha256", "d".repeat(64),
                "tables", List.of(Map.of("tableRef", "T1", "pageNumber", 4, "sourceKind", "TEXT", "readStatus", "VERIFIED"))));
        Files.writeString(root.resolve("tables.jsonl"), mapper.writeValueAsString(new ParsedTable("T1", "1", "Assay",
                List.of("Treatment", "EC50 mg/L"), List.of(List.of("Agent A", "0.35")), List.of(), "", true, "")));
        UUID run = UUID.randomUUID();
        var document = new SourceDocument(UUID.randomUUID(), "Patent", List.of(), null, null, null, root.toString());
        var method = new EvidenceChunk("doc:page:4", "patent/method", 4, null, null, "Mycelial growth assay.", "body", "");
        List<String> cells = new ArrayList<>(Collections.nCopies(16, ""));
        cells.set(0, "Agent A");
        cells.set(6, "Mycelial growth assay");
        cells.set(7, "EC50=0.35 mg/L");
        var row = new ValidatedEvidenceRow(UUID.randomUUID(), cells, "fingerprint", List.of());
        when(extractor.extract(eq(run), eq(document), eq(registry.require("Q1")), anyList())).thenReturn(List.of(row));
        when(verifier.verify(any(), anyList(), anyList())).thenAnswer(invocation -> {
            assertThat(scope.current().getAgents().getVerifier().isDropInvalidRows()).isFalse();
            return List.of(row.withValidation(ValidationStatus.VALID, "Generic verifier accepted"));
        });
        when(reconciler.reconcile(any(), any(), anyList())).thenAnswer(invocation -> invocation.getArgument(2));

        var result = service.extractQuestion(run, document, List.of(method), "Q1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().recordId()).isEqualTo(row.recordId());
        assertThat(result.getFirst().cells()).isEqualTo(cells);
        assertThat(result.getFirst().validationStatus()).isEqualTo(ValidationStatus.INVALID);
        assertThat(result.getFirst().verificationNote()).contains("Missing required tested oomycete Latin name");
        assertThat(result.getFirst().anchors()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(scope.current().getAgents().getVerifier().isDropInvalidRows()).isTrue();
        verifyNoInteractions(retriever, coverage);
        assertThat(root.resolve("extraction").resolve(run.toString()).resolve("patent-q1-audit.json")).exists();
    }
}
