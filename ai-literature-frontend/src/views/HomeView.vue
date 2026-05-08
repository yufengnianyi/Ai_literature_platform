<template>
  <div class="chat-container">
    <div class="chat-stage">
      <div class="chat-stage-header">
        <div class="chat-stage-copy">
          <div class="history-kicker">
            <span class="history-dot" :class="{ 'history-dot-loading': isHistoryLoading }"></span>
            {{ historyStatusText }}
          </div>
          <h2 class="chat-stage-title">Research assistant</h2>
          <p class="chat-stage-subtitle">
            {{ activeConversationTitle }}
          </p>
        </div>
        <div class="chat-stage-meta">
          {{ isHistoryLoading ? 'Loading...' : `${messages.length} message${messages.length === 1 ? '' : 's'}` }}
        </div>
      </div>

      <div class="chat-messages" ref="messagesContainer">
        <div v-if="isHistoryLoading" class="loading-state">
          Loading conversation history...
        </div>

        <div v-else-if="messages.length === 0" class="empty-state">
          <h3 class="empty-title">Start a conversation</h3>
          <p class="empty-description">
            Ask for a summary, compare sources, or continue an existing research thread.
          </p>
        </div>

        <ChatMessageItem
          v-for="msg in messages"
          :key="msg.id"
          :message="msg"
        />
      </div>
    </div>

    <ChatComposer
      :disabled="isGenerating || isHistoryLoading || !activeConversationId"
      @send="handleSend"
    />
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

const historyStatusText = computed(() => {
  if (isHistoryLoading.value) {
    return 'Loading history';
  }

  if (activeConversationId.value && messages.value.length > 0) {
    return 'History loaded';
  }

  if (activeConversationId.value) {
    return 'Conversation ready';
  }

  return 'No active conversation';
});

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const handleSend = (text: string, options: { deepThinking: boolean }) => {
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
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  gap: 12px;
  padding: 16px;
  overflow: hidden;
}

.chat-stage {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #dbe7f5;
  border-radius: 16px;
  background: #fff;
  overflow: hidden;
}

.chat-stage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid #eff6ff;
  background: #f8fbff;
}

.chat-stage-copy {
  min-width: 0;
}

.history-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: #2563eb;
}

.history-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #2563eb;
  box-shadow: 0 0 0 5px rgba(37, 99, 235, 0.12);
}

.history-dot-loading {
  animation: historyPulse 1.2s ease-in-out infinite;
}

.chat-stage-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #111827;
}

.chat-stage-subtitle {
  margin: 6px 0 0;
  max-width: 640px;
  font-size: 13px;
  line-height: 1.6;
  color: #64748b;
}

.chat-stage-meta {
  flex-shrink: 0;
  font-size: 13px;
  color: #64748b;
}

.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
  background: #fff;
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

@keyframes historyPulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 1;
  }

  50% {
    transform: scale(0.78);
    opacity: 0.45;
  }
}

@media (max-width: 960px) {
  .chat-container {
    padding: 12px;
  }

  .chat-stage-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
