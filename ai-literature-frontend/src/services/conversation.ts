import request from '@/request';
import type {
  Conversation,
  CreateConversationRequest,
  PinConversationRequest,
  RenameConversationRequest,
} from '@/types/conversation';
import type { ConversationHistoryMessage } from '@/types/chat';

export const conversationService = {
  async listConversations(): Promise<Conversation[]> {
    const response = await request.get<Conversation[]>('/conversations');
    return response.data;
  },

  async createConversation(payload: CreateConversationRequest = {}): Promise<Conversation> {
    const response = await request.post<Conversation>('/conversations', payload);
    return response.data;
  },

  async renameConversation(conversationId: string, payload: RenameConversationRequest): Promise<Conversation> {
    const response = await request.patch<Conversation>(`/conversations/${encodeURIComponent(conversationId)}`, payload);
    return response.data;
  },

  async pinConversation(conversationId: string, payload: PinConversationRequest): Promise<Conversation> {
    const response = await request.patch<Conversation>(
      `/conversations/${encodeURIComponent(conversationId)}/pin`,
      payload,
    );
    return response.data;
  },

  async deleteConversation(conversationId: string): Promise<void> {
    await request.delete(`/conversations/${encodeURIComponent(conversationId)}`);
  },

  async listConversationMessages(conversationId: string): Promise<ConversationHistoryMessage[]> {
    const response = await request.get<ConversationHistoryMessage[]>(
      `/conversations/${encodeURIComponent(conversationId)}/messages`,
    );
    return response.data;
  },
};
