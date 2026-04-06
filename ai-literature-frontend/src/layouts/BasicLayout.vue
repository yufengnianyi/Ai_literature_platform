<template>
  <a-layout class="basic-layout">
    <a-layout-header class="shell-header">
      <div class="brand-block">
        <div class="brand-mark">
          <img alt="AI Literature" class="logo" src="@/assets/img.png" />
        </div>
        <div class="brand-copy">
          <span class="brand-title">AI Literature</span>
          <span class="brand-subtitle">Session workspace</span>
        </div>
      </div>

      <div class="header-actions">
        <a-space :size="12">
          <a-button :type="isHomeRoute ? 'primary' : 'default'" @click="goHome">Chat</a-button>
          <a-button :type="isReviewRoute ? 'primary' : 'default'" @click="goReview">Review</a-button>
          <a-button
            v-if="isAdmin"
            :type="isAdminRoute ? 'primary' : 'default'"
            @click="goUserManage"
          >
            Users
          </a-button>
        </a-space>

        <a-dropdown placement="bottomRight">
          <div class="user-pill">
            <a-avatar class="user-avatar" :size="34" :src="loginUser?.userAvatar">
              <template #icon><UserOutlined /></template>
            </a-avatar>
            <div class="user-copy">
              <span class="user-label">{{ isAdmin ? 'Administrator' : 'Account' }}</span>
              <span class="username">{{ displayName }}</span>
            </div>
          </div>

          <template #overlay>
            <a-menu @click="handleUserMenuClick">
              <a-menu-item key="chat">Chat</a-menu-item>
              <a-menu-item key="review">Review</a-menu-item>
              <a-menu-item v-if="isAdmin" key="manage">User Manage</a-menu-item>
              <a-menu-divider />
              <a-menu-item key="logout" danger>Logout</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </a-layout-header>

    <a-layout class="main-layout">
      <a-layout-sider
        :width="312"
        :collapsed-width="72"
        :collapsed="collapsed"
        theme="light"
        class="left-sider"
        :trigger="null"
      >
        <div class="sider-panel">
          <div class="sider-header" :class="{ 'sider-header-collapsed': collapsed }">
            <div v-if="!collapsed" class="sider-copy">
              <span class="sider-title">Conversations</span>
              <span class="sider-subtitle">{{ loginUser?.userAccount }}</span>
            </div>

            <div class="sider-actions" :class="{ 'sider-actions-collapsed': collapsed }">
              <a-tooltip title="New conversation">
                <a-button
                  class="sider-button sider-button-primary"
                  type="text"
                  shape="circle"
                  @click="handleCreateConversation"
                >
                  <template #icon><PlusOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="collapsed ? 'Expand sidebar' : 'Collapse sidebar'">
                <a-button class="sider-button" type="text" shape="circle" @click="toggleCollapse">
                  <template #icon>
                    <MenuUnfoldOutlined v-if="collapsed" />
                    <MenuFoldOutlined v-else />
                  </template>
                </a-button>
              </a-tooltip>
            </div>
          </div>

          <div class="conversation-list">
            <template v-if="conversationSections.length > 0">
              <section
                v-for="section in conversationSections"
                :key="section.key"
                class="conversation-section"
              >
                <div v-if="!collapsed" class="section-header">
                  <span class="section-title">{{ section.title }}</span>
                  <span class="section-count">{{ section.items.length }}</span>
                </div>

                <div class="section-items">
                  <div
                    v-for="item in section.items"
                    :key="item.conversationId"
                    class="conversation-item"
                    :class="{
                      'conversation-item-active': item.conversationId === activeConversationId,
                      'conversation-item-collapsed': collapsed,
                      'conversation-item-pinned': item.pinned,
                    }"
                    @click="handleSelectConversation(item.conversationId)"
                  >
                    <span v-if="item.pinned" class="pin-marker"></span>

                    <template v-if="editingConversationId === item.conversationId && !collapsed">
                      <div class="rename-panel" @click.stop>
                        <a-input
                          ref="renameInputRef"
                          v-model:value="editingTitle"
                          size="large"
                          class="rename-input"
                          maxlength="255"
                          @pressEnter="handleSubmitRename(item.conversationId)"
                        />
                        <div class="rename-actions">
                          <button type="button" class="rename-button" @click="cancelRename">Cancel</button>
                          <button
                            type="button"
                            class="rename-button rename-button-primary"
                            @click="handleSubmitRename(item.conversationId)"
                          >
                            Save
                          </button>
                        </div>
                      </div>
                    </template>

                    <template v-else>
                      <div class="conversation-icon-shell">
                        <PushpinFilled v-if="item.pinned" class="conversation-pin-icon" />
                        <FileTextOutlined class="conversation-icon" />
                      </div>

                      <div v-if="!collapsed" class="conversation-body">
                        <div class="conversation-row">
                          <div class="conversation-main">
                            <div class="conversation-title">{{ item.title }}</div>
                            <div class="conversation-meta">
                              {{ item.pinned ? 'Pinned' : 'Updated' }} - {{ formatConversationDate(item.updatedAt) }}
                            </div>
                          </div>

                          <div class="conversation-side">
                            <span class="conversation-time">{{ formatConversationTime(item.updatedAt) }}</span>
                            <a-dropdown :trigger="['click']" placement="bottomRight">
                              <a-button
                                class="conversation-menu-button"
                                type="text"
                                size="small"
                                :disabled="isConversationBusy(item.conversationId)"
                                @click.stop
                              >
                                <template #icon><MoreOutlined /></template>
                              </a-button>

                              <template #overlay>
                                <a-menu @click="handleConversationMenuClick($event, item.conversationId, item.title, item.pinned)">
                                  <a-menu-item key="rename">
                                    <EditOutlined />
                                    Rename
                                  </a-menu-item>
                                  <a-menu-item key="pin">
                                    <PushpinOutlined />
                                    {{ item.pinned ? 'Unpin' : 'Pin' }}
                                  </a-menu-item>
                                  <a-menu-divider />
                                  <a-menu-item key="delete" danger>
                                    <DeleteOutlined />
                                    Delete
                                  </a-menu-item>
                                </a-menu>
                              </template>
                            </a-dropdown>
                          </div>
                        </div>
                      </div>
                    </template>
                  </div>
                </div>
              </section>
            </template>

            <div v-else-if="!isConversationLoading && !collapsed" class="empty-text">
              No conversations yet. Create one to get started.
            </div>
          </div>
        </div>
      </a-layout-sider>

      <a-layout-content class="content">
        <div class="content-shell">
          <router-view />
        </div>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message, Modal } from 'ant-design-vue';
import {
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MoreOutlined,
  PlusOutlined,
  PushpinFilled,
  PushpinOutlined,
  UserOutlined,
} from '@ant-design/icons-vue';
import { logout } from '@/api/usersController';
import { useConversationState } from '@/composables/useConversationState';
import { useLoginUserStore } from '@/stores/loginUser';
import { USER_ROLE_ADMIN } from '@/constants/user';

interface MenuClickEvent {
  key: string | number;
}

const router = useRouter();
const route = useRoute();
const loginUserStore = useLoginUserStore();
const collapsed = ref(false);
const editingConversationId = ref('');
const editingTitle = ref('');
const deletingConversationId = ref('');
const pinningConversationId = ref('');
const renameInputRef = ref();

const {
  conversations,
  activeConversationId,
  isConversationLoading,
  initializeConversations,
  createConversation,
  deleteConversation,
  renameConversation,
  togglePinConversation,
  setActiveConversation,
  resetConversationState,
} = useConversationState();

const loginUser = computed(() => loginUserStore.loginUser);
const isAdmin = computed(() => loginUser.value?.userRole === USER_ROLE_ADMIN);
const displayName = computed(() => loginUser.value?.userName || loginUser.value?.userAccount || 'Workspace user');
const isHomeRoute = computed(() => route.path === '/');
const isReviewRoute = computed(() => route.path === '/review');
const isAdminRoute = computed(() => route.path.startsWith('/admin'));

const pinnedConversations = computed(() => conversations.value.filter((item) => item.pinned));
const recentConversations = computed(() => conversations.value.filter((item) => !item.pinned));

const conversationSections = computed(() => {
  if (collapsed.value) {
    return [{ key: 'all', title: 'All', items: conversations.value }];
  }

  return [
    ...(pinnedConversations.value.length > 0
      ? [{ key: 'pinned', title: 'Pinned', items: pinnedConversations.value }]
      : []),
    ...(recentConversations.value.length > 0
      ? [{ key: 'recent', title: 'Recent', items: recentConversations.value }]
      : []),
  ];
});

const timeFormatter = new Intl.DateTimeFormat('en-US', {
  hour: 'numeric',
  minute: '2-digit',
});

const dateFormatter = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
});

const fullDateFormatter = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  year: 'numeric',
});

const toggleCollapse = () => {
  collapsed.value = !collapsed.value;
};

const isConversationBusy = (conversationId: string) => {
  return deletingConversationId.value === conversationId || pinningConversationId.value === conversationId;
};

const formatConversationTime = (value: string) => timeFormatter.format(new Date(value));

const formatConversationDate = (value: string) => {
  const date = new Date(value);
  const now = new Date();
  return now.getFullYear() === date.getFullYear() ? dateFormatter.format(date) : fullDateFormatter.format(date);
};

const handleCreateConversation = async () => {
  try {
    cancelRename();
    await createConversation();
    if (!isHomeRoute.value) {
      await router.push('/');
    }
  } catch (error) {
    console.error(error);
    message.error('Failed to create conversation');
  }
};

const handleSelectConversation = async (conversationId: string) => {
  if (editingConversationId.value && editingConversationId.value !== conversationId) {
    cancelRename();
  }
  setActiveConversation(conversationId);
  if (!isHomeRoute.value) {
    await router.push('/');
  }
};

const startRename = async (conversationId: string, currentTitle: string) => {
  editingConversationId.value = conversationId;
  editingTitle.value = currentTitle;
  await nextTick();
  renameInputRef.value?.focus?.();
};

const cancelRename = () => {
  editingConversationId.value = '';
  editingTitle.value = '';
};

const handleSubmitRename = async (conversationId: string) => {
  const title = editingTitle.value.trim();
  if (!title) {
    message.warning('Conversation title cannot be empty');
    return;
  }
  if (title.length > 255) {
    message.warning('Conversation title length must be 255 characters or fewer');
    return;
  }

  try {
    await renameConversation(conversationId, title);
    cancelRename();
  } catch (error) {
    console.error(error);
    message.error('Failed to rename conversation');
  }
};

const handleTogglePin = async (conversationId: string, pinned: boolean) => {
  if (pinningConversationId.value) {
    return;
  }
  pinningConversationId.value = conversationId;
  try {
    const updated = await togglePinConversation(conversationId, pinned);
    message.success(updated.pinned ? 'Conversation pinned' : 'Conversation unpinned');
  } catch (error) {
    console.error(error);
    message.error('Failed to update conversation pin');
  } finally {
    pinningConversationId.value = '';
  }
};

const handleDeleteConversation = async (conversationId: string) => {
  if (deletingConversationId.value) {
    return;
  }
  deletingConversationId.value = conversationId;
  cancelRename();

  try {
    await deleteConversation(conversationId);
    message.success('Conversation deleted');
  } catch (error) {
    console.error(error);
    message.error('Failed to delete conversation');
  } finally {
    deletingConversationId.value = '';
  }
};

const confirmDeleteConversation = (conversationId: string) => {
  Modal.confirm({
    title: 'Delete this conversation permanently?',
    content: 'The message history in this conversation will be removed.',
    okText: 'Delete',
    cancelText: 'Cancel',
    okType: 'danger',
    onOk: async () => {
      await handleDeleteConversation(conversationId);
    },
  });
};

const handleConversationMenuClick = (
  event: MenuClickEvent,
  conversationId: string,
  title: string,
  pinned: boolean,
) => {
  const action = String(event.key);
  if (action === 'rename') {
    void startRename(conversationId, title);
    return;
  }
  if (action === 'pin') {
    void handleTogglePin(conversationId, !pinned);
    return;
  }
  if (action === 'delete') {
    confirmDeleteConversation(conversationId);
  }
};

const goHome = async () => {
  await router.push('/');
};

const goReview = async () => {
  await router.push('/review');
};

const goUserManage = async () => {
  if (isAdmin.value) {
    await router.push('/admin/user-manage');
  }
};

const handleLogout = async () => {
  try {
    await logout();
  } finally {
    resetConversationState();
    loginUserStore.clearLoginUser();
    await router.push('/user/login');
  }
};

const handleUserMenuClick = async ({ key }: MenuClickEvent) => {
  if (key === 'chat') {
    await goHome();
    return;
  }
  if (key === 'review') {
    await goReview();
    return;
  }
  if (key === 'manage') {
    await goUserManage();
    return;
  }
  if (key === 'logout') {
    await handleLogout();
  }
};

onMounted(async () => {
  try {
    await initializeConversations();
  } catch (error) {
    console.error(error);
    message.error('Failed to load conversations');
  }
});
</script>

<style scoped>
.basic-layout {
  height: 100vh;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.08), transparent 26%),
    linear-gradient(180deg, #f3f7ff, #eef4fb);
  color: var(--app-text);
  overflow: hidden;
}

.shell-header {
  height: 72px;
  padding: 0 20px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  border-bottom: 1px solid #dbe7f5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.brand-block,
.header-actions,
.user-pill,
.brand-copy,
.user-copy {
  display: flex;
  align-items: center;
}

.brand-block {
  gap: 12px;
  min-width: 0;
}

.brand-mark {
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.logo {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.brand-copy {
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.1;
}

.brand-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.brand-subtitle {
  margin-top: 4px;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.16em;
  color: #64748b;
}

.header-actions {
  gap: 14px;
}

.user-pill {
  gap: 10px;
  padding: 8px 12px;
  border-radius: 14px;
  background: linear-gradient(180deg, #ffffff, #f8fbff);
  border: 1px solid #dbe7f5;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.04);
  cursor: pointer;
}

.user-avatar {
  background: #2563eb;
}

.user-copy {
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.1;
}

.user-label {
  font-size: 11px;
  color: #64748b;
}

.username {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.main-layout {
  flex: 1;
  min-height: 0;
  background: transparent;
  padding: 16px;
  gap: 16px;
  overflow: hidden;
}

.left-sider,
.content {
  background: #ffffff !important;
  border: 1px solid #dbe7f5;
  border-radius: 16px;
  box-shadow: 0 8px 24px rgba(37, 99, 235, 0.05);
  overflow: hidden;
}

.left-sider {
  min-width: 0 !important;
}

.sider-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.sider-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid #eff6ff;
}

.sider-header-collapsed {
  justify-content: center;
}

.sider-copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.sider-title {
  display: block;
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.sider-subtitle {
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
}

.sider-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.sider-actions-collapsed {
  flex-direction: column;
}

.sider-button {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: #475569;
  border: 1px solid #dbe7f5;
  background: #ffffff;
}

.sider-button:hover {
  color: #2563eb;
  background: #f8fbff;
}

.sider-button-primary {
  color: #ffffff;
  background: #2563eb;
  border-color: #2563eb;
}

.sider-button-primary:hover {
  color: #ffffff;
  background: #1d4ed8;
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.conversation-section + .conversation-section {
  margin-top: 16px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 8px;
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.section-count {
  font-size: 12px;
  color: #94a3b8;
}

.section-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.conversation-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: 14px;
  background: #ffffff;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease;
}

.conversation-item:hover {
  background: #f8fbff;
  border-color: #dbeafe;
}

.conversation-item-active {
  background: #eff6ff;
  border-color: #bfdbfe;
}

.conversation-item-collapsed {
  justify-content: center;
  align-items: center;
  padding: 12px 8px;
}

.pin-marker {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #2563eb;
}

.conversation-icon-shell {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 10px;
  border: 1px solid #dbe7f5;
  background: #f8fbff;
  color: #2563eb;
  position: relative;
}

.conversation-pin-icon {
  position: absolute;
  top: 4px;
  right: 4px;
  font-size: 9px;
  color: #2563eb;
}

.conversation-body {
  min-width: 0;
  flex: 1;
}

.conversation-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.conversation-main {
  min-width: 0;
  flex: 1;
}

.conversation-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.conversation-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.conversation-side {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.conversation-time {
  font-size: 12px;
  color: #94a3b8;
}

.conversation-menu-button {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: #64748b;
}

.conversation-menu-button:hover {
  color: #2563eb;
  background: #eff6ff;
}

.rename-panel {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rename-input :deep(.ant-input) {
  height: 40px;
  border-radius: 10px;
  border-color: #cbd5e1;
}

.rename-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.rename-button {
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid #dbe7f5;
  background: #ffffff;
  color: #475569;
  cursor: pointer;
}

.rename-button-primary {
  color: #ffffff;
  background: #2563eb;
  border-color: #2563eb;
}

.empty-text {
  padding: 12px 4px;
  color: #64748b;
  font-size: 13px;
}

.content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.content-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  background: #ffffff;
}

@media (max-width: 960px) {
  .shell-header {
    height: auto;
    padding: 14px 16px;
    flex-direction: column;
    align-items: stretch;
  }

  .header-actions {
    justify-content: space-between;
  }

  .main-layout {
    padding: 12px;
    gap: 12px;
  }
}
</style>
