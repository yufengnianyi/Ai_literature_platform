<template>
  <div class="chat-input-area">
    <div class="input-wrapper" :class="{ 'input-wrapper-disabled': disabled }">
      <a-textarea
        v-model:value="inputText"
        placeholder="Ask about a paper, compare findings, or request a concise synthesis..."
        :auto-size="{ minRows: 1, maxRows: 6 }"
        aria-label="Message"
        @pressEnter="handlePressEnter"
        class="custom-textarea"
        :disabled="disabled"
      />
      <a-button
        type="primary"
        class="send-btn"
        :disabled="disabled"
        @click="handleSend"
      >
        <template #icon><SendOutlined /></template>
        Send
      </a-button>
    </div>
    <div class="composer-options">
      <a-switch
        v-model:checked="deepThinking"
        size="small"
        :disabled="disabled"
      />
      <span>深度思考</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { SendOutlined } from '@ant-design/icons-vue';

const props = defineProps<{
  disabled?: boolean
}>();

const emit = defineEmits<{
  (e: 'send', text: string, options: { deepThinking: boolean }): void
}>();

const inputText = ref('');
const deepThinking = ref(false);

const handleSend = () => {
  const text = inputText.value.trim();
  if (!text || props.disabled) {
    return;
  }

  emit('send', text, { deepThinking: deepThinking.value });
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
  position: sticky;
  bottom: 0;
  z-index: 2;
  flex-shrink: 0;
  border: 1px solid #dbe7f5;
  border-radius: 16px;
  background: #fff;
  padding: 12px 16px;
  box-shadow: 0 -1px 0 rgba(219, 231, 245, 0.7);
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: flex-end;
  border: 1px solid #dbe7f5;
  border-radius: 14px;
  background: #fff;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.input-wrapper:focus-within {
  border-color: #93c5fd;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.08);
}

.input-wrapper-disabled {
  opacity: 0.72;
}

.composer-options {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  color: #475569;
  font-size: 12px;
  font-weight: 600;
}

.custom-textarea {
  flex: 1;
  border: none !important;
  box-shadow: none !important;
  background: transparent !important;
  resize: none;
  padding: 12px 16px;
  padding-right: 112px;
}

.custom-textarea :deep(textarea) {
  background: transparent;
  color: #111827;
  font-size: 14px;
  line-height: 1.6;
}

.custom-textarea :deep(textarea::placeholder) {
  color: #94a3b8;
}

.send-btn {
  position: absolute;
  right: 8px;
  bottom: 8px;
  height: 36px;
  border-radius: 10px;
  padding: 0 14px;
  border: none;
  background: #2563eb;
}

.send-btn:hover:not(:disabled) {
  background: #1d4ed8;
}

@media (max-width: 720px) {
  .chat-input-area {
    padding: 10px 12px;
  }
}
</style>
