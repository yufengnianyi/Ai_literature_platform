<template>
  <a-layout class="basic-layout">
    <a-layout-header class="header">
      <div class="header-left">
        <img alt="logo" class="logo" src="@/assets/img.png" />
        <span class="title">AI Literature</span>
      </div>
      <div class="header-right">
        <a-avatar class="user-avatar" :size="34">
          <template #icon><UserOutlined /></template>
        </a-avatar>
        <span class="username">{{ DEFAULT_USER.username }}</span>
      </div>
    </a-layout-header>

    <a-layout class="main-layout">
      <a-layout-sider
        :width="300"
        :collapsed-width="64"
        :collapsed="collapsed"
        theme="light"
        class="left-sider"
        :trigger="null"
      >
        <div class="sider-header" :class="{ 'sider-header-collapsed': collapsed }">
          <span v-if="!collapsed" class="sider-title">Conversations</span>
          <div class="sider-actions">
            <a-tooltip title="New conversation">
              <a-button type="text" shape="circle" @click="handleCreateConversation">
                <template #icon><PlusOutlined /></template>
              </a-button>
            </a-tooltip>
            <a-button type="text" shape="circle" @click="toggleCollapse">
              <template #icon>
                <MenuUnfoldOutlined v-if="collapsed" />
                <MenuFoldOutlined v-else />
              </template>
            </a-button>
          </div>
        </div>

        <div class="conversation-list">
          <div
            v-for="item in conversations"
            :key="item.conversationId"
            class="conversation-item"
            :class="{
              'conversation-item-active': item.conversationId === activeConversationId,
              'conversation-item-collapsed': collapsed
            }"
            @click="handleSelectConversation(item.conversationId)"
          >
            <template v-if="editingConversationId === item.conversationId && !collapsed">
              <a-input
                ref="renameInputRef"
                v-model:value="editingTitle"
                size="small"
                class="rename-input"
                @pressEnter="handleSubmitRename(item.conversationId)"
                @click.stop
              />
              <div class="rename-actions">
                <a-button type="text" size="small" @click.stop="handleSubmitRename(item.conversationId)">
                  <template #icon><CheckOutlined /></template>
                </a-button>
                <a-button type="text" size="small" @click.stop="cancelRename">
                  <template #icon><CloseOutlined /></template>
                </a-button>
              </div>
            </template>

            <template v-else>
              <FileTextOutlined class="conversation-icon" />
              <span v-if="!collapsed" class="conversation-title">{{ item.title }}</span>
              <a-button
                v-if="!collapsed"
                class="rename-trigger"
                type="text"
                size="small"
                @click.stop="startRename(item.conversationId, item.title)"
              >
                <template #icon><EditOutlined /></template>
              </a-button>
            </template>
          </div>

          <div v-if="!isConversationLoading && conversations.length === 0 && !collapsed" class="empty-text">
            No conversation yet. Click + to create one.
          </div>
        </div>
      </a-layout-sider>

      <a-layout-content class="content">
        <div class="chat-placeholder">
          <router-view />
        </div>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import {
  CheckOutlined,
  CloseOutlined,
  EditOutlined,
  FileTextOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlusOutlined,
  UserOutlined,
} from '@ant-design/icons-vue';
import { useConversationState } from '@/composables/useConversationState';
import { DEFAULT_USER } from '@/constants/user';

const collapsed = ref(false);
const editingConversationId = ref('');
const editingTitle = ref('');
const renameInputRef = ref();

const {
  conversations,
  activeConversationId,
  isConversationLoading,
  initializeConversations,
  createConversation,
  renameConversation,
  setActiveConversation,
} = useConversationState();

const toggleCollapse = () => {
  collapsed.value = !collapsed.value;
};

const handleCreateConversation = async () => {
  try {
    cancelRename();
    await createConversation();
  } catch (error) {
    console.error(error);
    message.error('Failed to create conversation');
  }
};

const handleSelectConversation = (conversationId: string) => {
  if (editingConversationId.value && editingConversationId.value !== conversationId) {
    cancelRename();
  }
  setActiveConversation(conversationId);
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
    message.warning('Conversation title length must be <= 255');
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
  display: flex;
  flex-direction: column;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #f8f9fa;
  border-bottom: 1px solid #e8e8e8;
  padding: 0 24px;
  height: 64px;
}

.header-left {
  display: flex;
  align-items: center;
}

.logo {
  height: 32px;
  margin-right: 12px;
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: #1f1f1f;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-avatar {
  background: #1677ff;
}

.username {
  font-size: 14px;
  color: #1f1f1f;
  font-weight: 500;
}

.main-layout {
  flex: 1;
  overflow: hidden;
  background-color: #f0f2f5;
  padding: 16px;
  gap: 16px;
}

.left-sider,
.content {
  background-color: #fff !important;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.left-sider {
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
  min-width: 0 !important;
}

.content {
  flex: 1;
  margin: 0;
  overflow-y: auto;
  background-color: #fff;
}

.sider-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 12px 14px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.sider-header-collapsed {
  justify-content: center;
}

.sider-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f1f1f;
}

.sider-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.conversation-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s ease;
  margin-bottom: 6px;
}

.conversation-item:hover {
  background-color: #f5f5f5;
}

.conversation-item-active {
  background-color: #e6f4ff;
  color: #1677ff;
}

.conversation-item-collapsed {
  justify-content: center;
  padding: 10px 0;
}

.conversation-icon {
  font-size: 14px;
  flex-shrink: 0;
}

.conversation-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.rename-trigger {
  opacity: 0;
  transition: opacity 0.2s ease;
}

.conversation-item:hover .rename-trigger,
.conversation-item-active .rename-trigger {
  opacity: 1;
}

.rename-input {
  flex: 1;
}

.rename-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.empty-text {
  color: #8c8c8c;
  font-size: 13px;
  padding: 8px 4px;
}

.chat-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
}
</style>
