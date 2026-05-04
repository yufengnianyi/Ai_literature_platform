import type { MessageSource } from '../types/chat';

const toTrimmedString = (value: unknown): string | undefined => {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value);
  }

  if (typeof value !== 'string') {
    return undefined;
  }

  const trimmed = value.trim();
  return trimmed || undefined;
};

const pickFirstDefined = (...values: Array<string | undefined>): string | undefined =>
  values.find((value) => Boolean(value));

const toExcerpt = (value: unknown): string | undefined => {
  const text = toTrimmedString(value);
  if (!text) {
    return undefined;
  }

  return text.length > 480 ? `${text.slice(0, 477)}...` : text;
};

const normalizeSourceEntry = (value: unknown): MessageSource | null => {
  if (typeof value === 'string') {
    const title = toTrimmedString(value);
    return title ? { title } : null;
  }

  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }

  const record = value as Record<string, unknown>;
  const title = pickFirstDefined(
    toTrimmedString(record.title),
    toTrimmedString(record.source),
    toTrimmedString(record.name),
    toTrimmedString(record.paperId),
    toTrimmedString(record.paper_id),
    toTrimmedString(record.fileName),
    toTrimmedString(record.file_name),
  );

  if (!title) {
    return null;
  }

  const excerpt = pickFirstDefined(
    toExcerpt(record.excerpt),
    toExcerpt(record.quote),
    toExcerpt(record.snippet),
    toExcerpt(record.text),
    toExcerpt(record.content),
    toExcerpt(record.chunkText),
    toExcerpt(record.chunk_text),
  );

  return {
    title,
    section: pickFirstDefined(
      toTrimmedString(record.section),
      toTrimmedString(record.sectionName),
      toTrimmedString(record.section_name),
    ),
    chunk: pickFirstDefined(
      toTrimmedString(record.chunk),
      toTrimmedString(record.chunkId),
      toTrimmedString(record.chunk_id),
    ),
    page: pickFirstDefined(
      toTrimmedString(record.page),
      toTrimmedString(record.pageNumber),
      toTrimmedString(record.page_number),
    ),
    ...(excerpt ? { excerpt } : {}),
  };
};

const buildSourceKey = (source: MessageSource): string =>
  [
    source.title.trim().toLowerCase(),
    source.section ?? '',
    source.chunk ?? '',
    source.page ?? '',
  ].join('||');

export const normalizeSourcesPayload = (payload: unknown): MessageSource[] => {
  if (!Array.isArray(payload)) {
    return [];
  }

  const normalizedSources: MessageSource[] = [];
  const seen = new Set<string>();

  for (const entry of payload) {
    const normalized = normalizeSourceEntry(entry);
    if (!normalized) {
      continue;
    }

    const sourceKey = buildSourceKey(normalized);
    if (seen.has(sourceKey)) {
      continue;
    }

    seen.add(sourceKey);
    normalizedSources.push(normalized);
  }

  return normalizedSources;
};
