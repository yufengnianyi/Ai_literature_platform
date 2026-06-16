import { ref } from 'vue';

import type { ConversationHistoryMessage, Message } from '@/types/chat';
import type { ConversationMode } from '@/types/conversation';
import { splitMarkdownStream } from '@/utils/markdown';
import { chatService, type ChatStreamHandle } from '@/services/chat';
import { conversationService } from '@/services/conversation';
import { reportService, type ReportRun } from '@/services/report';

const syncAssistantMessageState = (message: Message, final: boolean) => {
  const rawContent = message.rawContent ?? message.content ?? '';
  message.content = rawContent;
  message.rawContent = rawContent;
  if (final) {
    message.stableContent = rawContent;
    message.pendingTail = '';
    return;
  }
  const { stableContent, pendingTail } = splitMarkdownStream(rawContent);
  message.stableContent = stableContent;
  message.pendingTail = pendingTail;
};

const updateAssistantMessage = (
  messages: Message[],
  aiMessageId: string,
  updater: (message: Message) => void,
) => {
  const message = messages.find((item) => item.id === aiMessageId);
  if (message) {
    updater(message);
  }
};

const toUiMessage = (conversationId: string, message: ConversationHistoryMessage): Message => {
  if (message.role === 'assistant') {
    return {
      id: `${conversationId}-${message.seqNo}`,
      role: 'assistant',
      content: message.content,
      rawContent: message.content,
      stableContent: message.content,
      pendingTail: '',
      thinkingContent: message.thinking ?? '',
      renderMode: 'markdown',
      report: message.report ?? undefined,
      isLoading: message.report
        ? !['COMPLETED', 'PARTIAL_COMPLETED', 'FAILED'].includes(message.report.status)
        : false,
    };
  }
  return {
    id: `${conversationId}-${message.seqNo}`,
    role: 'user',
    content: message.content,
  };
};

export function useChat() {
  const messages = ref<Message[]>([]);
  const isGenerating = ref(false);
  const isHistoryLoading = ref(false);
  const currentStream = ref<ChatStreamHandle | null>(null);
  const reportPollTimer = ref<ReturnType<typeof window.setTimeout> | null>(null);
  const activeReportId = ref('');
  const latestHistoryRequestId = ref(0);

  const clearReportPolling = () => {
    if (reportPollTimer.value) {
      window.clearTimeout(reportPollTimer.value);
      reportPollTimer.value = null;
    }
    activeReportId.value = '';
  };

  const stopGenerating = () => {
    clearReportPolling();
    if (currentStream.value) {
      currentStream.value.close();
      currentStream.value = null;
      const lastMessage = messages.value[messages.value.length - 1];
      if (lastMessage?.isLoading) {
        lastMessage.isLoading = false;
        syncAssistantMessageState(lastMessage, true);
      }
    }
    isGenerating.value = false;
  };

  const applyReportRun = (message: Message, run: ReportRun) => {
    message.report = {
      reportId: run.reportId,
      question: run.question,
      status: run.status,
      evidenceCount: run.evidenceCount,
      attachmentFileName: run.attachmentFileName,
      errorMessage: run.errorMessage,
      phaseMessage: run.phaseMessage,
      progressPercent: run.progressPercent,
      selectedDocumentCount: run.selectedDocumentCount,
      analyzedDocumentCount: run.analyzedDocumentCount,
      warnings: run.warnings ?? [],
      updatedAt: run.updatedAt,
    };
    message.isLoading = !['COMPLETED', 'PARTIAL_COMPLETED', 'FAILED'].includes(run.status);
    if (run.status === 'COMPLETED' || run.status === 'PARTIAL_COMPLETED') {
      message.rawContent = run.answerMarkdown ?? '';
      message.content = message.rawContent;
      message.renderMode = 'markdown';
      syncAssistantMessageState(message, true);
    } else if (run.status === 'FAILED') {
      message.rawContent = '报告生成失败，请稍后重试。';
      message.content = message.rawContent;
      syncAssistantMessageState(message, true);
    }
  };

  const pollReport = (
    reportId: string,
    aiMessageId: string,
    options?: { onScrollToBottom?: () => void; onReportUpdated?: (run: ReportRun) => void },
  ) => {
    clearReportPolling();
    activeReportId.value = reportId;
    const poll = async () => {
      try {
        const run = await reportService.get(reportId);
        if (activeReportId.value !== reportId) return;
        updateAssistantMessage(messages.value, aiMessageId, (message) => applyReportRun(message, run));
        options?.onReportUpdated?.(run);
        options?.onScrollToBottom?.();
        if (['COMPLETED', 'PARTIAL_COMPLETED', 'FAILED'].includes(run.status)) {
          clearReportPolling();
          isGenerating.value = false;
          return;
        }
        reportPollTimer.value = window.setTimeout(poll, 1000);
      } catch (error) {
        console.error('Report polling failed:', error);
        if (activeReportId.value === reportId) {
          reportPollTimer.value = window.setTimeout(poll, 2000);
        }
      }
    };
    void poll();
  };

  const loadConversationMessages = async (
    conversationId: string,
    onScrollToBottom?: () => void,
    onReportUpdated?: (run: ReportRun) => void,
  ) => {
    latestHistoryRequestId.value += 1;
    const requestId = latestHistoryRequestId.value;
    stopGenerating();
    if (!conversationId) {
      messages.value = [];
      isHistoryLoading.value = false;
      return;
    }

    isHistoryLoading.value = true;
    messages.value = [];
    try {
      const historyMessages = await conversationService.listConversationMessages(conversationId);
      if (requestId !== latestHistoryRequestId.value) return;
      messages.value = historyMessages.map((message) => toUiMessage(conversationId, message));
      const activeReportMessage = [...messages.value].reverse().find(
        (message) =>
          message.report &&
          !['COMPLETED', 'PARTIAL_COMPLETED', 'FAILED'].includes(message.report.status),
      );
      if (activeReportMessage?.report) {
        isGenerating.value = true;
        pollReport(activeReportMessage.report.reportId, activeReportMessage.id, {
          onScrollToBottom,
          onReportUpdated,
        });
      }
      onScrollToBottom?.();
    } catch (error) {
      if (requestId === latestHistoryRequestId.value) {
        messages.value = [];
      }
      throw error;
    } finally {
      if (requestId === latestHistoryRequestId.value) {
        isHistoryLoading.value = false;
      }
    }
  };

  const sendMessage = (
    text: string,
    conversationId: string,
    onScrollToBottom?: () => void,
    options?: { enableThinking?: boolean },
    mode: ConversationMode = 'CHAT',
    callbacks?: {
      onSubmitted?: () => void;
      onReportUpdated?: (run: ReportRun) => void;
    },
  ) => {
    if (!text.trim() || !conversationId || isGenerating.value || isHistoryLoading.value) return;

    const requestStartedAt = Date.now();
    messages.value.push({ id: requestStartedAt.toString(), role: 'user', content: text });
    const aiMessageId = (requestStartedAt + 1).toString();
    messages.value.push({
      id: aiMessageId,
      role: 'assistant',
      content: '',
      rawContent: '',
      stableContent: '',
      pendingTail: '',
      renderMode: 'markdown',
      isLoading: true,
    });
    onScrollToBottom?.();
    isGenerating.value = true;

    if (mode === 'REPORT') {
      void (async () => {
        try {
          const run = await reportService.submit(conversationId, text);
          updateAssistantMessage(messages.value, aiMessageId, (message) => applyReportRun(message, run));
          callbacks?.onSubmitted?.();
          callbacks?.onReportUpdated?.(run);
          pollReport(run.reportId, aiMessageId, {
            onScrollToBottom,
            onReportUpdated: callbacks?.onReportUpdated,
          });
        } catch (error) {
          console.error('Report submit failed:', error);
          updateAssistantMessage(messages.value, aiMessageId, (message) => {
            message.isLoading = false;
            message.content = 'Report submission failed. Please try again.';
            message.rawContent = message.content;
            syncAssistantMessageState(message, true);
          });
          isGenerating.value = false;
        }
      })();
      return;
    }

    callbacks?.onSubmitted?.();
    currentStream.value = chatService.streamChat({
      conversationId,
      prompt: text,
      enableThinking: options?.enableThinking ?? false,
      onThinking: (newData) => {
        updateAssistantMessage(messages.value, aiMessageId, (message) => {
          message.thinkingContent = `${message.thinkingContent ?? ''}${newData}`;
        });
        onScrollToBottom?.();
      },
      onMessage: (newData) => {
        updateAssistantMessage(messages.value, aiMessageId, (message) => {
          message.isLoading = false;
          message.renderMode = 'markdown';
          message.rawContent = `${message.rawContent ?? message.content ?? ''}${newData}`;
          syncAssistantMessageState(message, false);
        });
        onScrollToBottom?.();
      },
      onSources: (sources) => {
        updateAssistantMessage(messages.value, aiMessageId, (message) => {
          message.sources = sources;
        });
      },
      onError: (error) => {
        console.error('SSE Error:', error);
        updateAssistantMessage(messages.value, aiMessageId, (message) => {
          message.isLoading = false;
          syncAssistantMessageState(message, true);
        });
        isGenerating.value = false;
        currentStream.value = null;
      },
      onComplete: () => {
        updateAssistantMessage(messages.value, aiMessageId, (message) => {
          message.isLoading = false;
          syncAssistantMessageState(message, true);
        });
        isGenerating.value = false;
        currentStream.value = null;
      },
    });
  };

  const clearMessages = () => {
    latestHistoryRequestId.value += 1;
    stopGenerating();
    messages.value = [];
    isHistoryLoading.value = false;
  };

  return {
    messages,
    isGenerating,
    isHistoryLoading,
    loadConversationMessages,
    sendMessage,
    stopGenerating,
    clearMessages,
  };
}
