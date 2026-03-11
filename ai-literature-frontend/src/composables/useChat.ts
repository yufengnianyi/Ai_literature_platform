import { ref } from 'vue';
import type { Message } from '../types/chat';
import { chatService } from '../services/chat';

export function useChat() {
  const messages = ref<Message[]>([]);
  const isGenerating = ref(false);
  const currentEventSource = ref<EventSource | null>(null);

  /**
   * 发送新消息
   * @param text 用户输入的内容
   * @param memoryId 当前会话的 ID
   * @param onScrollToBottom 通知 UI 滚动到底部的回调
   */
  const sendMessage = (text: string, memoryId: number = 1, onScrollToBottom?: () => void) => {
    if (!text.trim() || isGenerating.value) return;

    // 1. 添加用户消息
    messages.value.push({
      id: Date.now().toString(),
      role: 'user',
      content: text
    });
    
    if (onScrollToBottom) onScrollToBottom();

    // 2. 添加 AI 正在生成的占位消息
    const aiMessageId = (Date.now() + 1).toString();
    messages.value.push({
      id: aiMessageId,
      role: 'assistant',
      content: '',
      isLoading: true
    });
    
    if (onScrollToBottom) onScrollToBottom();

    isGenerating.value = true;
    
    // 3. 调用 SSE 服务
    currentEventSource.value = chatService.streamChat({
      memoryId,
      prompt: text,
      onMessage: (newData) => {
        const msgIndex = messages.value.findIndex(m => m.id === aiMessageId);
        if (msgIndex !== -1) {
          messages.value[msgIndex].isLoading = false;
          messages.value[msgIndex].content += newData;
          if (onScrollToBottom) onScrollToBottom();
        }
      },
      onError: (error) => {
        console.error('SSE Error:', error);
        const msgIndex = messages.value.findIndex(m => m.id === aiMessageId);
        if (msgIndex !== -1) {
          messages.value[msgIndex].isLoading = false;
        }
        isGenerating.value = false;
        currentEventSource.value = null;
      },
      onComplete: () => {
        isGenerating.value = false;
        currentEventSource.value = null;
      }
    });
  };

  /**
   * 停止当前正在生成的回答
   */
  const stopGenerating = () => {
    if (currentEventSource.value) {
      currentEventSource.value.close();
      currentEventSource.value = null;
      isGenerating.value = false;
      
      // 可以把最后一条处于 loading 的消息标记为完成
      const lastMessage = messages.value[messages.value.length - 1];
      if (lastMessage && lastMessage.isLoading) {
        lastMessage.isLoading = false;
      }
    }
  };

  /**
   * 清空历史记录
   */
  const clearMessages = () => {
    messages.value = [];
  };

  return {
    messages,
    isGenerating,
    sendMessage,
    stopGenerating,
    clearMessages
  };
}
