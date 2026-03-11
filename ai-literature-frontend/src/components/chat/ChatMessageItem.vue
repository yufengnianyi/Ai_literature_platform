<template>
  <div 
    class="message-wrapper" 
    :class="message.role === 'user' ? 'user-message' : 'ai-message'"
  >
    <div class="avatar">
      <span v-if="message.role === 'user'">👤</span>
      <span v-else>🤖</span>
    </div>
    <div class="message-content">
      <!-- 用户消息纯文本展示 -->
      <div v-if="message.role === 'user'" class="text-content">
        {{ message.content }}
      </div>
      <!-- AI消息用Markdown渲染 -->
      <div 
        v-else 
        class="markdown-content" 
        v-html="renderMarkdown(message.content)"
      ></div>
      <div v-if="message.isLoading" class="loading-indicator">
        <span class="dot"></span><span class="dot"></span><span class="dot"></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Message } from '../../types/chat';
import { renderMarkdown } from '../../utils/markdown';

defineProps<{
  message: Message
}>();
</script>

<style scoped>
.message-wrapper {
  display: flex;
  margin-bottom: 24px;
  max-width: 85%;
}

.user-message {
  flex-direction: row-reverse;
  margin-left: auto;
}

.ai-message {
  margin-right: auto;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background-color: #f0f2f5;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.user-message .avatar {
  margin-left: 12px;
  background-color: #e6f4ff;
}

.ai-message .avatar {
  margin-right: 12px;
  background-color: #e6fffb;
}

.message-content {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.6;
  font-size: 15px;
  word-break: break-word;
}

.user-message .message-content {
  background-color: #1677ff;
  color: white;
  border-top-right-radius: 4px;
}

.ai-message .message-content {
  background-color: #f5f5f5;
  color: #333;
  border-top-left-radius: 4px;
}

/* Markdown 样式 */
.markdown-content :deep(p:last-child) {
  margin-bottom: 0;
}
.markdown-content :deep(p:first-child) {
  margin-top: 0;
}
.markdown-content :deep(pre) {
  background-color: #282c34;
  color: #abb2bf;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
}
.markdown-content :deep(code) {
  background-color: rgba(0, 0, 0, 0.05);
  padding: 2px 4px;
  border-radius: 4px;
  font-family: source-code-pro, Menlo, Monaco, Consolas, "Courier New", monospace;
}
.markdown-content :deep(pre code) {
  background-color: transparent;
  padding: 0;
}
.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4) {
  margin-top: 1.5em;
  margin-bottom: 0.5em;
  font-weight: 600;
}

/* 加载动画 */
.loading-indicator {
  display: flex;
  align-items: center;
  height: 24px;
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
</style>
