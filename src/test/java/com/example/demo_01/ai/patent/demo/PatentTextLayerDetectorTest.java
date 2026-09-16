package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageText;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTextLayerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatentTextLayerDetectorTest {

    @Mock
    private PatentPdfTextService textService;

    @TempDir
    private Path tempDir;

    @Test
    void acceptsUsableTextLayer() throws IOException {
        Path pdf = file("text.pdf", "pdf");
        PatentTextLayerDetector detector = detector(500, 1_000_000);
        when(textService.pageCount(pdf)).thenReturn(10);
        when(textService.extractPages(eq(pdf), anyCollection()))
                .thenReturn(pages(10, normalText()));

        var report = detector.inspect(pdf);

        assertThat(report.status()).isEqualTo(PatentTextLayerStatus.TEXT_LAYER_USABLE);
        assertThat(report.usablePageRatio()).isEqualTo(1.0d);
    }

    @Test
    void marksEmptyTextAsScanned() throws IOException {
        Path pdf = file("scan.pdf", "pdf");
        PatentTextLayerDetector detector = detector(500, 1_000_000);
        when(textService.pageCount(pdf)).thenReturn(10);
        when(textService.extractPages(eq(pdf), anyCollection()))
                .thenReturn(pages(10, ""));

        var report = detector.inspect(pdf);

        assertThat(report.status()).isEqualTo(PatentTextLayerStatus.SCANNED_NEEDS_OCR);
    }

    @Test
    void marksPartialTextAsMixedQuality() throws IOException {
        Path pdf = file("mixed.pdf", "pdf");
        PatentTextLayerDetector detector = detector(500, 1_000_000);
        when(textService.pageCount(pdf)).thenReturn(10);
        when(textService.extractPages(eq(pdf), anyCollection())).thenReturn(List.of(
                new PatentPageText(1, normalText()),
                new PatentPageText(2, normalText()),
                new PatentPageText(3, normalText()),
                new PatentPageText(4, "short"),
                new PatentPageText(5, "short"),
                new PatentPageText(6, "short"),
                new PatentPageText(7, "short"),
                new PatentPageText(8, "short"),
                new PatentPageText(9, "short"),
                new PatentPageText(10, "short")
        ));

        var report = detector.inspect(pdf);

        assertThat(report.status()).isEqualTo(PatentTextLayerStatus.MIXED_OR_LOW_QUALITY);
    }

    @Test
    void marksHighReplacementRatioAsGarbled() throws IOException {
        Path pdf = file("garbled.pdf", "pdf");
        PatentTextLayerDetector detector = detector(500, 1_000_000);
        when(textService.pageCount(pdf)).thenReturn(10);
        when(textService.extractPages(eq(pdf), anyCollection())).thenReturn(List.of(
                new PatentPageText(1, normalText()),
                new PatentPageText(2, noisyText()),
                new PatentPageText(3, noisyText()),
                new PatentPageText(4, noisyText()),
                new PatentPageText(5, noisyText())
        ));

        var report = detector.inspect(pdf);

        assertThat(report.status()).isEqualTo(PatentTextLayerStatus.TEXT_LAYER_GARBLED);
    }

    @Test
    void skipsOversizedPdfBeforeParsing() throws IOException {
        Path pdf = file("large.pdf", "too-large");
        PatentTextLayerDetector detector = detector(500, 1);

        var report = detector.inspect(pdf);

        assertThat(report.status()).isEqualTo(PatentTextLayerStatus.OVERSIZED_SKIPPED);
        verify(textService, never()).pageCount(pdf);
    }

    private PatentTextLayerDetector detector(int maxPages, long maxBytes) {
        return new PatentTextLayerDetector(textService, maxPages, maxBytes, 0.60d, 1_200, 0.03d);
    }

    private Path file(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private List<PatentPageText> pages(int count, String text) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(page -> new PatentPageText(page, text))
                .toList();
    }

    private String normalText() {
        return "Compound 49 showed fungicidal control activity against Phytophthora infestans. ".repeat(10);
    }

    private String noisyText() {
        return ("\uFFFD".repeat(80) + " readable compound activity ").repeat(10);
    }
}
