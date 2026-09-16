package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageText;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTextLayerStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.TextLayerReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PatentTextLayerDetector {

    private static final int DEFAULT_MAX_PAGES = 500;
    private static final long DEFAULT_MAX_FILE_BYTES = 100L * 1024L * 1024L;
    private static final double DEFAULT_MIN_USABLE_PAGE_RATIO = 0.60d;
    private static final int DEFAULT_MIN_SAMPLE_TEXT_CHARS = 1_200;
    private static final double DEFAULT_MAX_REPLACEMENT_RATIO = 0.03d;

    private final PatentPdfTextService textService;
    private final int maxPages;
    private final long maxFileBytes;
    private final double minUsablePageRatio;
    private final int minSampleTextChars;
    private final double maxReplacementRatio;

    @Autowired
    public PatentTextLayerDetector(PatentPdfTextService textService) {
        this(textService, DEFAULT_MAX_PAGES, DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MIN_USABLE_PAGE_RATIO, DEFAULT_MIN_SAMPLE_TEXT_CHARS,
                DEFAULT_MAX_REPLACEMENT_RATIO);
    }

    PatentTextLayerDetector(PatentPdfTextService textService,
                            int maxPages,
                            long maxFileBytes,
                            double minUsablePageRatio,
                            int minSampleTextChars,
                            double maxReplacementRatio) {
        this.textService = textService;
        this.maxPages = maxPages;
        this.maxFileBytes = maxFileBytes;
        this.minUsablePageRatio = minUsablePageRatio;
        this.minSampleTextChars = minSampleTextChars;
        this.maxReplacementRatio = maxReplacementRatio;
    }

    public TextLayerReport inspect(Path pdfPath) {
        try {
            long fileSizeBytes = Files.size(pdfPath);
            if (fileSizeBytes > maxFileBytes) {
                return new TextLayerReport(PatentTextLayerStatus.OVERSIZED_SKIPPED,
                        0, fileSizeBytes, 0, 0.0d, 0.0d, "PDF file is over demo size limit.");
            }
            int pageCount = textService.pageCount(pdfPath);
            if (pageCount > maxPages) {
                return new TextLayerReport(PatentTextLayerStatus.OVERSIZED_SKIPPED,
                        pageCount, fileSizeBytes, 0, 0.0d, 0.0d, "PDF page count is over demo limit.");
            }
            List<PatentPageText> pages = textService.extractPages(pdfPath, samplePages(pageCount));
            return classify(pageCount, fileSizeBytes, pages);
        } catch (IOException | RuntimeException e) {
            return new TextLayerReport(PatentTextLayerStatus.PDF_ERROR,
                    0, 0L, 0, 0.0d, 0.0d, rootMessage(e));
        }
    }

    private TextLayerReport classify(int pageCount, long fileSizeBytes, List<PatentPageText> pages) {
        int sampledTextChars = 0;
        int usablePages = 0;
        int replacementChars = 0;
        int visibleChars = 0;
        for (PatentPageText page : pages) {
            PageTextMetrics metrics = metrics(page.text());
            sampledTextChars += metrics.visibleChars();
            replacementChars += metrics.replacementChars();
            visibleChars += metrics.visibleChars();
            if (isUsable(metrics)) {
                usablePages++;
            }
        }
        double usableRatio = pages.isEmpty() ? 0.0d : (double) usablePages / pages.size();
        double replacementRatio = visibleChars == 0 ? 0.0d : (double) replacementChars / visibleChars;
        PatentTextLayerStatus status = status(sampledTextChars, usableRatio, replacementRatio);
        return new TextLayerReport(status, pageCount, fileSizeBytes, sampledTextChars,
                usableRatio, replacementRatio, "");
    }

    private PatentTextLayerStatus status(int sampledTextChars,
                                         double usableRatio,
                                         double replacementRatio) {
        if (sampledTextChars < 1_000 || usableRatio < 0.10d) {
            return PatentTextLayerStatus.SCANNED_NEEDS_OCR;
        }
        if (replacementRatio > maxReplacementRatio) {
            return PatentTextLayerStatus.TEXT_LAYER_GARBLED;
        }
        if (usableRatio >= minUsablePageRatio && sampledTextChars >= minSampleTextChars) {
            return PatentTextLayerStatus.TEXT_LAYER_USABLE;
        }
        return PatentTextLayerStatus.MIXED_OR_LOW_QUALITY;
    }

    private boolean isUsable(PageTextMetrics metrics) {
        return metrics.visibleChars() >= 80
                && metrics.replacementRatio() <= maxReplacementRatio
                && metrics.signalRatio() >= 0.45d;
    }

    private PageTextMetrics metrics(String text) {
        if (text == null || text.isBlank()) {
            return new PageTextMetrics(0, 0, 0);
        }
        int visible = 0;
        int replacement = 0;
        int signal = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            visible++;
            if (codePoint == 0xFFFD) {
                replacement++;
            }
            if (Character.isLetterOrDigit(codePoint) || isCjk(codePoint)) {
                signal++;
            }
        }
        return new PageTextMetrics(visible, replacement, signal);
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private Set<Integer> samplePages(int pageCount) {
        Set<Integer> pages = new LinkedHashSet<>();
        for (int page = 1; page <= Math.min(5, pageCount); page++) {
            pages.add(page);
        }
        addIfValid(pages, pageCount, 8);
        addIfValid(pages, pageCount, Math.max(1, pageCount / 4));
        addIfValid(pages, pageCount, Math.max(1, pageCount / 2));
        addIfValid(pages, pageCount, Math.max(1, pageCount * 3 / 4));
        addIfValid(pages, pageCount, pageCount - 2);
        addIfValid(pages, pageCount, pageCount - 1);
        addIfValid(pages, pageCount, pageCount);
        return pages;
    }

    private void addIfValid(Set<Integer> pages, int pageCount, int page) {
        if (page >= 1 && page <= pageCount) {
            pages.add(page);
        }
    }

    private String rootMessage(Exception e) {
        Throwable cursor = e;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record PageTextMetrics(int visibleChars, int replacementChars, int signalChars) {
        double replacementRatio() {
            return visibleChars == 0 ? 0.0d : (double) replacementChars / visibleChars;
        }

        double signalRatio() {
            return visibleChars == 0 ? 0.0d : (double) signalChars / visibleChars;
        }
    }
}
