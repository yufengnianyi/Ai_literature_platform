<template>
  <div
    class="message-wrapper"
    :class="message.role === 'user' ? 'user-message' : 'ai-message'"
  >
    <div class="message-stack">
      <div class="message-content">
        <div v-if="message.role === 'user'" class="text-content">
          <div class="question-text">{{ message.content }}</div>
        </div>

        <template v-else>
          <div
            class="markdown-content"
            :class="{ 'plaintext-content': parsed.mode === 'plaintext-fallback' }"
            v-html="parsed.html"
            @click="handleCitationClick"
          ></div>

          <div v-if="parsed.pendingTail" class="pending-tail">
            {{ parsed.pendingTail }}
          </div>

          <div v-if="message.isLoading" class="loading-indicator">
            <span class="dot"></span><span class="dot"></span><span class="dot"></span>
          </div>

          <div v-if="parsed.citations.length > 0" class="citations-section">
            <div class="citations-header">
              <span>引用来源</span>
              <span class="citations-count">{{ parsed.citations.length }}</span>
            </div>
            <ol class="citations-list">
              <li
                v-for="cite in parsed.citations"
                :key="`${cite.index}-${cite.source}-${cite.chunk ?? ''}-${cite.page ?? ''}-${cite.excerpt ?? ''}`"
                class="citation-item"
                :id="cite.referenceId"
              >
                <span class="citation-index">{{ cite.index }}.</span>
                <div class="citation-body">
                  <span class="citation-source">{{ cite.source }}</span>
                  <span
                    v-if="cite.section || cite.chunk || cite.page"
                    class="citation-meta"
                  >
                    <span v-if="cite.section">{{ cite.section }}</span>
                    <span v-if="cite.chunk">chunk {{ cite.chunk }}</span>
                    <span v-if="cite.page">page {{ cite.page }}</span>
                  </span>
                  <p v-if="cite.excerpt" class="citation-excerpt">
                    {{ cite.excerpt }}
                  </p>
                </div>
              </li>
            </ol>
          </div>

        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import type { Message } from '../../types/chat';
import { parseAIResponse } from '../../utils/markdown';

const props = defineProps<{
  message: Message;
}>();

const referenceScope = computed(() => {
  const normalizedId = props.message.id
    .trim()
    .replace(/[^a-zA-Z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();

  return normalizedId ? `message-${normalizedId}` : 'message-assistant';
});

const handleCitationClick = (event: MouseEvent) => {
  const target = event.target;
  if (!(target instanceof Element)) {
    return;
  }

  const citationLink = target.closest('a[data-reference-target]');
  if (!(citationLink instanceof HTMLAnchorElement)) {
    return;
  }

  event.preventDefault();
  const targetId = citationLink.dataset.referenceTarget;
  if (!targetId) {
    return;
  }

  document.getElementById(targetId)?.scrollIntoView({
    behavior: 'smooth',
    block: 'nearest',
  });
};

const parsed = computed(() => {
  if (props.message.role !== 'assistant') {
    return {
      html: '',
      citations: [],
      pendingTail: '',
      mode: 'markdown' as const,
    };
  }

  return parseAIResponse(props.message.rawContent ?? props.message.content, {
    final: !props.message.isLoading,
    stableContent: props.message.stableContent,
    pendingTail: props.message.pendingTail,
    referenceScope: referenceScope.value,
    sources: props.message.sources,
  });
});
</script>

<style scoped>
.message-wrapper {
  display: flex;
  margin-bottom: 24px;
  width: 100%;
}

.user-message {
  justify-content: flex-end;
  margin-left: auto;
}

.ai-message {
  justify-content: flex-start;
  margin-right: auto;
}

.message-stack {
  min-width: 0;
  display: flex;
  flex-direction: column;
  width: fit-content;
  max-width: min(92%, 920px);
}

.citations-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #334155;
}

.citations-count {
  min-width: 20px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #e0f2fe;
  color: #0369a1;
  font-size: 11px;
  line-height: 1;
}

.message-content {
  min-width: 0;
  padding: 14px 16px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  border: 1px solid #dbe7f5;
}

.user-message .message-content {
  width: fit-content;
  max-width: min(100%, 42rem);
  background: #2563eb;
  border-color: #2563eb;
  color: #ffffff;
}

.ai-message .message-content {
  width: min(100%, 920px);
  background: #ffffff;
  color: #111827;
}

.text-content {
  white-space: pre-wrap;
}

.question-text {
  white-space: pre-wrap;
}

.markdown-content :deep(p:first-child) {
  margin-top: 0;
}

.markdown-content :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-content :deep(pre) {
  margin: 12px 0;
  padding: 12px;
  border-radius: 10px;
  background: #0f172a;
  color: #e5e7eb;
  overflow-x: auto;
}

.markdown-content :deep(code) {
  padding: 2px 5px;
  border-radius: 6px;
  background: #f3f4f6;
  font-family: 'Cascadia Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}

.markdown-content :deep(pre code) {
  padding: 0;
  background: transparent;
}

.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4) {
  margin-top: 1.25em;
  margin-bottom: 0.4em;
  font-weight: 600;
  color: #111827;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  padding-left: 1.5em;
}

.markdown-content :deep(blockquote) {
  margin: 12px 0;
  padding-left: 12px;
  border-left: 3px solid #bfdbfe;
  color: #475569;
}

.markdown-content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.markdown-content :deep(th),
.markdown-content :deep(td) {
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  text-align: left;
  vertical-align: top;
}

.markdown-content :deep(.citation-marker) {
  margin-left: 3px;
  margin-right: 1px;
  font-size: 0.68em;
  vertical-align: super;
  line-height: 0;
}

.markdown-content :deep(.citation-link) {
  min-width: 1.35em;
  height: 1.35em;
  padding: 0 0.34em;
  border: 1px solid #93c5fd;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #eff6ff 0%, #dbeafe 100%);
  color: #1d4ed8;
  text-decoration: none;
  font-weight: 800;
  box-shadow: 0 1px 3px rgba(37, 99, 235, 0.18);
  transform: translateY(-0.05em);
  transition: background-color 0.16s ease, border-color 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.markdown-content :deep(.citation-link:hover) {
  border-color: #2563eb;
  background: #2563eb;
  color: #ffffff;
  text-decoration: none;
  transform: translateY(-0.15em);
}

.plaintext-content {
  white-space: normal;
}

.pending-tail {
  margin-top: 8px;
  color: #64748b;
  white-space: pre-wrap;
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 20px;
  margin-top: 4px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #94a3b8;
  animation: bounce 1.4s infinite ease-in-out both;
}

.dot:nth-child(1) {
  animation-delay: -0.32s;
}

.dot:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes bounce {
  0%,
  80%,
  100% {
    transform: scale(0);
  }

  40% {
    transform: scale(1);
  }
}

.citations-section {
  margin-top: 16px;
  padding: 12px;
  border: 1px solid #dbeafe;
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgba(239, 246, 255, 0.92), rgba(255, 255, 255, 0.78)),
    radial-gradient(circle at top right, rgba(14, 165, 233, 0.14), transparent 38%);
}

.citations-list {
  list-style: none;
  padding: 0;
  margin: 10px 0 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.citation-item {
  position: relative;
  padding: 10px 10px 10px 36px;
  border: 1px solid rgba(191, 219, 254, 0.75);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.78);
  font-size: 12px;
  color: #475569;
  line-height: 1.6;
  scroll-margin-top: 24px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.citation-item:target {
  border-color: #2563eb;
  box-shadow: 0 10px 26px rgba(37, 99, 235, 0.16);
  transform: translateY(-1px);
}

.citation-index {
  position: absolute;
  left: 10px;
  top: 10px;
  width: 18px;
  height: 18px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #2563eb;
  color: #ffffff;
  font-size: 11px;
  font-weight: 800;
}

.citation-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.citation-source {
  color: #111827;
  font-weight: 700;
}

.citation-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  color: #64748b;
}

.citation-meta span {
  padding: 1px 7px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #475569;
}

.citation-excerpt {
  margin: 6px 0 0;
  padding: 8px 10px;
  border-left: 3px solid #38bdf8;
  border-radius: 8px;
  background: #f8fafc;
  color: #334155;
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

@media (max-width: 960px) {
  .message-wrapper {
    max-width: 100%;
  }

}
</style>
