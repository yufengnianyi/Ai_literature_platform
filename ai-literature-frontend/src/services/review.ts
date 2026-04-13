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

export interface QueryAnalysis {
  mainQuestion: string;
  subQuestions: string[];
  keyEntities: string[];
  keyConcepts: string[];
}

export interface ReviewGenerateRequest {
  question: string;
  mainQuestion: string;
  selectedSubQuestions: string[];
  selectedEntities: string[];
  selectedConcepts: string[];
  customSubQuestions: string[];
}

export interface CandidateReviewRequest {
  excludedChunkIds: string[];
  prioritizedChunkIds: string[];
}

export interface EvidenceReviewRequest {
  excludedEvidenceIds: number[];
  focusSubQuestions: string[];
  userGuidance: string;
}

export interface ReviewCandidate {
  id: number;
  taskId: string;
  chunkId: string;
  documentId: string | null;
  documentTitle: string | null;
  retrievalScore: number;
  retrievalSource: string | null;
  rerankScore: number | null;
  relevance: 'HIGH' | 'MEDIUM' | 'LOW' | 'IRRELEVANT' | null;
  screeningReason: string | null;
  included: boolean;
  chunkText: string | null;
}

export interface ReviewEvidenceRecord {
  id: number;
  taskId: string;
  candidateId: number | null;
  chunkId: string | null;
  documentId: string | null;
  claim: string | null;
  finding: string | null;
  methodology: string | null;
  entities: string[];
  evidenceType: string | null;
  confidence: number;
  originalText: string | null;
  normalizedGroup: string | null;
  subQuestion: string | null;
  consistency: string | null;
}

export interface ReviewTaskRecord {
  taskId: string;
  userId: string;
  question: string;
  status: 'QUEUED' | 'RUNNING' | 'AWAITING_USER' | 'COMPLETED' | 'FAILED';
  stage: string | null;
  queryAnalysis: QueryAnalysis | null;
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
  async analyzeQuestion(question: string): Promise<QueryAnalysis> {
    const { data } = await myAxios.post('/review/analyze', { question });
    return data as QueryAnalysis;
  },

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

  async startRetrieval(taskId: string, request: ReviewGenerateRequest): Promise<ReviewTaskAcceptedResponse> {
    const { data } = await myAxios.post(`/review/tasks/${taskId}/retrieve`, request);
    return data as ReviewTaskAcceptedResponse;
  },

  async getCandidates(taskId: string): Promise<ReviewCandidate[]> {
    const { data } = await myAxios.get(`/review/tasks/${taskId}/candidates`);
    return data as ReviewCandidate[];
  },

  async startExtraction(taskId: string, request: CandidateReviewRequest): Promise<ReviewTaskAcceptedResponse> {
    const { data } = await myAxios.post(`/review/tasks/${taskId}/extract`, request);
    return data as ReviewTaskAcceptedResponse;
  },

  async getEvidence(taskId: string): Promise<ReviewEvidenceRecord[]> {
    const { data } = await myAxios.get(`/review/tasks/${taskId}/evidence`);
    return data as ReviewEvidenceRecord[];
  },

  startGeneration(params: {
    taskId: string;
    request: EvidenceReviewRequest;
    onMessage: (data: string) => void;
    onXlsxReady: (downloadUrl: string) => void;
    onError: (error: unknown) => void;
    onComplete: () => void;
  }): ReviewStreamHandle {
    const url = `${API_BASE_URL}/review/tasks/${params.taskId}/generate`;
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
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
          },
          credentials: 'include',
          signal: controller.signal,
          body: JSON.stringify(params.request),
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
              if (parsed.eventName === 'xlsx_ready') {
                params.onXlsxReady(reviewService.getXlsxDownloadUrl(params.taskId));
                continue;
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

  getXlsxDownloadUrl(taskId: string): string {
    return `${API_BASE_URL}/review/tasks/${taskId}/xlsx`;
  },

  downloadXlsx(taskId: string): void {
    const url = `${API_BASE_URL}/review/tasks/${taskId}/xlsx`;
    const link = document.createElement('a');
    link.href = url;
    link.download = `review-summary-${taskId}.xlsx`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
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

  streamReportWithSelections(params: {
    request: ReviewGenerateRequest;
    onMessage: (data: string) => void;
    onXlsxReady: (downloadUrl: string) => void;
    onError: (error: unknown) => void;
    onComplete: () => void;
  }): ReviewStreamHandle {
    const taskId = crypto.randomUUID();
    const url = `${API_BASE_URL}/review/tasks/${taskId}/stream`;
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
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
          },
          credentials: 'include',
          signal: controller.signal,
          body: JSON.stringify(params.request),
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
              if (parsed.eventName === 'xlsx_ready') {
                params.onXlsxReady(reviewService.getXlsxDownloadUrl(taskId));
                continue;
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
