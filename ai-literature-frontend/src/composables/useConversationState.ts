import { readonly, ref } from 'vue';
import { conversationService } from '@/services/conversation';
import type { Conversation } from '@/types/conversation';
import { DEFAULT_USER } from '@/constants/user';

const conversationsState = ref<Conversation[]>([]);
const activeConversationIdState = ref('');
const loadingState = ref(false);
const initializedState = ref(false);
let initializePromise: Promise<void> | null = null;
const activeConversationStorageKey = `ai-literature.activeConversationId:${DEFAULT_USER.userId}`;

const sortConversations = (items: Conversation[]): Conversation[] => {
  return [...items].sort((a, b) => {
    if (a.pinned !== b.pinned) {
      return Number(b.pinned) - Number(a.pinned);
    }
    return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime();
  });
};

const setActiveConversation = (conversationId: string) => {
  activeConversationIdState.value = conversationId;
  persistActiveConversationId(conversationId);
};

const readPersistedActiveConversationId = (): string => {
  if (typeof window === 'undefined') {
    return '';
  }
  return window.localStorage.getItem(activeConversationStorageKey) || '';
};

const persistActiveConversationId = (conversationId: string) => {
  if (typeof window === 'undefined') {
    return;
  }
  if (conversationId) {
    window.localStorage.setItem(activeConversationStorageKey, conversationId);
    return;
  }
  window.localStorage.removeItem(activeConversationStorageKey);
};

const refreshConversations = async (): Promise<Conversation[]> => {
  loadingState.value = true;
  try {
    const rows = await conversationService.listConversations();
    const sorted = sortConversations(rows);
    conversationsState.value = sorted;

    if (sorted.length === 0) {
      activeConversationIdState.value = '';
      persistActiveConversationId('');
      return sorted;
    }

    const activeExists = sorted.some((item) => item.conversationId === activeConversationIdState.value);
    const persistedConversationId = readPersistedActiveConversationId();
    const persistedExists = sorted.some((item) => item.conversationId === persistedConversationId);
    const latestConversation = sorted[0];
    if (!activeExists) {
      const nextConversationId = persistedExists ? persistedConversationId : latestConversation?.conversationId || '';
      activeConversationIdState.value = nextConversationId;
      persistActiveConversationId(nextConversationId);
    }

    return sorted;
  } finally {
    loadingState.value = false;
  }
};

const createConversation = async (title?: string): Promise<Conversation> => {
  const payload = title && title.trim().length > 0 ? { title: title.trim() } : {};
  const created = await conversationService.createConversation(payload);
  conversationsState.value = sortConversations([
    created,
    ...conversationsState.value.filter((it) => it.conversationId !== created.conversationId),
  ]);
  activeConversationIdState.value = created.conversationId;
  persistActiveConversationId(created.conversationId);
  return created;
};

const renameConversation = async (conversationId: string, title: string): Promise<Conversation> => {
  const renamed = await conversationService.renameConversation(conversationId, { title: title.trim() });
  conversationsState.value = sortConversations(
    conversationsState.value.map((item) => (item.conversationId === conversationId ? renamed : item)),
  );
  return renamed;
};

const togglePinConversation = async (conversationId: string, pinned: boolean): Promise<Conversation> => {
  const updated = await conversationService.pinConversation(conversationId, { pinned });
  conversationsState.value = sortConversations(
    conversationsState.value.map((item) => (item.conversationId === conversationId ? updated : item)),
  );
  return updated;
};

const deleteConversation = async (conversationId: string): Promise<void> => {
  const currentConversations = conversationsState.value;
  const deleteIndex = currentConversations.findIndex((item) => item.conversationId === conversationId);

  await conversationService.deleteConversation(conversationId);

  if (deleteIndex === -1) {
    await refreshConversations();
    return;
  }

  const remainingConversations = currentConversations.filter((item) => item.conversationId !== conversationId);
  conversationsState.value = remainingConversations;

  if (activeConversationIdState.value !== conversationId) {
    return;
  }

  const nextConversationId =
    remainingConversations[deleteIndex]?.conversationId ??
    remainingConversations[deleteIndex - 1]?.conversationId ??
    '';

  if (nextConversationId) {
    setActiveConversation(nextConversationId);
    return;
  }

  setActiveConversation('');
  await createConversation();
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
    togglePinConversation,
    deleteConversation,
    setActiveConversation,
  };
}
