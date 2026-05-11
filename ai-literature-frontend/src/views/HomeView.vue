<template>
  <div class="chat-container">
    <div class="chat-messages" ref="messagesContainer">
      <div class="message-column">
        <div v-if="isHistoryLoading" class="loading-state">Loading conversation history...</div>

        <div v-else-if="messages.length === 0" class="empty-state">
          <h3 class="empty-title">Research assistant</h3>
          <p class="empty-description">{{ activeConversationTitle }}</p>
        </div>

        <ChatMessageItem v-for="msg in messages" :key="msg.id" :message="msg" />
      </div>
    </div>

    <div class="composer-dock">
      <ChatComposer
        :disabled="isGenerating || isHistoryLoading || !activeConversationId"
        @send="handleSend"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import ChatMessageItem from '../components/chat/ChatMessageItem.vue';
import ChatComposer from '../components/chat/ChatComposer.vue';
import { useChat } from '../composables/useChat';
import { useConversationState } from '@/composables/useConversationState';

const messagesContainer = ref<HTMLElement | null>(null);
const { activeConversationId, conversations } = useConversationState();

const {
  messages,
  isGenerating,
  isHistoryLoading,
  loadConversationMessages,
  sendMessage,
} = useChat();

const activeConversation = computed(() =>
  conversations.value.find((item) => item.conversationId === activeConversationId.value),
);

const activeConversationTitle = computed(() => {
  if (isHistoryLoading.value) {
    return 'Loading saved messages for the selected conversation.';
  }

  if (activeConversation.value?.title) {
    return activeConversation.value.title;
  }

  return 'Start a new thread or select an existing conversation from the sidebar.';
});

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const handleSend = (text: string, options: { enableThinking: boolean }) => {
  if (!activeConversationId.value) {
    return;
  }
  sendMessage(text, activeConversationId.value, scrollToBottom, options);
};

watch(
  () => activeConversationId.value,
  async (newId, oldId) => {
    if (newId !== oldId) {
      try {
        await loadConversationMessages(newId, scrollToBottom);
      } catch (error) {
        console.error(error);
        message.error('Failed to load conversation history');
      }
    }
  },
  { immediate: true },
);

onMounted(() => {
  scrollToBottom();
});
</script>

<style scoped>
.chat-container {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: #fff;
}

.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 28px 20px 124px;
}

.message-column {
  width: min(100%, 820px);
  margin: 0 auto;
}

.loading-state,
.empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #64748b;
  min-height: calc(100vh - 220px);
}

.empty-title {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
  color: #111827;
}

.empty-description {
  max-width: 420px;
  margin: 0;
  line-height: 1.6;
}

.composer-dock {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 14px 20px 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), #fff 34%);
}

@media (max-width: 960px) {
  .chat-messages {
    padding: 20px 12px 132px;
  }

  .composer-dock {
    padding: 10px 10px 14px;
  }
}
</style>
