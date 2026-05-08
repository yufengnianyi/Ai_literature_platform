import { ref } from 'vue';

import type { ConversationHistoryMessage, Message } from '../types/chat';
import { splitMarkdownStream } from '../utils/markdown';
import { chatService, type ChatStreamHandle } from '../services/chat';
import { conversationService } from '@/services/conversation';

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
  const msgIndex = messages.findIndex((item) => item.id === aiMessageId);
  const message = msgIndex === -1 ? undefined : messages[msgIndex];
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
  const latestHistoryRequestId = ref(0);

  const loadConversationMessages = async (conversationId: string, onScrollToBottom?: () => void) => {
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
      if (requestId !== latestHistoryRequestId.value) {
        return;
      }

      messages.value = historyMessages.map((message) => toUiMessage(conversationId, message));
      if (onScrollToBottom) {
        onScrollToBottom();
      }
    } catch (error) {
      if (requestId !== latestHistoryRequestId.value) {
        return;
      }

      messages.value = [];
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
    options?: { deepThinking?: boolean },
  ) => {
    if (!text.trim() || !conversationId || isGenerating.value || isHistoryLoading.value) {
      return;
    }

    const requestStartedAt = Date.now();
    messages.value.push({
      id: requestStartedAt.toString(),
      role: 'user',
      content: text,
    });

    if (onScrollToBottom) {
      onScrollToBottom();
    }

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

    if (onScrollToBottom) {
      onScrollToBottom();
    }

    isGenerating.value = true;

    currentStream.value = chatService.streamChat({
      conversationId,
      prompt: text,
      deepThinking: options?.deepThinking ?? false,
      onThinking: (newData) => {
        updateAssistantMessage(messages.value, aiMessageId, (message) => {
          message.thinkingContent = `${message.thinkingContent ?? ''}${newData}`;
        });

        if (onScrollToBottom) {
          onScrollToBottom();
        }
      },
      onMessage: (newData) => {
        updateAssistantMessage(messages.value, aiMessageId, (message) => {
          message.isLoading = false;
          message.renderMode = 'markdown';
          message.rawContent = `${message.rawContent ?? message.content ?? ''}${newData}`;
          syncAssistantMessageState(message, false);
        });

        if (onScrollToBottom) {
          onScrollToBottom();
        }
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

  const stopGenerating = () => {
    if (currentStream.value) {
      currentStream.value.close();
      currentStream.value = null;
      isGenerating.value = false;

      const lastMessage = messages.value[messages.value.length - 1];
      if (lastMessage && lastMessage.isLoading) {
        lastMessage.isLoading = false;
        syncAssistantMessageState(lastMessage, true);
      }
    }
  };

  const clearMessages = () => {
    latestHistoryRequestId.value += 1;
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
