package com.example.demo_01.ai.evidence.table;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders a parsed table into GFM markdown. The rendered text is exactly the text that is
 * injected into the extraction context as an anchorable chunk, so the extractor's verbatim
 * {@code exactQuote} check (whitespace-normalized) always has a valid target here.
 */
@Component
public class TableSerializer {

    public String render(String caption,
                         List<String> headers,
                         List<List<String>> rows,
                         List<String> footnotes,
                         boolean structured) {
        StringBuilder markdown = new StringBuilder();
        if (caption != null && !caption.isBlank()) {
            markdown.append(caption.trim()).append("\n\n");
        }
        if (!structured || headers == null || headers.isEmpty()) {
            markdown.append("(Table body is not machine-readable in the source TEI; "
                    + "only the caption above is available.)");
            appendFootnotes(markdown, footnotes);
            return markdown.toString().trim();
        }
        appendRow(markdown, headers);
        appendRow(markdown, headers.stream().map(header -> "---").toList());
        for (List<String> row : rows) {
            appendRow(markdown, row);
        }
        appendFootnotes(markdown, footnotes);
        return markdown.toString().trim();
    }

    private void appendFootnotes(StringBuilder markdown, List<String> footnotes) {
        if (footnotes == null || footnotes.isEmpty()) {
            return;
        }
        markdown.append("\nFootnotes: ").append(String.join(" ", footnotes));
    }

    private void appendRow(StringBuilder markdown, List<String> cells) {
        markdown.append("| ");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                markdown.append(" | ");
            }
            markdown.append(escape(cells.get(index)));
        }
        markdown.append(" |\n");
    }

    private String escape(String cell) {
        if (cell == null) {
            return "";
        }
        return cell.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
    }
}
