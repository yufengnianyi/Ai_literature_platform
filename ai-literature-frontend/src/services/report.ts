import request from '@/request';
import { API_BASE_URL } from '@/constants/user';
import type { ReportStatus } from '@/types/chat';

export interface ReportRun {
  reportId: string;
  conversationId: string;
  question: string;
  rewrittenQuestion?: string | null;
  status: ReportStatus;
  evidenceCount: number;
  attachmentFileName?: string | null;
  attachmentAvailable: boolean;
  answerMarkdown?: string | null;
  errorMessage?: string | null;
  phaseMessage?: string | null;
  progressPercent: number;
  selectedDocumentCount: number;
  analyzedDocumentCount: number;
  warnings: string[];
  createdAt: string;
  updatedAt: string;
  finishedAt?: string | null;
}

export const reportService = {
  async submit(conversationId: string, question: string): Promise<ReportRun> {
    const response = await request.post<ReportRun>('/report/runs', { conversationId, question });
    return response.data;
  },

  async get(reportId: string): Promise<ReportRun> {
    const response = await request.get<ReportRun>(`/report/runs/${encodeURIComponent(reportId)}`);
    return response.data;
  },

  async listByConversation(conversationId: string): Promise<ReportRun[]> {
    const response = await request.get<ReportRun[]>(
      `/conversations/${encodeURIComponent(conversationId)}/report-runs`,
    );
    return response.data;
  },

  downloadAttachment(reportId: string): void {
    const link = document.createElement('a');
    link.href = `${API_BASE_URL}/report/runs/${encodeURIComponent(reportId)}/attachment`;
    link.download = 'compound-evidence.xlsx';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  },
};
