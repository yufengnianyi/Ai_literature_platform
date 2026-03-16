import { marked } from 'marked';
import DOMPurify from 'dompurify';

marked.setOptions({
  breaks: true,
  gfm: true,
});

export interface Citation {
  index: number;
  source: string;
  section?: string;
}

export interface ParsedContent {
  html: string;
  citations: Citation[];
}

export interface CitationPreparationResult {
  processedText: string;
  citations: Citation[];
}

interface FenceState {
  character: '`' | '~';
  length: number;
}

const fencedCodePattern = /^(\s{0,3})(`{3,}|~{3,})(.*)$/;
const citationPattern = /\{source=([^;}\n]+?)(?:;\s*section=([^}\n]+?))?\}/g;

const normalizeMarkdownLine = (line: string): string => {
  const headingNormalized = line.replace(/^(\s{0,3})(#{1,6})(\S)/, '$1$2 $3');
  const orderedListMatch = headingNormalized.match(/^(\s{0,3})(\d{1,9})([.)])(?!\s)(\S.*)$/);
  if (orderedListMatch) {
    const indent = orderedListMatch[1] ?? '';
    const digits = orderedListMatch[2] ?? '';
    const delimiter = orderedListMatch[3];
    const content = orderedListMatch[4];
    if (!delimiter || !content) {
      return headingNormalized;
    }
    if (!(delimiter === '.' && /^\d/.test(content))) {
      return `${indent}${digits}${delimiter} ${content}`;
    }
  }

  const bulletListMatch = headingNormalized.match(/^(\s{0,3})([-+])(?![-+\s])(\S.*)$/);
  if (bulletListMatch) {
    const indent = bulletListMatch[1] ?? '';
    const marker = bulletListMatch[2];
    const content = bulletListMatch[3];
    if (!marker || !content) {
      return headingNormalized;
    }
    return `${indent}${marker} ${content}`;
  }

  const asteriskListMatch = headingNormalized.match(/^(\s{0,3})\*(?![*\s])(\S.*)$/);
  if (!asteriskListMatch) {
    return headingNormalized;
  }

  const indent = asteriskListMatch[1] ?? '';
  const content = asteriskListMatch[2];
  if (!content) {
    return headingNormalized;
  }
  if (/^[^*\s]+\*(?:\s|$)/.test(content)) {
    return headingNormalized;
  }

  return `${indent}* ${content}`;
};

export const normalizeMarkdownSyntax = (text: string): string => {
  if (!text) {
    return '';
  }

  const lines = text.split(/\r?\n/);
  let activeFence: FenceState | null = null;

  return lines
    .map((line) => {
      const fenceMatch = line.match(fencedCodePattern);
      if (fenceMatch) {
        const marker = fenceMatch[2];
        if (!marker) {
          return line;
        }
        const fenceState = {
          character: marker[0] as FenceState['character'],
          length: marker.length,
        };

        if (!activeFence) {
          activeFence = fenceState;
          return line;
        }

        if (
          activeFence.character === fenceState.character &&
          marker.length >= activeFence.length
        ) {
          activeFence = null;
        }
        return line;
      }

      if (activeFence) {
        return line;
      }

      return normalizeMarkdownLine(line);
    })
    .join('\n');
};

export const prepareCitations = (text: string): CitationPreparationResult => {
  if (!text) {
    return { processedText: '', citations: [] };
  }

  const sourceIndexMap = new Map<string, number>();
  const citations: Citation[] = [];
  let counter = 1;

  let match: RegExpExecArray | null;
  citationPattern.lastIndex = 0;
  while ((match = citationPattern.exec(text)) !== null) {
    const source = (match[1] ?? '').trim();
    if (!source) {
      continue;
    }
    if (!sourceIndexMap.has(source)) {
      sourceIndexMap.set(source, counter);
      citations.push({
        index: counter,
        source,
        section: (match[2] ?? '').trim() || undefined,
      });
      counter += 1;
    }
  }
  citationPattern.lastIndex = 0;

  const processedText = text.replace(citationPattern, (_, src) => {
    const source = (src as string | undefined)?.trim() ?? '';
    if (!source) {
      return '';
    }
    const idx = sourceIndexMap.get(source) ?? 1;
    return `<sup class="cite-num" data-cite="${idx}">[${idx}]</sup>`;
  });

  return { processedText, citations };
};

export const parseCitations = (text: string): ParsedContent => {
  if (!text) {
    return { html: '', citations: [] };
  }

  const { processedText, citations } = prepareCitations(text);
  const rawHtml = marked.parse(normalizeMarkdownSyntax(processedText)) as string;
  const html = DOMPurify.sanitize(rawHtml, {
    ADD_TAGS: ['sup'],
    ADD_ATTR: ['class', 'data-cite'],
  });

  return { html, citations };
};

export const renderMarkdown = (text: string): string => {
  if (!text) {
    return '';
  }
  const rawHtml = marked.parse(normalizeMarkdownSyntax(text)) as string;
  return DOMPurify.sanitize(rawHtml);
};
