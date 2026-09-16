package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.evidence.table.ParsedTable;
import com.example.demo_01.ai.evidence.table.TableSerializer;
import com.example.demo_01.ai.evidence.table.TeiTableParser;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import com.example.demo_01.ai.patent.demo.PatentTableVisionClient.TableRead;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PatentTableRecoveryService {
    private static final Pattern CAPTION = Pattern.compile("(?:\\btable\\s*\\d+|\u8868\\s*\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY = Pattern.compile("EC\\s*50|IC\\s*50|CTC|Test\\s+[A-H]|\u6bd2\u529b|\u9632\u6548|\u6291\u5236", Pattern.CASE_INSENSITIVE);
    private static final Pattern TREATMENT_HEADER = Pattern.compile(
            "ratio|treatment|compound|name|\u914d\u6bd4|\u6bd4\u4f8b|\u836f\u5242|\u5904\u7406|\u540d\u79f0", Pattern.CASE_INSENSITIVE);
    private final PatentTableVisionClient vision;
    private final ObjectMapper mapper;
    private final TableSerializer serializer;
    private final TeiTableParser teiParser;

    public PatentTableRecoveryService(PatentTableVisionClient vision, ObjectMapper mapper,
                                      TableSerializer serializer, TeiTableParser teiParser) {
        this.vision = vision;
        this.mapper = mapper;
        this.serializer = serializer;
        this.teiParser = teiParser;
    }

    public void recover(PatentCandidate candidate, PatentTriageResult triage, Path storageDir,
                        String pdfSha256) throws IOException {
        recover(candidate, triage, storageDir, pdfSha256, List.of(), true);
    }

    public void recover(PatentCandidate candidate, PatentTriageResult triage, Path storageDir,
                        String pdfSha256, List<PatentTableCandidate> candidates) throws IOException {
        recover(candidate, triage, storageDir, pdfSha256, candidates, false);
    }

    private void recover(PatentCandidate candidate, PatentTriageResult triage, Path storageDir,
                         String pdfSha256, List<PatentTableCandidate> candidates,
                         boolean failOnIncomplete) throws IOException {
        Files.createDirectories(storageDir.resolve("tables"));
        List<ParsedTable> tables = new ArrayList<>();
        List<Map<String, Object>> audits = new ArrayList<>();
        Path tei = candidate.pdfPath().resolveSibling(candidate.publicationNumber() + ".tei.xml");
        if (Files.isRegularFile(tei)) {
            for (ParsedTable table : teiParser.parseAll(Files.readString(tei, StandardCharsets.UTF_8))) {
                if (table.structured() && !table.rows().isEmpty() && ACTIVITY.matcher(table.markdown()).find()) {
                    tables.add(table);
                    audits.add(new LinkedHashMap<>(Map.of("tableRef", table.tableRef(), "sourceKind", "TEI",
                            "readStatus", "VERIFIED", "caption", table.caption(), "sourcePath", tei.toString())));
                }
            }
        }
        boolean incomplete = false;
        Map<Integer, List<PatentTableCandidate>> selectedCandidates = (candidates == null ? List.<PatentTableCandidate>of() : candidates)
                .stream()
                .filter(PatentTableCandidate::selectedForRead)
                .collect(Collectors.groupingBy(PatentTableCandidate::pageNumber, LinkedHashMap::new, Collectors.toList()));
        try (PDDocument pdf = Loader.loadPDF(candidate.pdfPath().toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (var selected : triage.selectedPages()) {
                int pageIndex = selected.pageNumber() - 1;
                PageText layout = new PageText();
                layout.setStartPage(pageIndex + 1);
                layout.setEndPage(pageIndex + 1);
                layout.getText(pdf);
                List<Line> lines = layout.lines();
                List<CaptionCandidate> captions = captionCandidates(lines,
                        selectedCandidates.getOrDefault(selected.pageNumber(), List.of()));
                Images images = new Images(pdf.getPage(pageIndex));
                images.processPage(pdf.getPage(pageIndex));
                for (CaptionCandidate captionCandidate : captions) {
                    Line caption = captionCandidate.line();
                    // References in prose are not standalone captions.
                    String stripped = caption.text.replaceFirst("^\\s*\\[\\d+\\]\\s*", "").strip();
                    if (!CAPTION.matcher(stripped).lookingAt()) {
                        continue;
                    }
                    if (tables.stream().anyMatch(t -> normalize(t.caption()).equals(normalize(stripped)))) {
                        continue;
                    }
                    String ref = "T" + (audits.size() + 1);
                    ParsedTable textTable = textTable(ref, stripped, caption, lines);
                    if (textTable != null) {
                        tables.add(textTable);
                        audits.add(new LinkedHashMap<>(Map.of("tableRef", ref, "pageNumber", pageIndex + 1,
                                "sourceKind", "TEXT", "readStatus", "VERIFIED", "caption", stripped,
                                "sourcePath", candidate.pdfPath().toString())));
                        continue;
                    }
                    Region region = images.regions.stream()
                            .filter(r -> r.width > 120 && r.height > 35 && r.y >= caption.y - 5 && r.y - caption.y < 100)
                            .min(Comparator.comparingDouble(r -> r.y)).orElse(null);
                    Map<String, Object> audit = new LinkedHashMap<>();
                    audit.put("tableRef", ref);
                    audit.put("pageNumber", pageIndex + 1);
                    audit.put("caption", stripped);
                    audit.put("sourceKind", "VISION");
                    audit.put("model", vision.model());
                    audit.put("promptVersion", PatentTableVisionClient.PROMPT_VERSION);
                    audits.add(audit);
                    if (region == null) {
                        audit.put("readStatus", "FAILED");
                        audit.put("reason", "Table caption found but no recoverable text rows or image region");
                        incomplete = true;
                        continue;
                    }
                    float pageWidth = pdf.getPage(pageIndex).getCropBox().getWidth();
                    Region crop = new Region(Math.max(0, Math.min(region.x, caption.x) - 5),
                            Math.max(0, caption.y - 16), 0, 0);
                    crop = new Region(crop.x, crop.y,
                            Math.min(pageWidth, Math.max(region.x + region.width, caption.right) + 5) - crop.x,
                            region.y + region.height + 6 - crop.y);
                    audit.put("region", crop);
                    Path image = storageDir.resolve("tables").resolve(ref + ".png");
                    Path verificationImage = storageDir.resolve("tables").resolve(ref + "-verification.png");
                    audit.put("imagePath", image.toString());
                    audit.put("verificationImagePath", verificationImage.toString());
                    try {
                        render(renderer, pageIndex, crop, 300, image);
                        // Keep the native raster for audit; both independent requests need readable input.
                        BufferedImage nativeImage = images.sources.get(region).getImage();
                        Path nativePath = storageDir.resolve("tables").resolve(ref + "-native.png");
                        ImageIO.write(nativeImage, "png", nativePath.toFile());
                        render(renderer, pageIndex, crop, 300, verificationImage);
                        audit.put("verificationSource", "INDEPENDENT_REQUEST_SAME_300_DPI_REGION");
                        audit.put("nativeImagePath", nativePath.toString());
                        audit.put("nativeImageWidth", nativeImage.getWidth());
                        audit.put("nativeImageHeight", nativeImage.getHeight());
                        String cacheKey = hash((pdfSha256 + "|" + mapper.writeValueAsString(crop) + "|"
                                + pageIndex + "|" + vision.model() + "|" + PatentTableVisionClient.PROMPT_VERSION
                                + "|render-300-twice/native-audit|" + selected.text()).getBytes(StandardCharsets.UTF_8));
                        Path cache = storageDir.getParent().getParent().resolve("table-cache").resolve(cacheKey);
                        Files.createDirectories(cache);
                        Path firstFile = storageDir.resolve("tables").resolve(ref + "-read-1.json");
                        Path secondFile = storageDir.resolve("tables").resolve(ref + "-read-2.json");
                        TableRead first = readCached(image, firstFile, cache.resolve("read-1.json"), selected.text());
                        TableRead second = readCached(verificationImage, secondFile, cache.resolve("read-2.json"), selected.text());
                        List<String> differences = compare(first, second);
                        audit.put("cacheKey", cacheKey);
                        audit.put("firstRead", first);
                        audit.put("secondRead", second);
                        audit.put("differences", differences);
                        audit.put("readStatus", differences.isEmpty() ? "VERIFIED" : "REVIEW_REQUIRED");
                        if (differences.isEmpty()) {
                            String markdown = serializer.render(stripped, first.headers(), first.rows(), List.of(), true);
                            tables.add(new ParsedTable(ref, CAPTION.matcher(stripped).results().findFirst()
                                    .map(match -> match.group()).orElse(ref), stripped, first.headers(), first.rows(),
                                    List.of(), markdown, true, ""));
                        } else {
                            incomplete = true;
                        }
                    } catch (Exception e) {
                        audit.put("readStatus", "FAILED");
                        audit.put("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        incomplete = true;
                    }
                }
                Set<String> captionCandidateIds = captions.stream()
                        .map(CaptionCandidate::candidateId)
                        .filter(id -> id != null && !id.isBlank())
                        .collect(Collectors.toSet());
                for (PatentTableCandidate candidateRegion : selectedCandidates.getOrDefault(selected.pageNumber(), List.of())) {
                    if (candidateRegion.region() == null || captionCandidateIds.contains(candidateRegion.candidateId())) {
                        continue;
                    }
                    if (!tables.isEmpty()) {
                        continue;
                    }
                    String ref = "T" + (audits.size() + 1);
                    Map<String, Object> audit = new LinkedHashMap<>();
                    audit.put("tableRef", ref);
                    audit.put("candidateId", candidateRegion.candidateId());
                    audit.put("pageNumber", pageIndex + 1);
                    audit.put("sourceKind", "VISION");
                    audit.put("candidateSource", candidateRegion.source().name());
                    audit.put("model", vision.model());
                    audit.put("promptVersion", PatentTableVisionClient.PROMPT_VERSION);
                    audits.add(audit);
                    Region crop = new Region(candidateRegion.region().x(), candidateRegion.region().y(),
                            candidateRegion.region().width(), candidateRegion.region().height());
                    audit.put("region", crop);
                    String caption = candidateRegion.caption() == null || candidateRegion.caption().isBlank()
                            ? "Patent table candidate " + candidateRegion.candidateId() : candidateRegion.caption();
                    incomplete = !readVisionTable(candidate, storageDir, pdfSha256, renderer, pageIndex,
                            selected.text(), tables, audit, ref, caption, crop, images, null) || incomplete;
                }
            }
        }
        StringBuilder jsonl = new StringBuilder();
        for (ParsedTable table : tables) {
            jsonl.append(mapper.writeValueAsString(table)).append('\n');
        }
        Files.writeString(storageDir.resolve("tables.jsonl"), jsonl, StandardCharsets.UTF_8);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("kind", "PATENT_Q1");
        manifest.put("publicationNumber", candidate.publicationNumber());
        manifest.put("pdfSha256", pdfSha256);
        manifest.put("complete", !incomplete);
        manifest.put("tables", audits);
        mapper.writerWithDefaultPrettyPrinter().writeValue(storageDir.resolve("patent-table-manifest.json").toFile(), manifest);
        if (incomplete && failOnIncomplete) {
            throw new IllegalStateException("Patent table recovery incomplete; inspect " + storageDir.resolve("patent-table-manifest.json"));
        }
    }

    private List<CaptionCandidate> captionCandidates(List<Line> lines, List<PatentTableCandidate> candidates) {
        Map<String, PatentTableCandidate> byCaption = candidates.stream()
                .filter(candidate -> candidate.caption() != null && !candidate.caption().isBlank())
                .collect(Collectors.toMap(candidate -> normalize(candidate.caption()), candidate -> candidate,
                        (left, right) -> left, LinkedHashMap::new));
        List<CaptionCandidate> result = new ArrayList<>();
        for (Line line : lines) {
            String normalized = normalize(line.text);
            PatentTableCandidate matched = byCaption.get(normalized);
            if (matched == null) {
                matched = byCaption.entrySet().stream()
                        .filter(entry -> normalized.contains(entry.getKey())
                                || entry.getKey().contains(normalized))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
            boolean legacy = CAPTION.matcher(line.text).find() && ACTIVITY.matcher(line.text).find();
            if (matched != null || legacy) {
                result.add(new CaptionCandidate(line, matched == null ? null : matched.candidateId()));
            }
        }
        return result;
    }

    private boolean readVisionTable(PatentCandidate candidate,
                                    Path storageDir,
                                    String pdfSha256,
                                    PDFRenderer renderer,
                                    int pageIndex,
                                    String sourceContext,
                                    List<ParsedTable> tables,
                                    Map<String, Object> audit,
                                    String ref,
                                    String caption,
                                    Region crop,
                                    Images images,
                                    Region nativeRegion) {
        Path image = storageDir.resolve("tables").resolve(ref + ".png");
        Path verificationImage = storageDir.resolve("tables").resolve(ref + "-verification.png");
        audit.put("caption", caption);
        audit.put("imagePath", image.toString());
        audit.put("verificationImagePath", verificationImage.toString());
        try {
            render(renderer, pageIndex, crop, 300, image);
            PDImage nativeSource = nativeRegion == null ? null : images.sources.get(nativeRegion);
            if (nativeSource != null) {
                BufferedImage nativeImage = nativeSource.getImage();
                Path nativePath = storageDir.resolve("tables").resolve(ref + "-native.png");
                ImageIO.write(nativeImage, "png", nativePath.toFile());
                audit.put("nativeImagePath", nativePath.toString());
                audit.put("nativeImageWidth", nativeImage.getWidth());
                audit.put("nativeImageHeight", nativeImage.getHeight());
            }
            render(renderer, pageIndex, crop, 300, verificationImage);
            audit.put("verificationSource", "INDEPENDENT_REQUEST_SAME_300_DPI_REGION");
            String cacheKey = hash((pdfSha256 + "|" + mapper.writeValueAsString(crop) + "|"
                    + pageIndex + "|" + vision.model() + "|" + PatentTableVisionClient.PROMPT_VERSION
                    + "|render-300-twice/native-audit|" + sourceContext).getBytes(StandardCharsets.UTF_8));
            Path cache = storageDir.getParent().getParent().resolve("table-cache").resolve(cacheKey);
            Files.createDirectories(cache);
            Path firstFile = storageDir.resolve("tables").resolve(ref + "-read-1.json");
            Path secondFile = storageDir.resolve("tables").resolve(ref + "-read-2.json");
            TableRead first = readCached(image, firstFile, cache.resolve("read-1.json"), sourceContext);
            TableRead second = readCached(verificationImage, secondFile, cache.resolve("read-2.json"), sourceContext);
            List<String> differences = compare(first, second);
            audit.put("cacheKey", cacheKey);
            audit.put("firstRead", first);
            audit.put("secondRead", second);
            audit.put("differences", differences);
            audit.put("readStatus", differences.isEmpty() ? "VERIFIED" : "REVIEW_REQUIRED");
            if (differences.isEmpty()) {
                String markdown = serializer.render(caption, first.headers(), first.rows(), List.of(), true);
                tables.add(new ParsedTable(ref, CAPTION.matcher(caption).results().findFirst()
                        .map(match -> match.group()).orElse(ref), caption, first.headers(), first.rows(),
                        List.of(), markdown, true, ""));
                return true;
            }
            return false;
        } catch (Exception e) {
            audit.put("readStatus", "FAILED");
            audit.put("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private TableRead readCached(Path image, Path response, Path cache, String sourceContext) throws IOException {
        if (Files.isRegularFile(cache)) {
            try {
                var raw = mapper.readTree(Files.readString(cache));
                TableRead result = vision.parse(raw.at("/choices/0/message/content").asText());
                Files.copy(cache, response, StandardCopyOption.REPLACE_EXISTING);
                return result;
            } catch (IOException | IllegalArgumentException ignored) {
                // A partial response is not a successful cached transcription.
            }
        }
        TableRead result = vision.read(image, response, sourceContext);
        Files.copy(response, cache, StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    static List<String> compare(TableRead first, TableRead second) {
        List<String> differences = new ArrayList<>();
        differences.addAll(first.uncertainties());
        differences.addAll(second.uncertainties());
        if (first.headers().size() != second.headers().size() || first.rows().size() != second.rows().size()) {
            differences.add("Table dimensions differ between independent readings");
            return differences;
        }
        for (int c = 0; c < first.headers().size(); c++) {
            if (!normalize(first.headers().get(c)).equals(normalize(second.headers().get(c)))) {
                differences.add("Header mismatch at column " + (c + 1));
            }
            // Regression equations are retained, but treatment, EC50 and CTC are the critical consensus fields.
            if (c != 0 && !ACTIVITY.matcher(normalize(first.headers().get(c))).find()
                    && !TREATMENT_HEADER.matcher(normalize(first.headers().get(c))).find()) {
                continue;
            }
            for (int r = 0; r < first.rows().size(); r++) {
                String left = first.rows().get(r).get(c);
                String right = second.rows().get(r).get(c);
                if (left.isBlank() || !normalize(left).equals(normalize(right))) {
                    differences.add("Cell mismatch or missing value at row " + (r + 1) + ", column " + (c + 1));
                }
            }
        }
        return List.copyOf(differences);
    }

    private ParsedTable textTable(String ref, String caption, Line captionLine, List<Line> lines) {
        List<Line> below = lines.stream().filter(line -> line.y > captionLine.y + 2).limit(60).toList();
        int header = -1;
        for (int i = 0; i < Math.min(4, below.size()); i++) {
            if (ACTIVITY.matcher(below.get(i).text).find() && below.get(i).text.contains("|")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return null;
        }
        List<String> headers = pipeCells(below.get(header).text);
        List<List<String>> rows = new ArrayList<>();
        for (int i = header + 1; i < below.size(); i++) {
            List<String> cells = pipeCells(below.get(i).text);
            if (cells.size() != headers.size()) {
                break;
            }
            if (!below.get(i).text.matches("[| :\\-]+")) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        return new ParsedTable(ref, ref, caption, headers, rows, List.of(),
                serializer.render(caption, headers, rows, List.of(), true), true, "");
    }

    private List<String> pipeCells(String text) {
        return java.util.Arrays.stream(text.replaceAll("^\\s*\\||\\|\\s*$", "").split("\\|", -1))
                .map(String::strip).toList();
    }

    private static String normalize(String input) {
        return Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", "").replace('\u2014', '-').replace('\u2013', '-').toLowerCase(java.util.Locale.ROOT);
    }

    private void render(PDFRenderer renderer, int pageIndex, Region region, int dpi, Path target) throws IOException {
        BufferedImage page = renderer.renderImageWithDPI(pageIndex, dpi);
        double scale = dpi / 72.0;
        int x = Math.max(0, (int) Math.floor(region.x * scale));
        int y = Math.max(0, (int) Math.floor(region.y * scale));
        int width = Math.min(page.getWidth() - x, (int) Math.ceil(region.width * scale));
        int height = Math.min(page.getHeight() - y, (int) Math.ceil(region.height * scale));
        if (width < 1 || height < 1) {
            throw new IOException("Table crop is outside the PDF page");
        }
        ImageIO.write(page.getSubimage(x, y, width, height), "png", target.toFile());
    }

    private String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Region(float x, float y, float width, float height) { }
    private record Line(String text, float x, float y, float right) { }
    private record CaptionCandidate(Line line, String candidateId) { }

    private static final class PageText extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();
        private PageText() { setSortByPosition(true); }
        @Override
        protected void writeString(String text, List<TextPosition> values) { positions.addAll(values); }
        private List<Line> lines() {
            List<TextPosition> sorted = positions.stream().sorted(Comparator.comparing(TextPosition::getYDirAdj)
                    .thenComparing(TextPosition::getXDirAdj)).toList();
            List<List<TextPosition>> groups = new ArrayList<>();
            for (TextPosition position : sorted) {
                if (groups.isEmpty() || Math.abs(groups.getLast().getFirst().getYDirAdj() - position.getYDirAdj()) > 3) {
                    groups.add(new ArrayList<>());
                }
                groups.getLast().add(position);
            }
            List<Line> result = new ArrayList<>();
            for (List<TextPosition> group : groups) {
                group.sort(Comparator.comparing(TextPosition::getXDirAdj));
                StringBuilder text = new StringBuilder();
                float right = group.getFirst().getXDirAdj();
                for (TextPosition p : group) {
                    if (p.getXDirAdj() - right > 2) { text.append(' '); }
                    text.append(p.getUnicode());
                    right = p.getXDirAdj() + p.getWidthDirAdj();
                }
                result.add(new Line(text.toString(), group.getFirst().getXDirAdj(), group.getFirst().getYDirAdj(), right));
            }
            return result;
        }
    }

    private static final class Images extends PDFGraphicsStreamEngine {
        private final List<Region> regions = new ArrayList<>();
        private final Map<Region, PDImage> sources = new LinkedHashMap<>();
        private final GeneralPath path = new GeneralPath();
        private Images(PDPage page) { super(page); }
        @Override
        public void drawImage(PDImage image) {
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            Point2D[] points = {matrix.transformPoint(0, 0), matrix.transformPoint(0, 1),
                    matrix.transformPoint(1, 0), matrix.transformPoint(1, 1)};
            double left = java.util.Arrays.stream(points).mapToDouble(Point2D::getX).min().orElse(0);
            double right = java.util.Arrays.stream(points).mapToDouble(Point2D::getX).max().orElse(0);
            double bottom = java.util.Arrays.stream(points).mapToDouble(Point2D::getY).min().orElse(0);
            double top = java.util.Arrays.stream(points).mapToDouble(Point2D::getY).max().orElse(0);
            Region region = new Region((float) left, getPage().getCropBox().getHeight() - (float) top,
                    (float) (right - left), (float) (top - bottom));
            regions.add(region);
            sources.put(region, image);
        }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
        @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { path.curveTo(x1,y1,x2,y2,x3,y3); }
        @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
        @Override public void closePath() { path.closePath(); }
        @Override public void endPath() { path.reset(); }
        @Override public void strokePath() { path.reset(); }
        @Override public void fillPath(int windingRule) { path.reset(); }
        @Override public void fillAndStrokePath(int windingRule) { path.reset(); }
        @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }
    }
}
