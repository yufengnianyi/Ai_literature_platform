import request from '@/request';
import type { Conversation, CreateConversationRequest, RenameConversationRequest } from '@/types/conversation';

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

  async deleteConversation(conversationId: string): Promise<void> {
    await request.delete(`/conversations/${encodeURIComponent(conversationId)}`);
  },
};
