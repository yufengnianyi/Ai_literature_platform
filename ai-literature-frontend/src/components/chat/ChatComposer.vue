<template>
  <div class="chat-input-area">
    <div class="input-wrapper" :class="{ 'input-wrapper-disabled': disabled }">
      <a-textarea
        v-model:value="inputText"
        placeholder="有问题，尽管问"
        :auto-size="{ minRows: 1, maxRows: 5 }"
        aria-label="Message"
        class="custom-textarea"
        :disabled="disabled"
        @pressEnter="handlePressEnter"
      />

      <div class="right-tools">
        <a-tooltip title="思考">
          <button
            class="think-toggle"
            :class="{ 'think-toggle-active': enableThinking }"
            :disabled="disabled"
            type="button"
            @click="enableThinking = !enableThinking"
          >
            思考
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

const props = defineProps<{
  disabled?: boolean
}>();

const emit = defineEmits<{
  (e: 'send', text: string, options: { enableThinking: boolean }): void
}>();

const inputText = ref('');
const enableThinking = ref(false);
const sendNudging = ref(false);
let nudgeTimer: ReturnType<typeof window.setTimeout> | null = null;

const triggerEmptyNudge = () => {
  message.warning('不能为空');
  sendNudging.value = false;

  if (nudgeTimer) {
    window.clearTimeout(nudgeTimer);
  }

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

  emit('send', text, { enableThinking: enableThinking.value });
  inputText.value = '';
};

const handlePressEnter = (e: KeyboardEvent) => {
  if (!e.shiftKey) {
    e.preventDefault();
    handleSend();
  }
};
</script>

<style scoped>
.chat-input-area {
  width: min(100%, 700px);
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
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.input-wrapper:focus-within {
  border-color: #d1d5db;
  box-shadow: 0 14px 38px rgba(15, 23, 42, 0.16);
}

.input-wrapper-disabled {
  opacity: 0.72;
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

.custom-textarea :deep(textarea::placeholder) {
  color: #9ca3af;
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
  font-size: 14px;
  cursor: pointer;
}

.think-toggle-active {
  background: #f3f4f6;
  color: #111827;
  font-weight: 600;
}

.think-toggle:disabled {
  cursor: not-allowed;
}

.send-btn,
.send-btn:focus,
.send-btn:focus-visible {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  border: none;
  background: #2563eb !important;
  color: #fff !important;
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.28);
}

.send-btn:hover:not(:disabled) {
  background: #1d4ed8 !important;
  color: #fff !important;
}

.send-btn:active:not(:disabled) {
  background: #1e40af !important;
  color: #fff !important;
}

.send-btn:disabled {
  background: #2563eb !important;
  color: #fff !important;
  opacity: 0.55;
}

.send-btn-nudge {
  animation: sendNudge 0.26s ease;
}

@keyframes sendNudge {
  0%,
  100% {
    transform: translateX(0);
  }

  25% {
    transform: translateX(-2px);
  }

  55% {
    transform: translateX(2px);
  }
}

@media (max-width: 640px) {
  .input-wrapper {
    border-radius: 22px;
  }

  .think-toggle {
    width: 34px;
    padding: 0;
    overflow: hidden;
    white-space: nowrap;
    text-indent: 36px;
    position: relative;
  }

  .think-toggle::before {
    content: '思';
    position: absolute;
    left: 0;
    right: 0;
    text-indent: 0;
  }
}
</style>
