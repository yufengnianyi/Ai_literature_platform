<template>
  <div class="chat-input-area">
    <div class="input-wrapper">
      <a-textarea
        v-model:value="inputText"
        placeholder="给 AI 发送消息..."
        :auto-size="{ minRows: 1, maxRows: 6 }"
        @pressEnter="handlePressEnter"
        class="custom-textarea"
      />
      <a-button 
        type="primary" 
        shape="circle" 
        class="send-btn"
        :disabled="!inputText.trim() || disabled"
        @click="handleSend"
      >
        <template #icon><SendOutlined /></template>
      </a-button>
    </div>
    <div class="footer-tip">内容由 AI 生成，请注意甄别。</div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { SendOutlined } from '@ant-design/icons-vue';

const props = defineProps<{
  disabled?: boolean
}>();

const emit = defineEmits<{
  (e: 'send', text: string): void
}>();

const inputText = ref('');

const handleSend = () => {
  const text = inputText.value.trim();
  if (!text || props.disabled) return;
  
  emit('send', text);
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
  padding: 16px 24px;
  background-color: #fff;
  border-top: 1px solid #f0f0f0;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: flex-end;
  background-color: #f5f5f5;
  border-radius: 24px;
  padding: 4px;
  border: 1px solid #e8e8e8;
  transition: all 0.3s;
}

.input-wrapper:focus-within {
  border-color: #1677ff;
  background-color: #fff;
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.1);
}

.custom-textarea {
  flex: 1;
  border: none !important;
  box-shadow: none !important;
  background-color: transparent !important;
  resize: none;
  padding: 8px 16px;
  padding-right: 50px; /* 给按钮留空间 */
}

.custom-textarea :deep(textarea) {
  background-color: transparent;
}

.send-btn {
  position: absolute;
  right: 8px;
  bottom: 8px;
}

.footer-tip {
  text-align: center;
  font-size: 12px;
  color: #bfbfbf;
  margin-top: 8px;
}
</style>
