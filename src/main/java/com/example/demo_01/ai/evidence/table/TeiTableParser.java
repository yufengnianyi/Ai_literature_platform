package com.example.demo_01.ai.evidence.table;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses GROBID TEI tables ({@code <figure type="table">} and standalone {@code <table>})
 * into {@link ParsedTable} units.
 *
 * <p>Handles the recurring pitfalls of scientific tables:
 * <ul>
 *   <li>multi-row headers (merged / spanning column headers) via header-row detection
 *       and left-forward-fill;</li>
 *   <li>{@code @cols} colspan expansion to keep columns aligned;</li>
 *   <li>the "no {@code <row>}" degrade case (image / caption-only tables) &rarr;
 *       {@code structured=false}.</li>
 * </ul>
 * Pure/deterministic: no LLM, no I/O. Safe to unit test in isolation.
 */
@Component
public class TeiTableParser {

    /** A cell that looks like a numeric measurement (e.g. {@code 166.1}, {@code <0.05}, {@code 12 ± 3}). */
    private static final Pattern DATA_VALUE = Pattern.compile(
            "^[<>~≈=]?\\s*\\d[\\d.,]*\\s*(?:[-–±/]\\s*\\d[\\d.,]*)?\\s*%?$");

    @Resource
    private TableSerializer tableSerializer;

    /** Parse every table in the supplied TEI document, assigning refs {@code T1, T2, ...}. */
    public List<ParsedTable> parseAll(String teiXml) {
        Document document = parseXml(teiXml);
        List<ParsedTable> tables = new ArrayList<>();
        NodeList figures = document.getElementsByTagName("*");
        int tableIndex = 0;
        Set<Node> consumed = new LinkedHashSet<>();

        // 1) figures declared as tables
        for (int i = 0; i < figures.getLength(); i++) {
            Node node = figures.item(i);
            if (!(node instanceof Element element) || !"figure".equals(element.getLocalName())) {
                continue;
            }
            String type = element.getAttribute("type");
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("table")) {
                continue;
            }
            tableIndex++;
            tables.add(parseFigure(element, "T" + tableIndex, consumed));
        }

        // 2) standalone <table> not wrapped in a table figure
        for (int i = 0; i < figures.getLength(); i++) {
            Node node = figures.item(i);
            if (!(node instanceof Element element) || !"table".equals(element.getLocalName())) {
                continue;
            }
            if (consumed.contains(element)) {
                continue;
            }
            tableIndex++;
            tables.add(parseStandaloneTable(element, "T" + tableIndex));
        }
        return List.copyOf(tables);
    }

    private ParsedTable parseFigure(Element figure, String tableRef, Set<Node> consumedTables) {
        String label = normalize(textOfFirstDirectChild(figure, "label"));
        String head = normalize(textOfFirstDirectChild(figure, "head"));
        String figDesc = normalize(deepText(firstDescendant(figure, "figDesc")));
        String caption = firstNonBlank(joinNonBlank(head, figDesc), head);

        Element table = firstDescendant(figure, "table");
        List<List<String>> rawRows = table == null ? List.of() : readRows(table);
        if (table != null) {
            consumedTables.add(table);
        }
        List<String> footnotes = readFootnotes(figure);
        return build(tableRef, label, caption, rawRows, footnotes, figure);
    }

    private ParsedTable parseStandaloneTable(Element table, String tableRef) {
        List<List<String>> rawRows = readRows(table);
        return build(tableRef, null, null, rawRows, List.of(), table);
    }

    private ParsedTable build(String tableRef,
                              String label,
                              String caption,
                              List<List<String>> rawRows,
                              List<String> footnotes,
                              Element rawNode) {
        String rawXml = serializeNode(rawNode);
        if (rawRows.isEmpty()) {
            // "no <row>" degrade: caption-only / image table.
            String markdown = tableSerializer.render(caption, List.of(), List.of(), footnotes, false);
            return new ParsedTable(tableRef, label, caption, List.of(), List.of(),
                    footnotes, markdown, false, rawXml);
        }
        int columns = rawRows.stream().mapToInt(List::size).max().orElse(0);
        List<List<String>> padded = pad(rawRows, columns);
        int headerRowCount = detectHeaderRowCount(padded);
        List<String> headers = mergeHeaders(padded.subList(0, headerRowCount), columns);
        List<List<String>> dataRows = padded.subList(headerRowCount, padded.size());
        String markdown = tableSerializer.render(caption, headers, dataRows, footnotes, true);
        return new ParsedTable(tableRef, label, caption, headers, List.copyOf(dataRows),
                footnotes, markdown, true, rawXml);
    }

    // --- row / cell extraction -------------------------------------------------

    private List<List<String>> readRows(Element table) {
        List<List<String>> rows = new ArrayList<>();
        for (Element row : directChildren(table, "row")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : directChildren(row, "cell")) {
                String text = normalize(deepText(cell));
                int cols = parseSpan(cell.getAttribute("cols"));
                cells.add(text == null ? "" : text);
                for (int i = 1; i < cols; i++) {
                    cells.add("");
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private List<String> readFootnotes(Element figure) {
        Element note = firstDescendant(figure, "note");
        if (note == null) {
            return List.of();
        }
        List<String> footnotes = new ArrayList<>();
        NodeList sentences = note.getElementsByTagName("*");
        boolean hasSentence = false;
        for (int i = 0; i < sentences.getLength(); i++) {
            if (sentences.item(i) instanceof Element element && "s".equals(element.getLocalName())) {
                hasSentence = true;
                String text = normalize(deepText(element));
                if (text != null && !text.isBlank()) {
                    footnotes.add(text);
                }
            }
        }
        if (!hasSentence) {
            String text = normalize(deepText(note));
            if (text != null && !text.isBlank()) {
                footnotes.add(text);
            }
        }
        return List.copyOf(footnotes);
    }

    private int parseSpan(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    // --- header handling -------------------------------------------------------

    private int detectHeaderRowCount(List<List<String>> rows) {
        int count = 0;
        for (List<String> row : rows) {
            if (isHeaderRow(row)) {
                count++;
            } else {
                break;
            }
        }
        if (count == 0) {
            return 1; // ensure at least one header row
        }
        if (count == rows.size()) {
            return 1; // avoid consuming every row as header
        }
        return count;
    }

    /** A header row carries no numeric measurement cell (only labels / units / blanks). */
    private boolean isHeaderRow(List<String> row) {
        for (String cell : row) {
            String value = cell == null ? "" : cell.trim();
            if (!value.isEmpty() && DATA_VALUE.matcher(value.replace(" ", "")).matches()) {
                return false;
            }
        }
        return true;
    }

    private List<String> mergeHeaders(List<List<String>> headerRows, int columns) {
        List<List<String>> filled = new ArrayList<>();
        for (List<String> row : headerRows) {
            filled.add(forwardFill(row, columns));
        }
        List<String> headers = new ArrayList<>();
        for (int col = 0; col < columns; col++) {
            LinkedHashSet<String> parts = new LinkedHashSet<>();
            for (List<String> row : filled) {
                String value = col < row.size() ? row.get(col) : "";
                if (value != null && !value.isBlank()) {
                    parts.add(value.trim());
                }
            }
            headers.add(String.join(" — ", parts));
        }
        return List.copyOf(headers);
    }

    /** Copy a spanning header cell rightwards into the empty sub-columns it covers. */
    private List<String> forwardFill(List<String> row, int columns) {
        List<String> result = new ArrayList<>();
        String last = "";
        for (int col = 0; col < columns; col++) {
            String value = col < row.size() ? (row.get(col) == null ? "" : row.get(col).trim()) : "";
            if (value.isEmpty()) {
                result.add(last);
            } else {
                result.add(value);
                last = value;
            }
        }
        return result;
    }

    private List<List<String>> pad(List<List<String>> rows, int columns) {
        List<List<String>> padded = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> copy = new ArrayList<>(row);
            while (copy.size() < columns) {
                copy.add("");
            }
            padded.add(copy);
        }
        return padded;
    }

    // --- DOM helpers -----------------------------------------------------------

    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse TEI XML for table extraction", e);
        }
    }

    private List<Element> directChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private Element firstDescendant(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList descendants = parent.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            if (descendants.item(i) instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private String textOfFirstDirectChild(Element element, String localName) {
        for (Element child : directChildren(element, localName)) {
            return normalize(deepText(child));
        }
        return null;
    }

    private String deepText(Node node) {
        return node == null ? null : node.getTextContent();
    }

    private String serializeNode(Node node) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String joinNonBlank(String first, String second) {
        boolean hasFirst = first != null && !first.isBlank();
        boolean hasSecond = second != null && !second.isBlank();
        if (hasFirst && hasSecond) {
            return first.trim() + " " + second.trim();
        }
        return hasFirst ? first.trim() : (hasSecond ? second.trim() : null);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
