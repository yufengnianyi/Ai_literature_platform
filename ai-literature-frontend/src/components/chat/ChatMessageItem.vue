<template>
  <div
    class="message-wrapper"
    :class="message.role === 'user' ? 'user-message' : 'ai-message'"
  >
    <!-- 头像 -->
    <div class="avatar" :class="message.role === 'user' ? 'avatar-user' : 'avatar-ai'">
      <img v-if="message.role === 'assistant'" :src="aiAvatarUrl" alt="AI" class="avatar-img" />
      <span v-else class="avatar-emoji">👤</span>
    </div>

    <!-- 消息气泡 -->
    <div class="message-content">
      <!-- 用户消息 -->
      <div v-if="message.role === 'user'" class="text-content">
        {{ message.content }}
      </div>

      <!-- AI 消息：正文 -->
      <template v-else>
        <div
          class="markdown-content"
          v-html="parsed.html"
        ></div>

        <!-- 加载动画 -->
        <div v-if="message.isLoading" class="loading-indicator">
          <span class="dot"></span><span class="dot"></span><span class="dot"></span>
        </div>

        <!-- 参考文献列表（ChatGPT 风格） -->
        <div v-if="parsed.citations.length > 0" class="citations-section">
          <div class="citations-divider"></div>
          <div class="citations-header">
            <BookOutlined class="citations-icon" />
            <span>参考文献</span>
          </div>
          <ol class="citations-list">
            <li
              v-for="cite in parsed.citations"
              :key="cite.index"
              class="citation-item"
            >
              <span class="citation-index">{{ cite.index }}.</span>
              <span class="citation-source">{{ cite.source }}</span>
              <span v-if="cite.section" class="citation-section">— {{ cite.section }}</span>
            </li>
          </ol>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { BookOutlined } from '@ant-design/icons-vue';
import type { Message } from '../../types/chat';
import { parseCitations } from '../../utils/markdown';

// 使用 new URL 确保 Vite 正确处理静态资源路径
const aiAvatarUrl = new URL('@/assets/img.png', import.meta.url).href;

const props = defineProps<{
  message: Message;
}>();

const parsed = computed(() => {
  if (props.message.role !== 'assistant') return { html: '', citations: [] };
  return parseCitations(props.message.content);
});
</script>

<style scoped>
.message-wrapper {
  display: flex;
  align-items: flex-start;
  margin-bottom: 24px;
  max-width: 88%;
}

.user-message {
  flex-direction: row-reverse;
  margin-left: auto;
}

.ai-message {
  margin-right: auto;
}

/* 头像 */
.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}

.avatar-ai {
  background-color: #e6fffb;
  margin-right: 10px;
  border: 1px solid #b7eb8f;
}

.avatar-user {
  background-color: #e6f4ff;
  margin-left: 10px;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-emoji {
  font-size: 18px;
  line-height: 1;
}

/* 气泡 */
.message-content {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.7;
  font-size: 15px;
  word-break: break-word;
  min-width: 0;
}

.user-message .message-content {
  background-color: #1677ff;
  color: white;
  border-top-right-radius: 4px;
}

.ai-message .message-content {
  background-color: #f5f5f5;
  color: #1a1a1a;
  border-top-left-radius: 4px;
}

/* Markdown */
.markdown-content :deep(p:last-child) { margin-bottom: 0; }
.markdown-content :deep(p:first-child) { margin-top: 0; }
.markdown-content :deep(pre) {
  background-color: #282c34;
  color: #abb2bf;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
}
.markdown-content :deep(code) {
  background-color: rgba(0, 0, 0, 0.06);
  padding: 2px 5px;
  border-radius: 4px;
  font-family: source-code-pro, Menlo, Monaco, Consolas, 'Courier New', monospace;
  font-size: 13px;
}
.markdown-content :deep(pre code) {
  background-color: transparent;
  padding: 0;
}
.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4) {
  margin-top: 1.4em;
  margin-bottom: 0.4em;
  font-weight: 600;
}
.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  padding-left: 1.5em;
}

/* 引用上标样式 */
.markdown-content :deep(.cite-num) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: #1677ff;
  background-color: #e6f4ff;
  border: 1px solid #91caff;
  border-radius: 4px;
  padding: 0 4px;
  margin: 0 1px;
  vertical-align: super;
  line-height: 1.4;
  cursor: default;
  user-select: none;
  white-space: nowrap;
}

/* 加载动画 */
.loading-indicator {
  display: flex;
  align-items: center;
  height: 24px;
  gap: 2px;
}
.dot {
  width: 6px;
  height: 6px;
  background-color: #8c8c8c;
  border-radius: 50%;
  margin: 0 3px;
  animation: bounce 1.4s infinite ease-in-out both;
}
.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

/* 参考文献区块 */
.citations-section {
  margin-top: 14px;
}

.citations-divider {
  height: 1px;
  background-color: #e0e0e0;
  margin-bottom: 10px;
}

.citations-header {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  color: #595959;
  margin-bottom: 8px;
  letter-spacing: 0.3px;
}

.citations-icon {
  font-size: 13px;
  color: #8c8c8c;
}

.citations-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.citation-item {
  display: flex;
  align-items: baseline;
  gap: 5px;
  font-size: 12px;
  color: #595959;
  line-height: 1.5;
}

.citation-index {
  flex-shrink: 0;
  font-weight: 600;
  color: #1677ff;
  min-width: 18px;
}

.citation-source {
  color: #3c3c3c;
  font-style: italic;
}

.citation-section {
  color: #8c8c8c;
  white-space: nowrap;
}
</style>
