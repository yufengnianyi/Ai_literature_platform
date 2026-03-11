/**
 * 提供 SSE 对话接口的封装服务
 */

// number prompt: 用户传入的参数
// 其他三个是 不同状态下的处理出发
export interface ChatSseParams {
  memoryId: number;
  prompt: string;
  onMessage: (data: string) => void;
  onError: (error: Event) => void;
  onComplete: () => void;
}

export const chatService = {
  /**
   * 发起流式对话
   */

  streamChat({ memoryId, prompt, onMessage, onError, onComplete }: ChatSseParams): EventSource {
    const encodedPrompt = encodeURIComponent(prompt);
    // TODO: baseUrl 可以抽离到环境变量
    const url = `http://localhost:8081/api/ai?memory_id=${memoryId}&prompt=${encodedPrompt}`;

    // 想url 发送一个SSE请求，保持链接，持续接收数据
    const eventSource = new EventSource(url);


    // 接受到数据的方法
    eventSource.onmessage = (event) => {
      let newData = event.data;
      // 简单处理如果后端发送的数据被引号包裹，将其去掉
      // 处理后端返回的数据
      if (newData.startsWith('"') && newData.endsWith('"')) {
         try {
             newData = JSON.parse(newData);
         } catch(e) {}
      }
      onMessage(newData);
    };

    // 错误处理
    eventSource.onerror = (error) => {
      onError(error);
      eventSource.close();
    };

    // 假设后端完成流式输出时会关闭连接或者发送一个特定的结束标记
    eventSource.addEventListener('complete', () => {
      onComplete();
      eventSource.close();
    });

    return eventSource;
  }
};
