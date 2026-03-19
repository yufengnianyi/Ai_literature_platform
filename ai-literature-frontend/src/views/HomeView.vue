<template>
  <div class="chat-container">
    <div class="chat-stage">
      <div class="chat-stage-header">
        <div>
          <h2 class="chat-stage-title">Research assistant</h2>
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
import { nextTick, onMounted, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import ChatMessageItem from '../components/chat/ChatMessageItem.vue';
import ChatComposer from '../components/chat/ChatComposer.vue';
import { useChat } from '../composables/useChat';
import { useConversationState } from '@/composables/useConversationState';

const messagesContainer = ref<HTMLElement | null>(null);
const { activeConversationId } = useConversationState();

const {
  messages,
  isGenerating,
  isHistoryLoading,
  loadConversationMessages,
  sendMessage,
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

.chat-stage-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #111827;
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
