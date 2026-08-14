<template>
  <div class="chat-container">
    <div class="workspace">
      <main class="chat-main">
        <div ref="messagesContainer" class="chat-messages">
          <div class="message-column">
            <div v-if="isHistoryLoading" class="loading-state">
              正在加载会话记录...
            </div>

            <ChatMessageItem v-for="item in messages" :key="item.id" :message="item" />
          </div>
        </div>

        <div class="composer-dock">
          <ChatComposer
            :key="`${activeConversationId || 'draft'}:${draftVersion}`"
            :disabled="isGenerating || isHistoryLoading || isConversationCreating"
            @send="handleSend"
          />
        </div>
      </main>

    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import ChatMessageItem from '@/components/chat/ChatMessageItem.vue';
import ChatComposer from '@/components/chat/ChatComposer.vue';
import { useChat } from '@/composables/useChat';
import { useConversationState } from '@/composables/useConversationState';

const messagesContainer = ref<HTMLElement | null>(null);
const isConversationCreating = ref(false);
const skipHistoryLoadForConversationId = ref('');

const {
  activeConversationId,
  draftMode,
  draftVersion,
  createConversation,
  setActiveConversation,
  markConversationMode,
  refreshConversations,
} = useConversationState();

const {
  messages,
  isGenerating,
  isHistoryLoading,
  loadConversationMessages,
  sendMessage,
  stopGenerating,
} = useChat();

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const refreshConversationTitle = () => {
  window.setTimeout(() => {
    void refreshConversations();
  }, 300);
};

const handleSend = async (text: string, options: { enableThinking: boolean }) => {
  if (isConversationCreating.value) return;
  const mode = draftMode.value;
  let conversationId = activeConversationId.value;
  if (!conversationId) {
    isConversationCreating.value = true;
    try {
      const created = await createConversation(mode, text, false);
      conversationId = created.conversationId;
      skipHistoryLoadForConversationId.value = conversationId;
      setActiveConversation(conversationId);
    } catch (error) {
      console.error(error);
      message.error('创建会话失败');
      return;
    } finally {
      isConversationCreating.value = false;
    }
  }

  markConversationMode(conversationId, mode);
  sendMessage(text, conversationId, scrollToBottom, options, {
    onSubmitted: () => {
      refreshConversationTitle();
    },
  });
};

watch(
  [() => activeConversationId.value, () => draftVersion.value],
  async ([newId, newDraftVersion], [oldId, oldDraftVersion]) => {
    if (newId === oldId && newDraftVersion === oldDraftVersion) return;
    if (newId && skipHistoryLoadForConversationId.value === newId) {
      skipHistoryLoadForConversationId.value = '';
      return;
    }
    try {
      await loadConversationMessages(newId, scrollToBottom);
    } catch (error) {
      console.error(error);
      message.error('加载会话记录失败');
    }
  },
  { immediate: true },
);

onMounted(() => {
  void scrollToBottom();
});

onUnmounted(() => {
  stopGenerating();
});
</script>

<style scoped>
.chat-container {
  position: relative;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: #fff;
}

.workspace {
  display: flex;
  height: 100%;
  min-height: 0;
}

.chat-main {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
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

.loading-state {
  min-height: calc(100vh - 220px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #64748b;
}

.composer-dock {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 14px 20px 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), #fff 34%);
}

@media (max-width: 640px) {
  .chat-messages {
    padding: 20px 12px 154px;
  }

  .composer-dock {
    padding: 10px 10px 14px;
  }

}
</style>
