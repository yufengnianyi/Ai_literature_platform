<template>
  <div class="chat-container">
    <div class="workspace">
      <main class="chat-main">
        <div ref="messagesContainer" class="chat-messages">
          <div class="message-column">
            <div v-if="isHistoryLoading" class="loading-state">
              正在加载会话记录...
            </div>

            <div v-else-if="messages.length === 0" class="empty-state">
              <h3 class="empty-title">
                {{ activeMode === 'REPORT' ? '证据综述' : '科研助手' }}
              </h3>
              <p class="empty-description">{{ activeConversationTitle }}</p>
            </div>

            <ChatMessageItem v-for="item in messages" :key="item.id" :message="item" />
          </div>
        </div>

        <div class="composer-dock">
          <ChatComposer
            :key="`${activeConversationId || 'draft'}:${draftVersion}`"
            :disabled="isGenerating || isHistoryLoading || isConversationCreating"
            :mode="activeMode"
            @send="handleSend"
            @mode-change="handleModeChange"
          />
        </div>
      </main>

    </div>

    <a-button
      v-if="activeConversationId && (activeMode === 'REPORT' || reportRuns.length > 0)"
      class="attachment-button"
      @click="attachmentDrawerOpen = true"
    >
      <template #icon><PaperClipOutlined /></template>
      报告附件（{{ reportRuns.length }}）
    </a-button>

    <a-drawer
      v-model:open="attachmentDrawerOpen"
      title="报告附件"
      placement="right"
      width="min(380px, 100vw)"
    >
      <ReportAttachmentPanel :runs="reportRuns" :loading="attachmentsLoading" />
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import { PaperClipOutlined } from '@ant-design/icons-vue';
import ChatMessageItem from '@/components/chat/ChatMessageItem.vue';
import ChatComposer from '@/components/chat/ChatComposer.vue';
import ReportAttachmentPanel from '@/components/report/ReportAttachmentPanel.vue';
import { useChat } from '@/composables/useChat';
import { useConversationState } from '@/composables/useConversationState';
import { reportService, type ReportRun } from '@/services/report';
import type { ConversationMode } from '@/types/conversation';

const messagesContainer = ref<HTMLElement | null>(null);
const reportRuns = ref<ReportRun[]>([]);
const attachmentsLoading = ref(false);
const attachmentDrawerOpen = ref(false);
const isConversationCreating = ref(false);
const skipHistoryLoadForConversationId = ref('');

const {
  activeConversationId,
  conversations,
  draftMode,
  draftVersion,
  createConversation,
  setActiveConversation,
  setDraftMode,
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

const activeMode = computed<ConversationMode>(() => draftMode.value);
const activeConversation = computed(() =>
  conversations.value.find((item) => item.conversationId === activeConversationId.value),
);

const activeConversationTitle = computed(() => {
  if (isHistoryLoading.value) return 'Loading saved messages for the selected conversation.';
  if (activeConversation.value?.title) return activeConversation.value.title;
  return activeMode.value === 'REPORT'
    ? '提出问题后将创建会话，并基于证据表和相关文献生成中文综述。'
    : '提出第一个问题后才会创建会话。';
});

const scrollToBottom = async () => {
  await nextTick();
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  }
};

const upsertReportRun = (run: ReportRun) => {
  if (run.conversationId !== activeConversationId.value) return;
  reportRuns.value = [
    run,
    ...reportRuns.value.filter((item) => item.reportId !== run.reportId),
  ].sort((left, right) =>
    new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime(),
  );
};

const loadReportRuns = async (conversationId: string) => {
  if (!conversationId) {
    reportRuns.value = [];
    return;
  }
  attachmentsLoading.value = true;
  try {
    reportRuns.value = await reportService.listByConversation(conversationId);
  } catch (error) {
    console.error(error);
    reportRuns.value = [];
    message.error('加载报告附件失败');
  } finally {
    attachmentsLoading.value = false;
  }
};

const refreshConversationTitle = () => {
  window.setTimeout(() => {
    void refreshConversations();
  }, 300);
};

const handleSend = async (text: string, options: { enableThinking: boolean }) => {
  if (isConversationCreating.value) return;
  const mode = activeMode.value;
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
  sendMessage(text, conversationId, scrollToBottom, options, mode, {
    onSubmitted: () => {
      refreshConversationTitle();
    },
    onReportUpdated: upsertReportRun,
  });
};

const handleModeChange = (mode: ConversationMode) => {
  if (mode === activeMode.value || isGenerating.value) return;
  setDraftMode(mode);
};

watch(
  [() => activeConversationId.value, () => draftVersion.value],
  async ([newId, newDraftVersion], [oldId, oldDraftVersion]) => {
    if (newId === oldId && newDraftVersion === oldDraftVersion) return;
    attachmentDrawerOpen.value = false;
    if (newId && skipHistoryLoadForConversationId.value === newId) {
      skipHistoryLoadForConversationId.value = '';
      reportRuns.value = [];
      return;
    }
    try {
      await loadConversationMessages(newId, scrollToBottom, upsertReportRun);
      await loadReportRuns(newId);
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

.loading-state,
.empty-state {
  min-height: calc(100vh - 220px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #64748b;
}

.empty-title {
  margin: 0 0 8px;
  color: #111827;
  font-size: 22px;
  font-weight: 600;
}

.empty-description {
  max-width: 460px;
  margin: 0;
  line-height: 1.6;
}

.composer-dock {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 14px 20px 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), #fff 34%);
}

.attachment-button {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 3;
  display: inline-flex;
  align-items: center;
  border-color: #bfdbfe;
  color: #1d4ed8;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 6px 18px rgba(37, 99, 235, 0.12);
  backdrop-filter: blur(10px);
}

@media (max-width: 640px) {
  .chat-messages {
    padding: 20px 12px 154px;
  }

  .composer-dock {
    padding: 10px 10px 14px;
  }

  .attachment-button {
    top: 10px;
    right: 10px;
  }
}
</style>
