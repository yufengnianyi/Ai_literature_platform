export interface Conversation {
  conversationId: string;
  title: string;
  pinned: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateConversationRequest {
  title?: string;
}

export interface RenameConversationRequest {
  title: string;
}

export interface PinConversationRequest {
  pinned: boolean;
}
