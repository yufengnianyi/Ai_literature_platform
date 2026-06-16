export type MessageRenderMode = 'markdown' | 'plaintext-fallback';

export interface MessageSource {
  title: string;
  section?: string;
  chunk?: string;
  page?: string;
  excerpt?: string;
}

export type ReportStatus =
  | 'QUEUED'
  | 'REWRITING'
  | 'MATCHING'
  | 'GENERATING'
  | 'PLANNING'
  | 'ANALYZING_EVIDENCE'
  | 'RETRIEVING_LITERATURE'
  | 'ANALYZING_LITERATURE'
  | 'SYNTHESIZING'
  | 'VALIDATING'
  | 'COMPLETED'
  | 'PARTIAL_COMPLETED'
  | 'FAILED';

export interface ReportMessageMetadata {
  reportId: string;
  question: string;
  status: ReportStatus;
  evidenceCount: number;
  attachmentFileName?: string | null;
  errorMessage?: string | null;
  phaseMessage?: string | null;
  progressPercent: number;
  selectedDocumentCount: number;
  analyzedDocumentCount: number;
  warnings: string[];
  updatedAt: string;
}

export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  rawContent?: string;
  stableContent?: string;
  pendingTail?: string;
  thinkingContent?: string;
  renderMode?: MessageRenderMode;
  isLoading?: boolean;
  sources?: MessageSource[];
  report?: ReportMessageMetadata;
}

export interface ConversationHistoryMessage {
  seqNo: number;
  role: 'user' | 'assistant';
  content: string;
  thinking?: string | null;
  createdAt: string;
  report?: ReportMessageMetadata | null;
}
