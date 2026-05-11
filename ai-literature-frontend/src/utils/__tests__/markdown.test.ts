import assert from 'node:assert/strict';

import {
  normalizeMarkdownSyntax,
  parseAIResponse,
  prepareCitations,
  splitMarkdownStream,
} from '../markdown.ts';
import { extractMarkdownTables } from '../reviewPresentation.ts';
import { normalizeSourcesPayload } from '../sources.ts';

const headingNormalized = normalizeMarkdownSyntax('###Heading');
assert.equal(headingNormalized, '### Heading');

const bulletNormalized = normalizeMarkdownSyntax('-Bullet item');
assert.equal(bulletNormalized, '- Bullet item');

const orderedNormalized = normalizeMarkdownSyntax('1.Ordered item');
assert.equal(orderedNormalized, '1. Ordered item');

const blockquoteNormalized = normalizeMarkdownSyntax('>Quoted line');
assert.equal(blockquoteNormalized, '> Quoted line');

const paragraphWithList = normalizeMarkdownSyntax('Summary line\n-Item A\n-Item B');
assert.equal(paragraphWithList, 'Summary line\n\n- Item A\n- Item B');

const paragraphWithTable = normalizeMarkdownSyntax('Summary line\n| col | value |\n| --- | --- |\n| a | b |');
assert.equal(paragraphWithTable, 'Summary line\n\n| col | value |\n| --- | --- |\n| a | b |');

const extractedTables = extractMarkdownTables('Intro\n\n| col | value |\n| --- | --- |\n| a | b |\n| c | d |');
assert.deepEqual(extractedTables, [
  {
    id: 'table-1',
    title: 'Table 1',
    headers: ['col', 'value'],
    rows: [['a', 'b'], ['c', 'd']],
  },
]);

const fenceStreamingState = splitMarkdownStream('```ts\nconst a = 1\n');
assert.equal(fenceStreamingState.stableContent, '');
assert.equal(fenceStreamingState.pendingTail, '```ts\nconst a = 1\n');

const paragraphBoundary = splitMarkdownStream('Intro line\n\n# Heading\n');
assert.equal(paragraphBoundary.stableContent, 'Intro line\n\n');
assert.equal(paragraphBoundary.pendingTail, '# Heading\n');

const thresholdBoundary = splitMarkdownStream('Line 1\nLine 2\nLine 3', { charThreshold: 5 });
assert.equal(thresholdBoundary.stableContent, 'Line 1\nLine 2\n');
assert.equal(thresholdBoundary.pendingTail, 'Line 3');

const preparedCitations = prepareCitations(
  'Answer {source=Paper A; section=Intro; chunk=3; quote=Direct evidence summary}\nAgain {source=Paper A; section=Intro; chunk=3; quote=Direct evidence summary}\nNext 〔source=Paper B; page=7〕',
  { referenceScope: 'message-42' },
);
assert.equal(preparedCitations.citations.length, 2);
assert.equal(preparedCitations.citations[0]?.referenceId, 'message-42-ref-1');
assert.equal(preparedCitations.citations[0]?.excerpt, 'Direct evidence summary');
assert.equal(preparedCitations.citations[1]?.referenceId, 'message-42-ref-2');
assert.match(
  preparedCitations.processedText,
  /<sup class="citation-marker" data-cite="1"><a class="citation-link" href="#message-42-ref-1" data-reference-target="message-42-ref-1" aria-label="Jump to reference 1" title="Paper A · Intro · chunk 3 · Direct evidence summary">1<\/a><\/sup>/,
);
assert.match(
  preparedCitations.processedText,
  /<sup class="citation-marker" data-cite="2"><a class="citation-link" href="#message-42-ref-2" data-reference-target="message-42-ref-2" aria-label="Jump to reference 2" title="Paper B · page 7">2<\/a><\/sup>/,
);
assert.match(preparedCitations.plaintextText, /\[2\]/);

const preparedRawHtmlCitation = prepareCitations(
  'Finding <sup class="citation-marker" data-cite="11"><a class="citation-link" href="#old" title="Paper C · Evidence">11</a></sup>',
  { referenceScope: 'message-html' },
);
assert.equal(preparedRawHtmlCitation.citations.length, 1);
assert.equal(preparedRawHtmlCitation.citations[0]?.index, 11);
assert.equal(preparedRawHtmlCitation.citations[0]?.source, 'Paper C');
assert.doesNotMatch(preparedRawHtmlCitation.processedText, /&lt;sup|class=&quot;citation-marker/);
assert.match(preparedRawHtmlCitation.processedText, /data-reference-target="message-html-ref-11"/);
assert.equal(preparedRawHtmlCitation.plaintextText, 'Finding [11]');

const parsedEscapedHtmlCitation = parseAIResponse(
  'Finding &lt;sup class="citation-marker" data-cite="12"&gt;&lt;a class="citation-link" href="#old" title="Paper D · Evidence"&gt;12&lt;/a&gt;&lt;/sup&gt;',
  { final: true, referenceScope: 'message-escaped' },
);
assert.equal(parsedEscapedHtmlCitation.citations.length, 1);
assert.equal(parsedEscapedHtmlCitation.citations[0]?.index, 12);
assert.equal(parsedEscapedHtmlCitation.citations[0]?.source, 'Paper D');
assert.doesNotMatch(parsedEscapedHtmlCitation.html, /&lt;sup|class=&quot;citation-marker/);
assert.match(parsedEscapedHtmlCitation.html, /data-reference-target="message-escaped-ref-12"/);

const parsedCodeWrappedHtmlCitation = parseAIResponse(
  'Finding `<sup class="citation-marker" data-cite="13"><a class="citation-link" href="#old" title="Paper E · Evidence">13</a></sup>`',
  { final: true, referenceScope: 'message-code-wrapped' },
);
assert.equal(parsedCodeWrappedHtmlCitation.citations.length, 1);
assert.equal(parsedCodeWrappedHtmlCitation.citations[0]?.index, 13);
assert.equal(parsedCodeWrappedHtmlCitation.citations[0]?.source, 'Paper E');
assert.doesNotMatch(parsedCodeWrappedHtmlCitation.html, /<code>|&lt;sup|class=&quot;citation-marker/);
assert.match(parsedCodeWrappedHtmlCitation.html, /data-reference-target="message-code-wrapped-ref-13"/);

const parsedCodeWrappedSourceCitation = parseAIResponse(
  'Finding `{source=Paper F; chunk=7; quote=Evidence}`',
  { final: true, referenceScope: 'message-source-code-wrapped' },
);
assert.equal(parsedCodeWrappedSourceCitation.citations.length, 1);
assert.equal(parsedCodeWrappedSourceCitation.citations[0]?.source, 'Paper F');
assert.doesNotMatch(parsedCodeWrappedSourceCitation.html, /<code>|source=Paper F/);
assert.match(parsedCodeWrappedSourceCitation.html, /data-reference-target="message-source-code-wrapped-ref-1"/);

const normalizedStringSources = normalizeSourcesPayload([' Paper A ', '', 'Paper B']);
assert.deepEqual(normalizedStringSources, [
  { title: 'Paper A' },
  { title: 'Paper B' },
]);

const normalizedStructuredSources = normalizeSourcesPayload([
  { source: 'Paper A', section: 'Intro', chunk: 3, page: 7 },
  { title: 'Paper A', section: 'Intro', chunk: 3, page: 7 },
  { name: 'Paper B', sectionName: 'Results', chunkId: 11, pageNumber: 2, snippet: 'Evidence excerpt' },
  { invalid: true },
]);
assert.deepEqual(normalizedStructuredSources, [
  { title: 'Paper A', section: 'Intro', chunk: '3', page: '7' },
  { title: 'Paper B', section: 'Results', chunk: '11', page: '2', excerpt: 'Evidence excerpt' },
]);

const finalParsed = parseAIResponse(
  'Summary line\n###Heading\n-Bullet item\n1.Ordered item\n\n```ts\nconst answer = 42\n',
  { final: true },
);
assert.equal(finalParsed.mode, 'markdown');
assert.match(finalParsed.html, /<h3>Heading<\/h3>/);
assert.match(finalParsed.html, /<ul>\s*<li>Bullet item<\/li>/);
assert.match(finalParsed.html, /<ol>\s*<li>Ordered item<\/li>/);
assert.match(finalParsed.html, /<pre><code class="language-ts">const answer = 42/);
assert.equal(finalParsed.pendingTail, '');

const streamingParsed = parseAIResponse('Summary line\n\n# Heading\nTrailing text', {
  final: false,
});
assert.match(streamingParsed.html, /<p>Summary line<\/p>/);
assert.equal(streamingParsed.pendingTail, '# Heading\nTrailing text');

const parsedWithoutCitationMarkers = parseAIResponse('Narrative only', {
  final: true,
  referenceScope: 'message-plain',
  sources: normalizedStructuredSources,
});
assert.equal(parsedWithoutCitationMarkers.citations.length, 0);
assert.doesNotMatch(parsedWithoutCitationMarkers.html, /citation-link/);

const enrichedCitations = prepareCitations(
  'Answer {source=Paper B}',
  {
    referenceScope: 'message-enriched',
    sources: normalizedStructuredSources,
  },
);
assert.deepEqual(enrichedCitations.citations, [
  {
    index: 1,
    source: 'Paper B',
    section: 'Results',
    chunk: '11',
    page: '2',
    excerpt: 'Evidence excerpt',
    referenceId: 'message-enriched-ref-1',
  },
]);

const emphasisPreserved = parseAIResponse('*emphasis* text', { final: true });
assert.equal(emphasisPreserved.mode, 'markdown');
assert.match(emphasisPreserved.html, /<em>emphasis<\/em> text/);

const escapedMarker = parseAIResponse('\\# literal heading', { final: true });
assert.equal(escapedMarker.mode, 'markdown');
assert.doesNotMatch(escapedMarker.html, /<h1>/);

console.log('markdown regression checks passed');
