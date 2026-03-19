import assert from 'node:assert/strict';

import {
  normalizeMarkdownSyntax,
  parseAIResponse,
  prepareCitations,
  splitMarkdownStream,
} from '../markdown.ts';
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
  'Answer {source=Paper A; section=Intro; chunk=3}\nAgain {source=Paper A; section=Intro; chunk=3}\nNext 〔source=Paper B; page=7〕',
  { referenceScope: 'message-42' },
);
assert.equal(preparedCitations.citations.length, 2);
assert.equal(preparedCitations.citations[0]?.referenceId, 'message-42-ref-1');
assert.equal(preparedCitations.citations[1]?.referenceId, 'message-42-ref-2');
assert.match(
  preparedCitations.processedText,
  /<sup class="citation-marker" data-cite="1"><a class="citation-link" href="#message-42-ref-1" data-reference-target="message-42-ref-1" aria-label="Jump to reference 1">1<\/a><\/sup>/,
);
assert.match(
  preparedCitations.processedText,
  /<sup class="citation-marker" data-cite="2"><a class="citation-link" href="#message-42-ref-2" data-reference-target="message-42-ref-2" aria-label="Jump to reference 2">2<\/a><\/sup>/,
);
assert.match(preparedCitations.plaintextText, /\[2\]/);

const normalizedStringSources = normalizeSourcesPayload([' Paper A ', '', 'Paper B']);
assert.deepEqual(normalizedStringSources, [
  { title: 'Paper A' },
  { title: 'Paper B' },
]);

const normalizedStructuredSources = normalizeSourcesPayload([
  { source: 'Paper A', section: 'Intro', chunk: 3, page: 7 },
  { title: 'Paper A', section: 'Intro', chunk: 3, page: 7 },
  { name: 'Paper B', sectionName: 'Results', chunkId: 11, pageNumber: 2 },
  { invalid: true },
]);
assert.deepEqual(normalizedStructuredSources, [
  { title: 'Paper A', section: 'Intro', chunk: '3', page: '7' },
  { title: 'Paper B', section: 'Results', chunk: '11', page: '2' },
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
