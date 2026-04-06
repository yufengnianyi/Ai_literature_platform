import myAxios from '@/request';
import { API_BASE_URL } from '@/constants/user';
import { redirectToLogin } from '@/request';

export interface ReviewTaskSubmitRequest {
  question: string;
}

export interface ReviewTaskAcceptedResponse {
  taskId: string;
  status: string;
}

export interface ReviewTaskRecord {
  taskId: string;
  userId: string;
  question: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  stage: string | null;
  reportMarkdown: string | null;
  candidateCount: number | null;
  evidenceCount: number | null;
  metrics: {
    retrievalMs: number | null;
    rerankMs: number | null;
    extractionMs: number | null;
    fusionMs: number | null;
    reportMs: number | null;
    totalMs: number | null;
  };
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
}

export interface ReviewStreamHandle {
  close: () => void;
}

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

  return { eventName, data: dataLines.join('\n') };
};

const decodePayload = (rawData: string): string => {
  if (rawData.startsWith('"') && rawData.endsWith('"')) {
    try {
      return JSON.parse(rawData) as string;
    } catch {
      return rawData;
    }
  }
  return rawData;
};

export const reviewService = {
  async submitTask(question: string): Promise<ReviewTaskAcceptedResponse> {
    const { data } = await myAxios.post('/review/tasks', { question });
    return data as ReviewTaskAcceptedResponse;
  },

  async getTask(taskId: string): Promise<ReviewTaskRecord> {
    const { data } = await myAxios.get(`/review/tasks/${taskId}`);
    return data as ReviewTaskRecord;
  },

  async getReport(taskId: string): Promise<string> {
    const { data } = await myAxios.get(`/review/tasks/${taskId}/report`);
    return data as string;
  },

  async listTasks(): Promise<ReviewTaskRecord[]> {
    const { data } = await myAxios.get('/review/tasks');
    return data as ReviewTaskRecord[];
  },

  async deleteTask(taskId: string): Promise<boolean> {
    const { data } = await myAxios.delete(`/review/tasks/${taskId}`);
    return data as boolean;
  },

  async retryTask(taskId: string): Promise<ReviewTaskAcceptedResponse> {
    const { data } = await myAxios.post(`/review/tasks/${taskId}/retry`);
    return data as ReviewTaskAcceptedResponse;
  },

  streamReport(params: {
    question: string;
    onMessage: (data: string) => void;
    onError: (error: unknown) => void;
    onComplete: () => void;
  }): ReviewStreamHandle {
    const encodedQuestion = encodeURIComponent(params.question);
    const taskId = crypto.randomUUID();
    const url = `${API_BASE_URL}/review/tasks/${taskId}/stream?question=${encodedQuestion}`;
    const controller = new AbortController();
    let completed = false;

    const finalize = () => {
      if (!completed) {
        completed = true;
        params.onComplete();
      }
    };

    void (async () => {
      try {
        const response = await fetch(url, {
          method: 'GET',
          headers: { Accept: 'text/event-stream' },
          credentials: 'include',
          signal: controller.signal,
        });

        if (response.status === 401) {
          redirectToLogin();
          throw new Error('Unauthorized');
        }

        if (!response.ok) {
          throw new Error(`Request failed: ${response.status}`);
        }

        if (!response.body) {
          throw new Error('Response body is empty');
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

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
              params.onMessage(decodePayload(parsed.data));
            }
            separatorIndex = buffer.indexOf('\n\n');
          }
        }

        finalize();
      } catch (error) {
        if (controller.signal.aborted) {
          finalize();
          return;
        }
        params.onError(error);
      }
    })();

    return {
      close: () => {
        if (!controller.signal.aborted) controller.abort();
      },
    };
  },
};
