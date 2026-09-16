package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentImageRegion;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageIndexRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.springframework.stereotype.Service;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PatentPageIndexService {

    public List<PatentPageIndexRecord> index(Path pdfPath, List<PatentPageAssessment> pages) {
        Map<Integer, List<PatentImageRegion>> images = imageRegions(pdfPath);
        return pages.stream()
                .sorted(Comparator.comparingInt(PatentPageAssessment::pageNumber))
                .map(page -> {
                    List<PatentImageRegion> regions = images.getOrDefault(page.pageNumber(), List.of());
                    return new PatentPageIndexRecord(
                            page.pageNumber(),
                            page.text() == null || page.text().isBlank()
                                    ? PatentDemoModels.PatentPageReadStatus.EMPTY
                                    : PatentDemoModels.PatentPageReadStatus.READ_OK,
                            null,
                            page.textChars(),
                            regions.size(),
                            regions);
                })
                .toList();
    }

    private Map<Integer, List<PatentImageRegion>> imageRegions(Path pdfPath) {
        Map<Integer, List<PatentImageRegion>> result = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                Images images = new Images(document.getPage(i), i + 1);
                images.processPage(document.getPage(i));
                result.put(i + 1, images.regions());
            }
        } catch (IOException e) {
            return Map.of();
        }
        return result;
    }

    private static final class Images extends PDFGraphicsStreamEngine {
        private final int pageNumber;
        private final List<PatentImageRegion> regions = new ArrayList<>();
        private final GeneralPath path = new GeneralPath();

        private Images(PDPage page, int pageNumber) {
            super(page);
            this.pageNumber = pageNumber;
        }

        private List<PatentImageRegion> regions() {
            return regions.stream()
                    .filter(region -> region.width() > 0 && region.height() > 0)
                    .collect(Collectors.toUnmodifiableList());
        }

        @Override
        public void drawImage(PDImage image) {
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            Point2D[] points = {
                    matrix.transformPoint(0, 0),
                    matrix.transformPoint(0, 1),
                    matrix.transformPoint(1, 0),
                    matrix.transformPoint(1, 1)
            };
            double left = java.util.Arrays.stream(points).mapToDouble(Point2D::getX).min().orElse(0);
            double right = java.util.Arrays.stream(points).mapToDouble(Point2D::getX).max().orElse(0);
            double bottom = java.util.Arrays.stream(points).mapToDouble(Point2D::getY).min().orElse(0);
            double top = java.util.Arrays.stream(points).mapToDouble(Point2D::getY).max().orElse(0);
            regions.add(new PatentImageRegion(
                    pageNumber,
                    (float) left,
                    getPage().getCropBox().getHeight() - (float) top,
                    (float) (right - left),
                    (float) (top - bottom),
                    "PDF_IMAGE"));
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
        @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { path.curveTo(x1, y1, x2, y2, x3, y3); }
        @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
        @Override public void closePath() { path.closePath(); }
        @Override public void endPath() { path.reset(); }
        @Override public void strokePath() { path.reset(); }
        @Override public void fillPath(int windingRule) { path.reset(); }
        @Override public void fillAndStrokePath(int windingRule) { path.reset(); }
        @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }
    }
}
