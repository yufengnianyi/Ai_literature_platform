import { marked } from 'marked';
import DOMPurify from 'dompurify';

import type { MessageRenderMode, MessageSource } from '../types/chat';

marked.setOptions({
  breaks: true,
  gfm: true,
});

export interface Citation {
  index: number;
  source: string;
  section?: string;
  chunk?: string;
  page?: string;
  referenceId: string;
}

export interface ParsedContent {
  html: string;
  citations: Citation[];
}

export interface CitationPreparationResult {
  processedText: string;
  plaintextText: string;
  citations: Citation[];
}

export interface StreamingMarkdownState {
  stableContent: string;
  pendingTail: string;
}

export interface ParseAIResponseOptions {
  final?: boolean;
  stableContent?: string;
  pendingTail?: string;
  referenceScope?: string;
  sources?: MessageSource[];
}

export interface ParsedAIResponse {
  html: string;
  citations: Citation[];
  pendingTail: string;
  mode: MessageRenderMode;
}

interface FenceState {
  character: '`' | '~';
  length: number;
}

interface BoundaryScanResult {
  safeBoundary: number;
  newlineBoundary: number;
}

const fencedCodePattern = /^(\s{0,3})(`{3,}|~{3,})(.*)$/;
const zeroWidthPattern = /[\u200B-\u200D\uFEFF]/g;
const specialSpacePattern = /[\u00A0\u3000]/g;
const fullWidthDigitOffset = '０'.charCodeAt(0) - '0'.charCodeAt(0);
const allowedInlineHtmlAttrs = {
  ADD_TAGS: ['sup'],
  ADD_ATTR: ['aria-label', 'class', 'data-cite', 'data-reference-target', 'href'],
};

const sanitizeHtml = (
  html: string,
  options?: Parameters<(typeof DOMPurify)['sanitize']>[1],
): string => {
  if (typeof DOMPurify.sanitize === 'function') {
    return DOMPurify.sanitize(html, options);
  }
  return html;
};

const escapeHtml = (value: string): string =>
  value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

const toHalfWidthDigits = (value: string): string =>
  value.replace(/[０-９]/g, (char) => String.fromCharCode(char.charCodeAt(0) - fullWidthDigitOffset));

const parseFence = (line: string): FenceState | null => {
  const match = line.match(fencedCodePattern);
  if (!match) {
    return null;
  }

  const marker = match[2];
  if (!marker) {
    return null;
  }

  return {
    character: marker[0] as FenceState['character'],
    length: marker.length,
  };
};

const isFenceClose = (line: string, activeFence: FenceState | null): boolean => {
  if (!activeFence) {
    return false;
  }

  const fence = parseFence(line);
  return Boolean(
    fence &&
      fence.character === activeFence.character &&
      fence.length >= activeFence.length,
  );
};

const isBlankLine = (line: string): boolean => line.trim().length === 0;
const isHeadingLine = (line: string): boolean => /^(\s{0,3})#{1,6}\s+\S/.test(line);
const isBlockquoteLine = (line: string): boolean => /^(\s{0,3})>\s+\S/.test(line);
const isBulletListLine = (line: string): boolean => /^(\s{0,3})[-+*]\s+\S/.test(line);
const isOrderedListLine = (line: string): boolean => /^(\s{0,3})\d{1,9}\.\s+\S/.test(line);
const isListLine = (line: string): boolean => isBulletListLine(line) || isOrderedListLine(line);
const isTableDelimiterLine = (line: string): boolean => /^(\s*)\|?(\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?(\s*)$/.test(line);
const isTableLikeLine = (line: string): boolean =>
  /^(\s*)\|.*\|(\s*)$/.test(line) || isTableDelimiterLine(line);

const normalizeMarkdownLine = (line: string): string => {
  const headingNormalized = line
    .replace(/^(\s{0,3})(＃{1,6})(\S)/u, (_, indent: string, hashes: string, content: string) => {
      return `${indent}${'#'.repeat(hashes.length)} ${content}`;
    })
    .replace(/^(\s{0,3})(#{1,6})(\S)/, '$1$2 $3');

  const orderedListMatch = headingNormalized.match(/^(\s{0,3})([0-9０-９]{1,9})([.)）])\s*(\S.*)$/u);
  if (orderedListMatch) {
    const indent = orderedListMatch[1] ?? '';
    const digits = toHalfWidthDigits(orderedListMatch[2] ?? '');
    const delimiter = orderedListMatch[3];
    const content = orderedListMatch[4];

    if (!content || (delimiter === '.' && /^\d/.test(content))) {
      return headingNormalized;
    }

    return `${indent}${digits}. ${content}`;
  }

  const bulletListMatch = headingNormalized.match(/^(\s{0,3})([-+*•·●])\s*(\S.*)$/u);
  if (bulletListMatch) {
    const indent = bulletListMatch[1] ?? '';
    const marker = bulletListMatch[2];
    const content = bulletListMatch[3];
    if (!content) {
      return headingNormalized;
    }

    if (
      marker === '*' &&
      (/^\*/.test(content) || /^[^*\s]+\*(?:\s|$)/.test(content))
    ) {
      return headingNormalized;
    }

    return `${indent}- ${content}`;
  }

  const blockquoteMatch = headingNormalized.match(/^(\s{0,3})>(?!\s)(\S.*)$/);
  if (blockquoteMatch) {
    return `${blockquoteMatch[1] ?? ''}> ${blockquoteMatch[2]}`;
  }

  return headingNormalized;
};

const normalizeTextOutsideFences = (text: string, final: boolean): string => {
  if (!text) {
    return '';
  }

  const lines = text
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/^\uFEFF/u, '')
    .split('\n');

  const normalizedLines: string[] = [];
  let activeFence: FenceState | null = null;

  for (const rawLine of lines) {
    if (activeFence) {
      normalizedLines.push(rawLine);
      if (isFenceClose(rawLine, activeFence)) {
        activeFence = null;
      }
      continue;
    }

    const line = rawLine
      .replace(zeroWidthPattern, '')
      .replace(specialSpacePattern, ' ');
    const fence = parseFence(line);
    if (fence) {
      normalizedLines.push(line);
      activeFence = fence;
      continue;
    }

    normalizedLines.push(normalizeMarkdownLine(line));
  }

  if (final && activeFence) {
    normalizedLines.push(activeFence.character.repeat(activeFence.length));
  }

  return normalizedLines.join('\n');
};

const shouldInsertBlankLine = (currentLine: string, previousLine: string | undefined): boolean => {
  if (!previousLine || isBlankLine(previousLine) || isBlankLine(currentLine)) {
    return false;
  }

  if (isHeadingLine(currentLine) || parseFence(currentLine) || isTableLikeLine(currentLine)) {
    return !isTableLikeLine(previousLine);
  }

  if (isListLine(currentLine)) {
    return !isListLine(previousLine);
  }

  if (isBlockquoteLine(currentLine)) {
    return !isBlockquoteLine(previousLine);
  }

  return false;
};

const insertBlankLinesBeforeBlocks = (text: string): string => {
  if (!text) {
    return '';
  }

  const lines = text.split('\n');
  const adjustedLines: string[] = [];
  let activeFence: FenceState | null = null;

  for (const line of lines) {
    if (activeFence) {
      adjustedLines.push(line);
      if (isFenceClose(line, activeFence)) {
        activeFence = null;
      }
      continue;
    }

    if (shouldInsertBlankLine(line, adjustedLines[adjustedLines.length - 1])) {
      adjustedLines.push('');
    }

    adjustedLines.push(line);

    const fence = parseFence(line);
    if (fence) {
      activeFence = fence;
    }
  }

  return adjustedLines.join('\n');
};

const defaultReferenceScope = 'citation';

const normalizeReferenceScope = (value?: string): string => {
  const sanitized = (value ?? '')
    .trim()
    .replace(/[^a-zA-Z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '');

  return sanitized || defaultReferenceScope;
};

const buildReferenceId = (referenceScope: string, index: number): string =>
  `${referenceScope}-ref-${index}`;

const buildSourceLookup = (
  sources?: MessageSource[],
): Map<string, MessageSource | null> => {
  const sourceLookup = new Map<string, MessageSource | null>();

  for (const source of sources ?? []) {
    const key = source.title.trim().toLowerCase();
    if (!key) {
      continue;
    }

    if (!sourceLookup.has(key)) {
      sourceLookup.set(key, source);
      continue;
    }

    const existing = sourceLookup.get(key);
    if (!existing) {
      continue;
    }

    const hasConflictingMetadata =
      existing.section !== source.section ||
      existing.chunk !== source.chunk ||
      existing.page !== source.page;

    if (hasConflictingMetadata) {
      sourceLookup.set(key, null);
    }
  }

  return sourceLookup;
};

const parseCitationTokenBody = (
  token: string,
  sourceLookup: Map<string, MessageSource | null>,
): Omit<Citation, 'index' | 'referenceId'> | null => {
  const body = token
    .replace(/^\{/, '')
    .replace(/\}$/, '')
    .replace(/^〔/u, '')
    .replace(/〕$/u, '');

  const fields = body
    .split(';')
    .map((part) => part.trim())
    .filter(Boolean);

  const entries = new Map<string, string>();
  for (const field of fields) {
    const separatorIndex = field.indexOf('=');
    if (separatorIndex <= 0) {
      continue;
    }
    const key = field.slice(0, separatorIndex).trim().toLowerCase();
    const value = field.slice(separatorIndex + 1).trim();
    if (key && value) {
      entries.set(key, value);
    }
  }

  const source = entries.get('source');
  if (!source) {
    return null;
  }

  const matchedSource = sourceLookup.get(source.trim().toLowerCase());

  return {
    source,
    section: entries.get('section') ?? matchedSource?.section,
    chunk: entries.get('chunk') ?? matchedSource?.chunk,
    page: entries.get('page') ?? matchedSource?.page,
  };
};

const citationTokenPattern = /(\{source=[^{}\n]+\}|〔source=[^〔〕\n]+〕)/gu;

export const normalizeMarkdownSyntax = (text: string, options?: { final?: boolean }): string => {
  if (!text) {
    return '';
  }

  const normalized = normalizeTextOutsideFences(text, options?.final ?? false);
  return insertBlankLinesBeforeBlocks(normalized);
};

export const prepareCitations = (
  text: string,
  options?: { referenceScope?: string; sources?: MessageSource[] },
): CitationPreparationResult => {
  if (!text) {
    return { processedText: '', plaintextText: '', citations: [] };
  }

  const referenceScope = normalizeReferenceScope(options?.referenceScope);
  const sourceLookup = buildSourceLookup(options?.sources);
  const citationIndexMap = new Map<string, number>();
  const citations: Citation[] = [];
  let counter = 1;

  const replacer = (token: string, htmlMode: boolean): string => {
    const parsed = parseCitationTokenBody(token, sourceLookup);
    if (!parsed) {
      return '';
    }

    const citationKey = [
      parsed.source,
      parsed.section ?? '',
      parsed.chunk ?? '',
      parsed.page ?? '',
    ].join('||');

    let index = citationIndexMap.get(citationKey);
    if (!index) {
      index = counter;
      counter += 1;
      citationIndexMap.set(citationKey, index);
      citations.push({
        index,
        ...parsed,
        referenceId: buildReferenceId(referenceScope, index),
      });
    }

    const referenceId = buildReferenceId(referenceScope, index);

    return htmlMode
      ? `<sup class="citation-marker" data-cite="${index}"><a class="citation-link" href="#${referenceId}" data-reference-target="${referenceId}" aria-label="Jump to reference ${index}">${index}</a></sup>`
      : `[${index}]`;
  };

  const processedText = text.replace(citationTokenPattern, (token) => replacer(token, true));
  const plaintextText = text.replace(citationTokenPattern, (token) => replacer(token, false));

  return {
    processedText,
    plaintextText,
    citations,
  };
};

const renderPlaintextHtml = (text: string): string =>
  sanitizeHtml(escapeHtml(text).replace(/\n/g, '<br>'));

const scanMarkdownBoundaries = (text: string): BoundaryScanResult => {
  let safeBoundary = 0;
  let newlineBoundary = 0;
  let activeFence: FenceState | null = null;
  let offset = 0;

  while (offset < text.length) {
    const newlineIndex = text.indexOf('\n', offset);
    const lineEnd = newlineIndex === -1 ? text.length : newlineIndex;
    const segmentEnd = newlineIndex === -1 ? text.length : newlineIndex + 1;
    const line = text.slice(offset, lineEnd);

    if (activeFence) {
      if (isFenceClose(line, activeFence)) {
        activeFence = null;
        safeBoundary = segmentEnd;
        newlineBoundary = segmentEnd;
      }
    } else {
      const fence = parseFence(line);
      if (fence) {
        activeFence = fence;
      } else if (isBlankLine(line)) {
        safeBoundary = segmentEnd;
        newlineBoundary = segmentEnd;
      } else if (newlineIndex !== -1) {
        newlineBoundary = segmentEnd;
      }
    }

    offset = segmentEnd;
  }

  return {
    safeBoundary,
    newlineBoundary,
  };
};

export const splitMarkdownStream = (
  text: string,
  options?: { charThreshold?: number },
): StreamingMarkdownState => {
  if (!text) {
    return { stableContent: '', pendingTail: '' };
  }

  const charThreshold = options?.charThreshold ?? 240;
  const normalizedText = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const { safeBoundary, newlineBoundary } = scanMarkdownBoundaries(normalizedText);

  let boundary = safeBoundary;
  if (boundary <= 0 && newlineBoundary > 0 && normalizedText.length - newlineBoundary >= charThreshold) {
    boundary = newlineBoundary;
  }

  return {
    stableContent: normalizedText.slice(0, boundary),
    pendingTail: normalizedText.slice(boundary),
  };
};

const shouldFallbackToPlaintext = (normalizedText: string, html: string): boolean => {
  if (!normalizedText.trim()) {
    return false;
  }

  const singleParagraph = /^<p>[\s\S]*<\/p>\s*$/i.test(html.trim());
  if (!singleParagraph) {
    return false;
  }

  const lines = normalizedText.split('\n').filter((line) => line.trim().length > 0);
  const hasHeading = lines.some(isHeadingLine);
  const hasList = lines.some(isListLine);
  const hasTable = lines.some(isTableLikeLine);
  const hasFence = lines.some((line) => Boolean(parseFence(line)));
  const hasBlockquote = lines.some(isBlockquoteLine);

  if (hasHeading && !/<h[1-6][^>]*>/i.test(html)) {
    return true;
  }

  if (hasList && !/(<ul>|<ol>)/i.test(html)) {
    return true;
  }

  if (hasTable && !/<table>/i.test(html)) {
    return true;
  }

  if (hasFence && !/<pre>/i.test(html)) {
    return true;
  }

  if (hasBlockquote && !/<blockquote>/i.test(html)) {
    return true;
  }

  return false;
};

export const parseAIResponse = (
  rawText: string,
  options?: ParseAIResponseOptions,
): ParsedAIResponse => {
  if (!rawText) {
    return {
      html: '',
      citations: [],
      pendingTail: '',
      mode: 'markdown',
    };
  }

  const final = options?.final ?? false;
  const streamingState = final
    ? { stableContent: rawText, pendingTail: '' }
    : {
        stableContent: options?.stableContent ?? splitMarkdownStream(rawText).stableContent,
        pendingTail: options?.pendingTail ?? splitMarkdownStream(rawText).pendingTail,
      };

  const normalizedStable = normalizeMarkdownSyntax(streamingState.stableContent, { final });
  const prepared = prepareCitations(normalizedStable, {
    referenceScope: options?.referenceScope,
    sources: options?.sources,
  });

  try {
    const rawHtml = marked.parse(prepared.processedText) as string;
    const html = sanitizeHtml(rawHtml, allowedInlineHtmlAttrs);

    if (shouldFallbackToPlaintext(normalizedStable, html)) {
      return {
        html: renderPlaintextHtml(prepared.plaintextText),
        citations: prepared.citations,
        pendingTail: streamingState.pendingTail,
        mode: 'plaintext-fallback',
      };
    }

    return {
      html,
      citations: prepared.citations,
      pendingTail: streamingState.pendingTail,
      mode: 'markdown',
    };
  } catch {
    return {
      html: renderPlaintextHtml(prepared.plaintextText),
      citations: prepared.citations,
      pendingTail: streamingState.pendingTail,
      mode: 'plaintext-fallback',
    };
  }
};

export const parseCitations = (text: string): ParsedContent => {
  const parsed = parseAIResponse(text, { final: true });
  return {
    html: parsed.html,
    citations: parsed.citations,
  };
};

export const renderMarkdown = (text: string): string => parseAIResponse(text, { final: true }).html;
