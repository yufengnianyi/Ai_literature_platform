import { ref } from 'vue';
import type { Message } from '../types/chat';
import { chatService, type ChatStreamHandle } from '../services/chat';

export function useChat() {
  const messages = ref<Message[]>([]);
  const isGenerating = ref(false);
  const currentStream = ref<ChatStreamHandle | null>(null);

  const sendMessage = (text: string, conversationId: string, onScrollToBottom?: () => void) => {
    if (!text.trim() || !conversationId || isGenerating.value) {
      return;
    }

    messages.value.push({
      id: Date.now().toString(),
      role: 'user',
      content: text,
    });

    if (onScrollToBottom) {
      onScrollToBottom();
    }

    const aiMessageId = (Date.now() + 1).toString();
    messages.value.push({
      id: aiMessageId,
      role: 'assistant',
      content: '',
      isLoading: true,
    });

    if (onScrollToBottom) {
      onScrollToBottom();
    }

    isGenerating.value = true;

    currentStream.value = chatService.streamChat({
      conversationId,
      prompt: text,
      onMessage: (newData) => {
        const msgIndex = messages.value.findIndex((item) => item.id === aiMessageId);
        const message = msgIndex === -1 ? undefined : messages.value[msgIndex];
        if (message) {
          message.isLoading = false;
          message.content += newData;
          if (onScrollToBottom) {
            onScrollToBottom();
          }
        }
      },
      onSources: (sources) => {
        const msgIndex = messages.value.findIndex((item) => item.id === aiMessageId);
        const message = msgIndex === -1 ? undefined : messages.value[msgIndex];
        if (message) {
          message.sources = sources;
        }
      },
      onError: (error) => {
        console.error('SSE Error:', error);
        const msgIndex = messages.value.findIndex((item) => item.id === aiMessageId);
        const message = msgIndex === -1 ? undefined : messages.value[msgIndex];
        if (message) {
          message.isLoading = false;
        }
        isGenerating.value = false;
        currentStream.value = null;
      },
      onComplete: () => {
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
      }
    }
  };

  const clearMessages = () => {
    messages.value = [];
  };

  return {
    messages,
    isGenerating,
    sendMessage,
    stopGenerating,
    clearMessages,
  };
}
