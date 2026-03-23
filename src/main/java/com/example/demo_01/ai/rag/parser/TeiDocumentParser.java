package com.example.demo_01.ai.rag.parser;

import jakarta.annotation.Resource;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Component
public class TeiDocumentParser {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?\u3002\uFF01\uFF1F])\\s+");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern SKIPPED_SECTION = Pattern.compile("reference|bibliograph|acknowledg|funding|supplement|appendix", Pattern.CASE_INSENSITIVE);

    @Resource
    private DoiNormalizer doiNormalizer;

    public RagDocumentMetadata parseMetadata(String teiXml) {
        Document document = parseXml(teiXml);
        XPath xpath = XPathFactory.newInstance().newXPath();

        String doiRaw = firstString(xpath, document,
                "(//*[local-name()='teiHeader']//*[local-name()='idno' and translate(@type, 'abcdefghijklmnopqrstuvwxyz', 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') = 'DOI'])[1]");
        String title = firstNonBlank(
                firstString(xpath, document, "(//*[local-name()='teiHeader']//*[local-name()='titleStmt']/*[local-name()='title'])[1]"),
                firstString(xpath, document, "(//*[local-name()='teiHeader']//*[local-name()='analytic']/*[local-name()='title'])[1]")
        );
        List<String> authors = extractAuthors(xpath, document);
        List<String> affiliations = extractDistinctTexts(xpath, document,
                "//*[local-name()='teiHeader']//*[local-name()='affiliation']");
        String abstractText = extractAbstract(document, xpath);
        String journal = firstNonBlank(
                firstString(xpath, document, "(//*[local-name()='teiHeader']//*[local-name()='monogr']/*[local-name()='title'])[1]"),
                firstString(xpath, document, "(//*[local-name()='teiHeader']//*[local-name()='sourceDesc']//*[local-name()='title'])[1]")
        );
        String publicationDate = firstNonBlank(
                firstString(xpath, document, "string((//*[local-name()='teiHeader']//*[local-name()='date'][@when])[1]/@when)"),
                firstString(xpath, document, "(//*[local-name()='teiHeader']//*[local-name()='date'])[1]")
        );
        Integer publicationYear = extractYear(publicationDate);
        return new RagDocumentMetadata(
                doiRaw,
                doiNormalizer.normalize(doiRaw),
                normalize(title),
                authors,
                affiliations,
                abstractText,
                normalize(journal),
                normalize(publicationDate),
                publicationYear
        );
    }

    public ParsedTeiDocument parse(String teiXml) {
        Document document = parseXml(teiXml);
        XPath xpath = XPathFactory.newInstance().newXPath();
        RagDocumentMetadata metadata = parseMetadata(teiXml);
        List<ChunkUnit> chunkUnits = new ArrayList<>();

        appendAbstract(chunkUnits, document, xpath);
        appendBody(chunkUnits, document, xpath);
        appendCaptions(chunkUnits, document, xpath);

        return new ParsedTeiDocument(metadata, chunkUnits);
    }

    private void appendAbstract(List<ChunkUnit> units, Document document, XPath xpath) {
        NodeList abstractParagraphs = nodes(xpath, document, "//*[local-name()='text']/*[local-name()='front']//*[local-name()='abstract']/*[local-name()='p']");
        if (abstractParagraphs.getLength() == 0) {
            String abstractText = extractAbstract(document, xpath);
            if (abstractText != null && !abstractText.isBlank()) {
                appendText(units, "abstract", "Abstract", 1, abstractText, false);
            }
            return;
        }
        for (int i = 0; i < abstractParagraphs.getLength(); i++) {
            appendNodeSentences(units, abstractParagraphs.item(i), "abstract", "Abstract", i + 1);
        }
    }

    private void appendBody(List<ChunkUnit> units, Document document, XPath xpath) {
        Node body = firstNode(xpath, document, "//*[local-name()='text']/*[local-name()='body']");
        if (body == null) {
            return;
        }
        AtomicInteger unnamedSectionCounter = new AtomicInteger(1);
        NodeList children = body.getChildNodes();
        int paragraphIndex = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element element)) {
                continue;
            }
            String name = element.getLocalName();
            if ("div".equals(name)) {
                appendDiv(units, element, new ArrayDeque<>(), unnamedSectionCounter);
            } else if ("p".equals(name)) {
                paragraphIndex++;
                appendNodeSentences(units, element, "body", "Body", paragraphIndex);
            }
        }
    }

    private void appendDiv(List<ChunkUnit> units,
                           Element div,
                           ArrayDeque<String> path,
                           AtomicInteger unnamedSectionCounter) {
        String head = normalize(textOfFirstDirectChild(div, "head"));
        ArrayDeque<String> currentPath = new ArrayDeque<>(path);
        if (head != null && !head.isBlank()) {
            currentPath.addLast(head);
        } else if (currentPath.isEmpty()) {
            currentPath.addLast("Section_" + unnamedSectionCounter.getAndIncrement());
        }
        String sectionPath = String.join(" > ", currentPath);
        if (SKIPPED_SECTION.matcher(sectionPath).find()) {
            return;
        }

        int paragraphIndex = 0;
        NodeList children = div.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element element)) {
                continue;
            }
            String name = element.getLocalName();
            if ("p".equals(name)) {
                paragraphIndex++;
                appendNodeSentences(units, element, "body", sectionPath, paragraphIndex);
            } else if ("div".equals(name)) {
                appendDiv(units, element, currentPath, unnamedSectionCounter);
            }
        }
    }

    private void appendCaptions(List<ChunkUnit> units, Document document, XPath xpath) {
        NodeList figures = nodes(xpath, document, "//*[local-name()='figure']");
        for (int i = 0; i < figures.getLength(); i++) {
            Node figure = figures.item(i);
            if (!(figure instanceof Element element)) {
                continue;
            }
            String type = element.getAttribute("type");
            String head = firstNonBlank(textOfFirstDirectChild(element, "head"), textOfFirstDirectChild(element, "label"));
            String figDesc = textOfFirstDirectChild(element, "figDesc");
            String captionText = normalize(firstNonBlank(figDesc, textOfFirstDirectChild(element, "p")));
            if (captionText == null || captionText.isBlank()) {
                continue;
            }
            boolean isTable = type != null && type.toLowerCase(Locale.ROOT).contains("table");
            String contentType = isTable ? "table_caption" : "figure_caption";
            String sectionPath = (isTable ? "Table" : "Figure") + (head == null || head.isBlank() ? "" : " > " + normalize(head));
            appendText(units, contentType, sectionPath, i + 1, captionText, false);
        }
    }

    private void appendNodeSentences(List<ChunkUnit> units, Node node, String contentType, String sectionPath, int paragraphIndex) {
        NodeList sentenceNodes = descendantElements(node, "s");
        if (sentenceNodes.getLength() == 0) {
            appendText(units, contentType, sectionPath, paragraphIndex, normalize(node.getTextContent()), false);
            return;
        }
        for (int i = 0; i < sentenceNodes.getLength(); i++) {
            String text = normalize(sentenceNodes.item(i).getTextContent());
            if (text == null || text.isBlank()) {
                continue;
            }
            units.add(new ChunkUnit(contentType, sectionPath, paragraphIndex, i + 1, text));
        }
    }

    private void appendText(List<ChunkUnit> units,
                            String contentType,
                            String sectionPath,
                            int paragraphIndex,
                            String text,
                            boolean keepAsSingleSentence) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (keepAsSingleSentence) {
            units.add(new ChunkUnit(contentType, sectionPath, paragraphIndex, 1, text));
            return;
        }
        String[] parts = SENTENCE_BOUNDARY.split(text);
        int sentenceIndex = 0;
        for (String part : parts) {
            String normalized = normalize(part);
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            sentenceIndex++;
            units.add(new ChunkUnit(contentType, sectionPath, paragraphIndex, sentenceIndex, normalized));
        }
        if (sentenceIndex == 0) {
            units.add(new ChunkUnit(contentType, sectionPath, paragraphIndex, 1, text));
        }
    }

    private String extractAbstract(Document document, XPath xpath) {
        return normalize(firstString(xpath, document, "(//*[local-name()='text']/*[local-name()='front']//*[local-name()='abstract'])[1]"));
    }

    private List<String> extractAuthors(XPath xpath, Document document) {
        NodeList authors = nodes(xpath, document, "//*[local-name()='teiHeader']//*[local-name()='author']");
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < authors.getLength(); i++) {
            String candidate = extractAuthorName(authors.item(i));
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String dedupeKey = candidate.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "").toLowerCase(Locale.ROOT);
            String existing = values.get(dedupeKey);
            if (existing == null || existing.indexOf(' ') < 0 && candidate.indexOf(' ') >= 0) {
                values.put(dedupeKey, candidate);
            }
        }
        return List.copyOf(values.values());
    }

    private String extractAuthorName(Node author) {
        NodeList persNames = descendantElements(author, "persName");
        if (persNames.getLength() > 0 && persNames.item(0) instanceof Element persName) {
            List<String> parts = new ArrayList<>();
            NodeList children = persName.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element childElement) {
                    String value = normalize(childElement.getTextContent());
                    if (value != null && !value.isBlank()) {
                        parts.add(value);
                    }
                }
            }
            if (!parts.isEmpty()) {
                return normalize(String.join(" ", parts));
            }
            String inlineName = normalize(persName.getTextContent());
            if (inlineName != null && !inlineName.isBlank()) {
                return inlineName;
            }
        }
        return normalize(author.getTextContent());
    }

    private List<String> extractDistinctTexts(XPath xpath, Document document, String expression) {
        NodeList nodes = nodes(xpath, document, expression);
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = normalize(nodes.item(i).getTextContent());
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private Integer extractYear(String publicationDate) {
        if (publicationDate == null || publicationDate.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = YEAR_PATTERN.matcher(publicationDate);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse TEI XML", e);
        }
    }

    private Node firstNode(XPath xpath, Document document, String expression) {
        try {
            return (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
        } catch (Exception e) {
            throw new IllegalStateException("XPath query failed: " + expression, e);
        }
    }

    private NodeList nodes(XPath xpath, Document document, String expression) {
        try {
            return (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
        } catch (Exception e) {
            throw new IllegalStateException("XPath query failed: " + expression, e);
        }
    }

    private String firstString(XPath xpath, Document document, String expression) {
        try {
            String value = xpath.evaluate(expression, document);
            return normalize(value);
        } catch (Exception e) {
            throw new IllegalStateException("XPath query failed: " + expression, e);
        }
    }

    private NodeList descendantElements(Node node, String localName) {
        if (!(node instanceof Element) && !(node instanceof Document)) {
            return new EmptyNodeList();
        }
        List<Node> result = new ArrayList<>();
        collectDescendants(node, localName, result);
        return new ListBackedNodeList(result);
    }

    private void collectDescendants(Node node, String localName, List<Node> result) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                if (localName.equals(element.getLocalName())) {
                    result.add(element);
                }
                collectDescendants(element, localName, result);
            }
        }
    }

    private String textOfFirstDirectChild(Element element, String localName) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement && localName.equals(childElement.getLocalName())) {
                return normalize(childElement.getTextContent());
            }
        }
        return null;
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

    private static final class EmptyNodeList implements NodeList {
        @Override
        public Node item(int index) {
            return null;
        }

        @Override
        public int getLength() {
            return 0;
        }
    }

    private static final class ListBackedNodeList implements NodeList {
        private final List<Node> nodes;

        private ListBackedNodeList(List<Node> nodes) {
            this.nodes = nodes;
        }

        @Override
        public Node item(int index) {
            return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
        }

        @Override
        public int getLength() {
            return nodes.size();
        }
    }
}
