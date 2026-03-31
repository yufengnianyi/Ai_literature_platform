export type MessageRenderMode = 'markdown' | 'plaintext-fallback';

export interface MessageSource {
  title: string;
  section?: string;
  chunk?: string;
  page?: string;
}

export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  graphPrompt?: string;
  rawContent?: string;
  stableContent?: string;
  pendingTail?: string;
  renderMode?: MessageRenderMode;
  isLoading?: boolean;
  sources?: MessageSource[];
}

export interface ConversationHistoryMessage {
  seqNo: number;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}
