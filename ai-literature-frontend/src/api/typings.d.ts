declare namespace API {
  type chatParams = {
    conversationId?: string;
    memory_id?: number;
    prompt: string;
  };

  type ServerSentEventString = true;
}