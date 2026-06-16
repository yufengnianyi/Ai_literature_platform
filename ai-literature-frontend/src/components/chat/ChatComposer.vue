<template>
  <div class="chat-input-area">
    <div class="input-wrapper" :class="{ 'input-wrapper-disabled': disabled }">
      <div class="mode-switch" aria-label="Conversation mode">
        <button
          v-for="option in modeOptions"
          :key="option"
          type="button"
          class="mode-button"
          :class="{ 'mode-button-active': mode === option }"
          :disabled="disabled || mode === option"
          @click="emit('mode-change', option)"
        >
          {{ option === 'CHAT' ? 'Chat' : 'Report' }}
        </button>
      </div>

      <a-textarea
        v-model:value="inputText"
        :placeholder="mode === 'REPORT' ? 'Ask for an evidence report' : 'Ask a question'"
        :auto-size="{ minRows: 1, maxRows: 5 }"
        aria-label="Message"
        class="custom-textarea"
        :disabled="disabled"
        @pressEnter="handlePressEnter"
      />

      <div class="right-tools">
        <a-tooltip v-if="mode === 'CHAT'" title="Thinking">
          <button
            class="think-toggle"
            :class="{ 'think-toggle-active': enableThinking }"
            :disabled="disabled"
            type="button"
            @click="enableThinking = !enableThinking"
          >
            Think
          </button>
        </a-tooltip>
        <a-button
          type="primary"
          shape="circle"
          class="send-btn"
          :class="{ 'send-btn-nudge': sendNudging }"
          :disabled="disabled"
          @click="handleSend"
        >
          <template #icon><SendOutlined /></template>
        </a-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { message } from 'ant-design-vue';
import { SendOutlined } from '@ant-design/icons-vue';
import type { ConversationMode } from '@/types/conversation';

const modeOptions: ConversationMode[] = ['CHAT', 'REPORT'];
const props = defineProps<{
  disabled?: boolean;
  mode: ConversationMode;
}>();

const emit = defineEmits<{
  (event: 'send', text: string, options: { enableThinking: boolean }): void;
  (event: 'mode-change', mode: ConversationMode): void;
}>();

const inputText = ref('');
const enableThinking = ref(false);
const sendNudging = ref(false);
let nudgeTimer: ReturnType<typeof window.setTimeout> | null = null;

const triggerEmptyNudge = () => {
  message.warning('Message cannot be empty');
  sendNudging.value = false;
  if (nudgeTimer) window.clearTimeout(nudgeTimer);
  window.requestAnimationFrame(() => {
    sendNudging.value = true;
    nudgeTimer = window.setTimeout(() => {
      sendNudging.value = false;
      nudgeTimer = null;
    }, 260);
  });
};

const handleSend = () => {
  if (props.disabled) return;
  const text = inputText.value.trim();
  if (!text) {
    triggerEmptyNudge();
    return;
  }
  emit('send', text, { enableThinking: props.mode === 'CHAT' && enableThinking.value });
  inputText.value = '';
};

const handlePressEnter = (event: KeyboardEvent) => {
  if (!event.shiftKey) {
    event.preventDefault();
    handleSend();
  }
};
</script>

<style scoped>
.chat-input-area {
  width: min(100%, 780px);
  margin: 0 auto;
}

.input-wrapper {
  min-height: 58px;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 28px;
  background: #fff;
  box-shadow: 0 12px 34px rgba(15, 23, 42, 0.12);
}

.input-wrapper:focus-within {
  border-color: #d1d5db;
  box-shadow: 0 14px 38px rgba(15, 23, 42, 0.16);
}

.input-wrapper-disabled {
  opacity: 0.72;
}

.mode-switch {
  flex: 0 0 auto;
  display: flex;
  padding: 3px;
  border-radius: 999px;
  background: #f1f5f9;
}

.mode-button {
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.mode-button-active {
  background: #fff;
  color: #1d4ed8;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
}

.mode-button:disabled {
  cursor: default;
}

.custom-textarea {
  flex: 1;
  min-width: 0;
  border: none !important;
  box-shadow: none !important;
  background: transparent !important;
  resize: none;
  padding: 8px 2px 7px;
}

.custom-textarea :deep(textarea) {
  background: transparent;
  color: #111827;
  font-size: 16px;
  line-height: 1.6;
}

.right-tools {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.think-toggle {
  height: 34px;
  padding: 0 10px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
}

.think-toggle-active {
  background: #f3f4f6;
  color: #111827;
  font-weight: 600;
}

.send-btn,
.send-btn:focus {
  width: 38px;
  height: 38px;
  border: none;
  background: #2563eb !important;
  color: #fff !important;
}

.send-btn-nudge {
  animation: sendNudge 0.26s ease;
}

@keyframes sendNudge {
  25% { transform: translateX(-2px); }
  55% { transform: translateX(2px); }
}

@media (max-width: 640px) {
  .input-wrapper {
    flex-wrap: wrap;
    border-radius: 22px;
  }

  .mode-switch {
    order: 1;
  }

  .custom-textarea {
    order: 3;
    flex-basis: calc(100% - 52px);
  }

  .right-tools {
    order: 4;
  }
}
</style>
