import { readonly, ref } from 'vue';
import { conversationService } from '@/services/conversation';
import type { Conversation, ConversationMode } from '@/types/conversation';
import { useLoginUserStore } from '@/stores/loginUser';
import { pinia } from '@/stores';

const conversationsState = ref<Conversation[]>([]);
const activeConversationIdState = ref('');
const draftModeState = ref<ConversationMode>('CHAT');
const draftVersionState = ref(0);
const draftActiveState = ref(false);
const loadingState = ref(false);
const initializedState = ref(false);
let initializePromise: Promise<void> | null = null;

const getStorageKey = () => {
  const loginUserStore = useLoginUserStore(pinia);
  return `ai-literature.activeConversationId:${loginUserStore.loginUser?.userId ?? 'anonymous'}`;
};

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
  draftActiveState.value = false;
  const conversation = conversationsState.value.find((item) => item.conversationId === conversationId);
  if (conversation) {
    draftModeState.value = conversation.mode;
  }
  persistActiveConversationId(conversationId);
};

const startDraft = (mode: ConversationMode = draftModeState.value) => {
  activeConversationIdState.value = '';
  draftModeState.value = mode;
  draftActiveState.value = true;
  draftVersionState.value += 1;
  persistActiveConversationId('');
};

const setDraftMode = (mode: ConversationMode) => {
  draftModeState.value = mode;
};

const markConversationMode = (conversationId: string, mode: ConversationMode) => {
  conversationsState.value = conversationsState.value.map((item) =>
    item.conversationId === conversationId ? { ...item, mode } : item,
  );
  if (activeConversationIdState.value === conversationId) {
    draftModeState.value = mode;
  }
};

const readPersistedActiveConversationId = (): string => {
  if (typeof window === 'undefined') {
    return '';
  }
  return window.localStorage.getItem(getStorageKey()) || '';
};

const persistActiveConversationId = (conversationId: string) => {
  if (typeof window === 'undefined') {
    return;
  }
  const activeConversationStorageKey = getStorageKey();
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
      draftActiveState.value = true;
      persistActiveConversationId('');
      return sorted;
    }

    if (draftActiveState.value) {
      return sorted;
    }

    const activeExists = sorted.some((item) => item.conversationId === activeConversationIdState.value);
    const persistedConversationId = readPersistedActiveConversationId();
    const persistedExists = sorted.some((item) => item.conversationId === persistedConversationId);
    const latestConversation = sorted[0];
    if (!activeExists) {
      const nextConversationId = persistedExists ? persistedConversationId : latestConversation?.conversationId || '';
      activeConversationIdState.value = nextConversationId;
      draftModeState.value =
        sorted.find((item) => item.conversationId === nextConversationId)?.mode ?? 'CHAT';
      persistActiveConversationId(nextConversationId);
    }

    return sorted;
  } finally {
    loadingState.value = false;
  }
};

const createConversation = async (
  mode: ConversationMode = 'CHAT',
  title?: string,
  activate = true,
): Promise<Conversation> => {
  const payload = {
    mode,
    ...(title && title.trim().length > 0 ? { title: title.trim() } : {}),
  };
  const created = await conversationService.createConversation(payload);
  conversationsState.value = sortConversations([
    created,
    ...conversationsState.value.filter((it) => it.conversationId !== created.conversationId),
  ]);
  if (activate) {
    setActiveConversation(created.conversationId);
  }
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
  const deletedMode = currentConversations[deleteIndex]?.mode ?? 'CHAT';

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

  startDraft(deletedMode);
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
    await refreshConversations();
    initializedState.value = true;
  })();

  try {
    await initializePromise;
  } finally {
    initializePromise = null;
  }
};

const resetConversationState = () => {
  conversationsState.value = [];
  activeConversationIdState.value = '';
  draftModeState.value = 'CHAT';
  draftVersionState.value = 0;
  draftActiveState.value = false;
  loadingState.value = false;
  initializedState.value = false;
  initializePromise = null;
  persistActiveConversationId('');
};

export function useConversationState() {
  return {
    conversations: readonly(conversationsState),
    activeConversationId: readonly(activeConversationIdState),
    draftMode: readonly(draftModeState),
    draftVersion: readonly(draftVersionState),
    isConversationLoading: readonly(loadingState),
    initializeConversations,
    refreshConversations,
    createConversation,
    startDraft,
    setDraftMode,
    markConversationMode,
    renameConversation,
    togglePinConversation,
    deleteConversation,
    setActiveConversation,
    resetConversationState,
  };
}
