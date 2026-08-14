export type ConversationMode = 'CHAT';

export interface Conversation {
  conversationId: string;
  title: string;
  pinned: boolean;
  mode: ConversationMode;
  createdAt: string;
  updatedAt: string;
}

export interface CreateConversationRequest {
  title?: string;
  mode?: ConversationMode;
}

export interface RenameConversationRequest {
  title: string;
}

export interface PinConversationRequest {
  pinned: boolean;
}
