package com.example.demo_01.ai.evidence.table;

import java.util.List;

/**
 * Structured representation of a single GROBID TEI table (one {@code <figure type="table">}
 * or a standalone {@code <table>}).
 *
 * <p>This is the on-demand, sidecar unit produced by {@link TeiTableParser}. It is never
 * embedded into the vector store; it is materialized lazily into {@code tables.jsonl} and
 * loaded when an evidence profile needs the table body.</p>
 *
 * @param tableRef   stable identifier within the document, e.g. {@code T1}
 * @param label      the raw table label, e.g. {@code 1} (from {@code <label>})
 * @param caption    the caption / description text (from {@code <head>}/{@code <figDesc>})
 * @param headers    the merged (possibly multi-row) header, one string per column
 * @param rows       data rows, each a list of cell strings aligned to {@code headers}
 * @param footnotes  footnote lines from the table {@code <note>} block
 * @param markdown   pre-rendered GFM markdown of the table (LLM-facing, anchorable text)
 * @param structured {@code true} when {@code <row>/<cell>} were parsed; {@code false} for
 *                   caption-only / image tables (the "no {@code <row>}" degrade case)
 * @param rawTeiXml  the original {@code <figure>}/{@code <table>} XML for audit/backfill
 */
public record ParsedTable(
        String tableRef,
        String label,
        String caption,
        List<String> headers,
        List<List<String>> rows,
        List<String> footnotes,
        String markdown,
        boolean structured,
        String rawTeiXml
) {
}
