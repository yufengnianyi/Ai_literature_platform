import assert from 'node:assert/strict';
import { marked } from 'marked';

import {
  normalizeMarkdownSyntax,
  prepareCitations,
} from '../markdown.ts';

const renderNormalizedMarkdown = (text: string) => marked.parse(normalizeMarkdownSyntax(text)) as string;

const headingHtml = renderNormalizedMarkdown('###1）结论');
assert.match(headingHtml, /<h3>1）结论<\/h3>/);

const bulletHtml = renderNormalizedMarkdown('-RLKs在植物中的重要性');
assert.match(bulletHtml, /<ul>/);
assert.match(bulletHtml, /<li>RLKs在植物中的重要性<\/li>/);

const parenthesisListHtml = renderNormalizedMarkdown('1)证据');
assert.match(parenthesisListHtml, /<ol>/);
assert.match(parenthesisListHtml, /<li>证据<\/li>/);

const dotListHtml = renderNormalizedMarkdown('1.证据');
assert.match(dotListHtml, /<ol>/);
assert.match(dotListHtml, /<li>证据<\/li>/);

const blockquoteHtml = renderNormalizedMarkdown('>引用');
assert.match(blockquoteHtml, /<blockquote>/);
assert.match(blockquoteHtml, /<p>引用<\/p>/);

const fencedMarkdown = '```md\n###1）结论\n-RLKs在植物中的重要性\n```';
assert.equal(normalizeMarkdownSyntax(fencedMarkdown), fencedMarkdown);

const emphasisHtml = renderNormalizedMarkdown('*强调* 内容');
assert.doesNotMatch(emphasisHtml, /<ul>/);
assert.match(emphasisHtml, /<em>强调<\/em> 内容/);

const citationPrepared = prepareCitations('###1）结论 {source=Paper A}\n-RLKs在植物中的重要性 {source=Paper A}');
const citationHtml = renderNormalizedMarkdown(citationPrepared.processedText);
assert.equal(citationPrepared.citations.length, 1);
assert.match(citationHtml, /<h3>1）结论 <sup class="cite-num" data-cite="1">\[1\]<\/sup><\/h3>/);
assert.match(citationHtml, /<li>RLKs在植物中的重要性 <sup class="cite-num" data-cite="1">\[1\]<\/sup><\/li>/);

console.log('markdown regression checks passed');
