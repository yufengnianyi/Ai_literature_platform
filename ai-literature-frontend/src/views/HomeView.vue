<template>
  <div class="chat-container">
    <!-- 聊天内容区域 -->
    <div class="chat-messages" ref="messagesContainer">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-icon">🤖</div>
        <h3>你好，我是 AI 助手</h3>
        <p>你可以问我任何问题，例如：“你能帮我写一段快排代码吗？”</p>
      </div>
      
      <ChatMessageItem 
        v-for="msg in messages" 
        :key="msg.id" 
        :message="msg"
      />
    </div>

    <!-- 底部输入区域 -->
    <ChatComposer 
      :disabled="isGenerating"
      @send="handleSend"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue';
import ChatMessageItem from '../components/chat/ChatMessageItem.vue';
import ChatComposer from '../components/chat/ChatComposer.vue';
import { useChat } from '../composables/useChat';

const messagesContainer = ref<HTMLElement | null>(null);

// 提取的 chat 逻辑 Hook
const { 
  messages, 
  isGenerating, 
  sendMessage 
} = useChat();

// 滚动到底部的辅助函数
const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const handleSend = (text: string) => {
  // 目前硬编码 memoryId = 1，实际可以从路由或 store 获取
  sendMessage(text, 1, scrollToBottom);
};

onMounted(() => {
  scrollToBottom();
});
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: #fff;
  position: relative;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  scroll-behavior: smooth;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #8c8c8c;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
}
</style>
