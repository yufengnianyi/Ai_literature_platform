package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.evidence.table.TableSerializer;
import com.example.demo_01.ai.evidence.table.TeiTableParser;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.*;
import com.example.demo_01.ai.patent.demo.PatentTableVisionClient.TableRead;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PatentTableRecoveryServiceTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsIndependentReadingsWithDifferentRatioValueOrTime() {
        TableRead original = read("A:B 2:1", "0.123", "130");
        assertThat(PatentTableRecoveryService.compare(original, read("A:B 1:2", "0.123", "130"))).isNotEmpty();
        assertThat(PatentTableRecoveryService.compare(original, read("A:B 2:1", "0.128", "130"))).isNotEmpty();
        assertThat(PatentTableRecoveryService.compare(original, read("A:B 2:1", "0.123", "138"))).isNotEmpty();
        assertThat(PatentTableRecoveryService.compare(original, original)).isEmpty();
        TableRead uncertain = new TableRead(original.caption(), original.headers(), original.rows(), List.of("blurred digit"));
        assertThat(PatentTableRecoveryService.compare(original, uncertain)).contains("blurred digit");
        TableRead ratioColumn = new TableRead("Table", List.of("Treatment", "Ratio", "EC50"),
                List.of(List.of("A:B", "2:1", ".1")), List.of());
        TableRead wrongRatio = new TableRead("Table", ratioColumn.headers(),
                List.of(List.of("A:B", "1:2", ".1")), List.of());
        assertThat(PatentTableRecoveryService.compare(ratioColumn, wrongRatio)).isNotEmpty();
    }

    @Test
    void findsImageFromCaptionAndReusesAuditableIndependentReadCache() throws Exception {
        Path pdf = pdf();
        PatentTableVisionClient vision = mock(PatentTableVisionClient.class);
        when(vision.model()).thenReturn("test-vision");
        TableRead reading = read("A:B 2:1", "0.123", "130");
        when(vision.read(any(), any(), any())).thenAnswer(invocation -> {
            Path response = invocation.getArgument(1);
            Files.writeString(response, mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "message", Map.of("content", mapper.writeValueAsString(reading)), "finish_reason", "stop")))));
            return reading;
        });
        when(vision.parse(any())).thenReturn(reading);
        var service = new PatentTableRecoveryService(vision, mapper, new TableSerializer(), mock(TeiTableParser.class));
        var candidate = new PatentCandidate("TEST", "Activity", "category", pdf);
        Path output = temp.resolve("runs/run-one/TEST");
        service.recover(candidate, triage(), output, "source-hash");
        assertThat(Files.readString(output.resolve("tables.jsonl"))).contains("0.123", "130");
        var audit = mapper.readTree(output.resolve("patent-table-manifest.json").toFile());
        assertThat(audit.path("complete").asBoolean()).isTrue();
        assertThat(audit.at("/tables/0/pageNumber").asInt()).isEqualTo(1);
        assertThat(audit.at("/tables/0/readStatus").asText()).isEqualTo("VERIFIED");
        assertThat(Files.isRegularFile(Path.of(audit.at("/tables/0/imagePath").asText()))).isTrue();
        Files.writeString(output.resolve("tables.jsonl"), "");
        service.recover(candidate, triage(), output, "source-hash");
        assertThat(Files.readString(output.resolve("tables.jsonl"))).contains("0.123");
        service.recover(candidate, triage(), temp.resolve("runs/run-two/TEST"), "source-hash");
        verify(vision, times(2)).read(any(), any(), any());
    }

    @Test
    void failedReadIsRecordedAndCannotProceedAsACompleteTable() throws Exception {
        var vision = mock(PatentTableVisionClient.class);
        when(vision.model()).thenReturn("test-vision");
        when(vision.read(any(), any(), any())).thenThrow(new IllegalStateException("No vision credentials"));
        var service = new PatentTableRecoveryService(vision, mapper, new TableSerializer(), mock(TeiTableParser.class));
        var candidate = new PatentCandidate("TEST", "Activity", "category", pdf());
        Path output = temp.resolve("failed/run/TEST");
        assertThatThrownBy(() -> service.recover(candidate, triage(), output, "hash"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        var audit = mapper.readTree(output.resolve("patent-table-manifest.json").toFile());
        assertThat(audit.path("complete").asBoolean()).isFalse();
        assertThat(audit.at("/tables/0/readStatus").asText()).isEqualTo("FAILED");
        assertThat(Files.readString(output.resolve("tables.jsonl"))).isEmpty();
    }

    private TableRead read(String name, String ec50, String ctc) {
        return new TableRead("Table 1 EC50 assay", List.of("Treatment", "72h EC50 (mg/L)", "CTC"),
                List.of(List.of(name, ec50, ctc)), List.of());
    }

    private PatentTriageResult triage() {
        var page = new PatentPageAssessment(1, 20, Set.of(PatentPageRole.ACTIVITY_TABLE), true,
                "image-table", 80, "Table 1 EC50 assay");
        return new PatentTriageResult(List.of(page), List.of(page));
    }

    private Path pdf() throws Exception {
        Path path = temp.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("Table 1 EC50 assay");
                stream.endText();
                stream.drawImage(LosslessFactory.createFromImage(document,
                        new BufferedImage(400, 160, BufferedImage.TYPE_INT_RGB)), 40, 490, 450, 180);
            }
            document.save(path.toFile());
        }
        return path;
    }
}
