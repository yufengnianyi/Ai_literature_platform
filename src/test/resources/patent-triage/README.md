# Patent triage regression extracts

`real-patent-pages.json` contains all physical pages of the four named source
patents, extracted with `PatentPdfTextService` (PDFBox 3.0.6, sort by position)
from the local source PDFs on 2026-09-03. Text, paragraph numbers, whitespace,
and page breaks are preserved, including extraction artifacts and empty image
table bodies. No OCR, inferred values, or LLM output is included.

The test fixtures cover CN106857590B (6 pages), CN118339136A (18 pages),
CN111418597A (14 pages), and CN109627065A (13 pages). Tests load this file
offline and do not require those PDFs, external services, or local data folders.
