import { readonly, ref } from 'vue';
import { conversationService } from '@/services/conversation';
import type { Conversation } from '@/types/conversation';

const conversationsState = ref<Conversation[]>([]);
const activeConversationIdState = ref('');
const loadingState = ref(false);
const initializedState = ref(false);
let initializePromise: Promise<void> | null = null;

const sortConversations = (items: Conversation[]): Conversation[] => {
  return [...items].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());
};

const setActiveConversation = (conversationId: string) => {
  activeConversationIdState.value = conversationId;
};

const refreshConversations = async (): Promise<Conversation[]> => {
  loadingState.value = true;
  try {
    const rows = await conversationService.listConversations();
    const sorted = sortConversations(rows);
    conversationsState.value = sorted;

    if (sorted.length === 0) {
      activeConversationIdState.value = '';
      return sorted;
    }

    const activeExists = sorted.some((item) => item.conversationId === activeConversationIdState.value);
    const latestConversation = sorted[0];
    if (!activeExists && latestConversation) {
      activeConversationIdState.value = latestConversation.conversationId;
    }

    return sorted;
  } finally {
    loadingState.value = false;
  }
};

const createConversation = async (title?: string): Promise<Conversation> => {
  const payload = title && title.trim().length > 0 ? { title: title.trim() } : {};
  const created = await conversationService.createConversation(payload);
  conversationsState.value = sortConversations([created, ...conversationsState.value.filter((it) => it.conversationId !== created.conversationId)]);
  activeConversationIdState.value = created.conversationId;
  return created;
};

const renameConversation = async (conversationId: string, title: string): Promise<Conversation> => {
  const renamed = await conversationService.renameConversation(conversationId, { title: title.trim() });
  conversationsState.value = sortConversations(
    conversationsState.value.map((item) => (item.conversationId === conversationId ? renamed : item)),
  );
  return renamed;
};

const initializeConversations = async (): Promise<void> => {
  if (initializedState.value) {
    return;
  }
  if (initializePromise) {
    await initializePromise;
    return;
  }

  initializePromise = (async () => {
    const rows = await refreshConversations();
    if (rows.length === 0) {
      await createConversation();
    }
    initializedState.value = true;
  })();

  try {
    await initializePromise;
  } finally {
    initializePromise = null;
  }
};

export function useConversationState() {
  return {
    conversations: readonly(conversationsState),
    activeConversationId: readonly(activeConversationIdState),
    isConversationLoading: readonly(loadingState),
    initializeConversations,
    refreshConversations,
    createConversation,
    renameConversation,
    setActiveConversation,
  };
}
