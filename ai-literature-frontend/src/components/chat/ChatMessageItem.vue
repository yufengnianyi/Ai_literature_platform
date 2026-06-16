<template>
  <div class="message-wrapper" :class="message.role === 'user' ? 'user-message' : 'ai-message'">
    <div class="message-stack">
      <div class="message-content">
        <div v-if="message.role === 'user'" class="text-content">
          <div class="question-text">{{ message.content }}</div>
        </div>

        <template v-else>
          <div
            v-if="
              message.report &&
              !['COMPLETED', 'PARTIAL_COMPLETED'].includes(message.report.status)
            "
            class="report-status-card"
            :class="{ 'report-status-failed': message.report.status === 'FAILED' }"
          >
            <div class="report-status-header">
              <span class="report-status-dot"></span>
              <strong>{{ reportStatusText }}</strong>
            </div>
            <div class="report-status-meta">
              <span v-if="message.report.evidenceCount > 0">
                已匹配 {{ message.report.evidenceCount }} 条证据
              </span>
              <span v-if="message.report.selectedDocumentCount > 0">
                已分析 {{ message.report.analyzedDocumentCount }}/{{
                  message.report.selectedDocumentCount
                }} 篇全文
              </span>
              <span v-if="message.report.progressPercent > 0">
                {{ message.report.progressPercent }}%
              </span>
              <span v-if="message.report.status === 'FAILED'">
                请稍后重试；若问题持续出现，请联系管理员。
              </span>
            </div>
          </div>

          <details v-if="message.thinkingContent" class="thinking-details">
            <summary>{{ message.isLoading ? '思考中' : '已思考' }}</summary>
            <pre class="thinking-content">{{ message.thinkingContent }}</pre>
          </details>

          <div
            v-if="
              !message.report ||
              ['COMPLETED', 'PARTIAL_COMPLETED', 'FAILED'].includes(message.report.status)
            "
            class="markdown-content"
            :class="{
              'plaintext-content': parsed.mode === 'plaintext-fallback',
              'report-markdown': !!message.report,
            }"
            v-html="parsed.html"
            @click="handleCitationClick"
          ></div>

          <div
            v-if="message.report?.warnings?.length"
            class="report-warning-card"
          >
            <strong>报告生成提示</strong>
            <ul>
              <li v-for="warning in message.report.warnings" :key="warning">
                {{ warning }}
              </li>
            </ul>
          </div>

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
                :key="`${cite.index}-${cite.source}`"
                class="citation-item"
                :id="cite.referenceId"
                :title="citationTitle(cite)"
              >
                <span class="citation-index">{{ cite.index }}.</span>
                <div class="citation-body">
                  <span class="citation-source">{{ cite.source }}</span>
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
import type { Citation } from '../../utils/markdown';

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
  if (!(target instanceof Element)) return;

  const citationLink = target.closest('a[data-reference-target]');
  if (!(citationLink instanceof HTMLAnchorElement)) return;

  event.preventDefault();
  const targetId = citationLink.dataset.referenceTarget;
  if (!targetId) return;

  document.getElementById(targetId)?.scrollIntoView({
    behavior: 'smooth',
    block: 'nearest',
  });
};

const citationTitle = (cite: Citation) =>
  [
    cite.source,
    cite.section,
    cite.chunk ? `chunk ${cite.chunk}` : undefined,
    cite.page ? `page ${cite.page}` : undefined,
    cite.excerpt,
  ]
    .filter(Boolean)
    .join(' · ');

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

const reportStatusText = computed(() => {
  if (props.message.report?.phaseMessage) {
    return props.message.report.phaseMessage;
  }
  switch (props.message.report?.status) {
    case 'QUEUED':
      return '报告已进入队列';
    case 'REWRITING':
      return '正在理解问题';
    case 'MATCHING':
      return '正在匹配证据表';
    case 'GENERATING':
      return '正在生成中文证据综述';
    case 'PLANNING':
      return '正在规划报告章节';
    case 'ANALYZING_EVIDENCE':
      return '正在分析结构化证据';
    case 'RETRIEVING_LITERATURE':
      return '正在检索补充文献';
    case 'ANALYZING_LITERATURE':
      return '正在逐篇分析全文';
    case 'SYNTHESIZING':
      return '正在进行跨文献综合';
    case 'VALIDATING':
      return '正在校验引用和数值';
    case 'FAILED':
      return '报告生成失败';
    default:
      return '正在准备报告';
  }
});
</script>

<style scoped>
.message-wrapper {
  display: flex;
  width: 100%;
  margin-bottom: 22px;
}

.user-message {
  justify-content: flex-end;
}

.ai-message {
  justify-content: flex-start;
}

.message-stack {
  min-width: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.message-content {
  min-width: 0;
  padding: 0;
  border: none;
  font-size: 16px;
  line-height: 1.75;
  color: #111827;
  word-break: break-word;
}

.user-message .message-content {
  width: fit-content;
  max-width: min(100%, 38rem);
  margin-left: auto;
  padding: 11px 15px;
  border-radius: 18px;
  background: #f3f4f6;
}

.ai-message .message-content {
  width: 100%;
  background: #fff;
}

.text-content,
.question-text {
  white-space: pre-wrap;
}

.report-status-card {
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid #bfdbfe;
  border-radius: 14px;
  background: #eff6ff;
  color: #1e3a8a;
}

.report-status-failed {
  border-color: #fecaca;
  background: #fef2f2;
  color: #991b1b;
}

.report-status-header {
  display: flex;
  align-items: center;
  gap: 9px;
}

.report-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  animation: reportPulse 1.4s ease-in-out infinite;
}

.report-status-meta {
  display: flex;
  gap: 12px;
  margin-top: 6px;
  color: inherit;
  opacity: 0.76;
  font-size: 13px;
}

.report-warning-card {
  margin: 14px 0;
  padding: 12px 14px;
  border: 1px solid #fde68a;
  border-radius: 12px;
  background: #fffbeb;
  color: #92400e;
  font-size: 13px;
}

.report-warning-card ul {
  margin: 6px 0 0;
  padding-left: 18px;
}

@keyframes reportPulse {
  50% { opacity: 0.35; transform: scale(0.82); }
}

.thinking-details {
  margin: 0 0 12px;
  color: #6b7280;
  font-size: 14px;
}

.thinking-details summary {
  width: fit-content;
  cursor: pointer;
  list-style: none;
  user-select: none;
}

.thinking-details summary::-webkit-details-marker {
  display: none;
}

.thinking-details summary::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 8px;
  border-radius: 50%;
  background: #9ca3af;
  vertical-align: 1px;
}

.thinking-content {
  max-height: 240px;
  margin: 10px 0 0;
  padding: 10px 12px;
  border-left: 2px solid #e5e7eb;
  background: #f9fafb;
  color: #4b5563;
  white-space: pre-wrap;
  overflow: auto;
  font-size: 13px;
  line-height: 1.6;
}

.report-markdown :deep(p) {
  text-indent: 2em;
}

.report-markdown :deep(li p) {
  text-indent: 0;
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
  border-radius: 8px;
  background: #0f172a;
  color: #e5e7eb;
  overflow-x: auto;
}

.markdown-content :deep(code) {
  padding: 2px 5px;
  border-radius: 6px;
  background: #f3f4f6;
  font-family: 'Cascadia Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
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
  color: #111827;
  font-weight: 650;
  line-height: 1.3;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  padding-left: 1.5em;
}

.markdown-content :deep(blockquote) {
  margin: 12px 0;
  padding-left: 12px;
  border-left: 3px solid #d1d5db;
  color: #4b5563;
}

.markdown-content :deep(table) {
  display: block;
  width: 100%;
  max-width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
  overflow-x: auto;
}

.markdown-content :deep(th),
.markdown-content :deep(td) {
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  text-align: left;
  vertical-align: top;
  font-size: 15px;
}

.markdown-content :deep(th) {
  background: #f9fafb;
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
  border: 1px solid #d1d5db;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #f9fafb;
  color: #111827;
  text-decoration: none;
  font-weight: 800;
}

.markdown-content :deep(.citation-link:hover) {
  border-color: #111827;
  background: #111827;
  color: #fff;
  text-decoration: none;
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
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}

.citations-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: #374151;
}

.citations-count {
  min-width: 20px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #f3f4f6;
  color: #374151;
  font-size: 11px;
  line-height: 1;
}

.citations-list {
  list-style: none;
  padding: 0;
  margin: 10px 0 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.citation-item {
  position: relative;
  padding: 8px 0 8px 28px;
  font-size: 13px;
  color: #4b5563;
  line-height: 1.6;
  scroll-margin-top: 24px;
}

.citation-item:target {
  background: #f9fafb;
}

.citation-index {
  position: absolute;
  left: 0;
  top: 9px;
  width: 18px;
  height: 18px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #111827;
  color: #fff;
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
</style>
