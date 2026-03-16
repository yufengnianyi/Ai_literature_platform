<template>
  <div class="chat-container">
    <div class="chat-messages" ref="messagesContainer">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-icon">AI</div>
        <h3>Hello, I am your AI assistant</h3>
        <p>Please select a conversation on the left and start asking questions.</p>
      </div>

      <ChatMessageItem
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
      />
    </div>

    <ChatComposer
      :disabled="isGenerating || !activeConversationId"
      @send="handleSend"
    />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue';
import ChatMessageItem from '../components/chat/ChatMessageItem.vue';
import ChatComposer from '../components/chat/ChatComposer.vue';
import { useChat } from '../composables/useChat';
import { useConversationState } from '@/composables/useConversationState';

const messagesContainer = ref<HTMLElement | null>(null);
const { activeConversationId } = useConversationState();

const {
  messages,
  isGenerating,
  sendMessage,
  clearMessages,
  stopGenerating,
} = useChat();

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const handleSend = (text: string) => {
  if (!activeConversationId.value) {
    return;
  }
  sendMessage(text, activeConversationId.value, scrollToBottom);
};

watch(
  () => activeConversationId.value,
  (newId, oldId) => {
    if (newId !== oldId) {
      stopGenerating();
      clearMessages();
      scrollToBottom();
    }
  },
);

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
  font-size: 32px;
  margin-bottom: 16px;
}
</style>
