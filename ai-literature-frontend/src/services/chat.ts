import { API_BASE_URL } from '@/constants/user';
import type { MessageSource } from '@/types/chat';
import { normalizeSourcesPayload } from '@/utils/sources';
import { redirectToLogin } from '@/request';

export interface ChatSseParams {
  conversationId: string;
  prompt: string;
  onMessage: (data: string) => void;
  onSources?: (sources: MessageSource[]) => void;
  onError: (error: unknown) => void;
  onComplete: () => void;
}

export interface ChatStreamHandle {
  close: () => void;
}

const decodeMessagePayload = (rawData: string): string => {
  if (rawData.startsWith('"') && rawData.endsWith('"')) {
    try {
      return JSON.parse(rawData) as string;
    } catch {
      return rawData;
    }
  }
  return rawData;
};

const parseEventBlock = (rawBlock: string): { eventName: string; data: string } | null => {
  const lines = rawBlock.split('\n');
  let eventName = 'message';
  const dataLines: string[] = [];

  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim() || 'message';
      continue;
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart());
    }
  }

  if (dataLines.length === 0 && eventName !== 'complete') {
    return null;
  }

  return {
    eventName,
    data: dataLines.join('\n'),
  };
};

export const chatService = {
  streamChat({ conversationId, prompt, onMessage, onSources, onError, onComplete }: ChatSseParams): ChatStreamHandle {
    const encodedConversationId = encodeURIComponent(conversationId);
    const encodedPrompt = encodeURIComponent(prompt);
    const url = `${API_BASE_URL}/ai?conversationId=${encodedConversationId}&prompt=${encodedPrompt}`;
    const controller = new AbortController();

    let completed = false;
    const finalize = () => {
      if (!completed) {
        completed = true;
        onComplete();
      }
    };

    void (async () => {
      try {
        const response = await fetch(url, {
          method: 'GET',
          headers: {
            'Accept': 'text/event-stream',
          },
          credentials: 'include',
          signal: controller.signal,
        });

        if (response.status === 401) {
          redirectToLogin();
          throw new Error('Chat request unauthorized');
        }

        if (!response.ok) {
          throw new Error(`Chat request failed: ${response.status}`);
        }

        if (!response.body) {
          throw new Error('SSE response body is empty');
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }

          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
          let separatorIndex = buffer.indexOf('\n\n');

          while (separatorIndex >= 0) {
            const rawBlock = buffer.slice(0, separatorIndex);
            buffer = buffer.slice(separatorIndex + 2);

            const parsed = parseEventBlock(rawBlock);
            if (parsed) {
              if (parsed.eventName === 'complete') {
                finalize();
                return;
              }

              if (parsed.eventName === 'sources') {
                if (onSources) {
                  try {
                    onSources(normalizeSourcesPayload(JSON.parse(parsed.data)));
                  } catch (error) {
                    console.error('Failed to parse sources payload', error);
                  }
                }
              } else {
                onMessage(decodeMessagePayload(parsed.data));
              }
            }

            separatorIndex = buffer.indexOf('\n\n');
          }
        }

        const rest = buffer.trim();
        if (rest.length > 0) {
          const parsed = parseEventBlock(rest);
          if (parsed && parsed.eventName !== 'sources') {
            onMessage(decodeMessagePayload(parsed.data));
          }
        }

        finalize();
      } catch (error) {
        if (controller.signal.aborted) {
          finalize();
          return;
        }
        onError(error);
      }
    })();

    return {
      close: () => {
        if (!controller.signal.aborted) {
          controller.abort();
        }
      },
    };
  },
};
