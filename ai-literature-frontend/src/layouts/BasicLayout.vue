<template>
  <a-layout class="basic-layout">
    <!-- 顶部导航栏 -->
    <a-layout-header class="header">
      <div class="header-left">
        <img alt="logo" class="logo" src="@/assets/logo.svg" />
        <span class="title">AI Literature</span>
      </div>
      <div class="header-right">
        <!-- 展示登录用户的头像，用登录按钮代替 -->
        <a-button type="primary">登录</a-button>
      </div>
    </a-layout-header>

    <a-layout class="main-layout">
      <!-- 左侧边栏 -->
      <a-layout-sider width="280" theme="light" class="left-sider">
        <div class="sider-header">
          <span class="sider-title">历史记录</span>
          <a-button type="text" shape="circle">
            <template #icon><MenuFoldOutlined /></template>
          </a-button>
        </div>
        <div class="menu-container">
          <a-menu mode="inline" :selectedKeys="['1']" class="menu">
            <!-- 历史记录导航栏 -->
            <a-menu-item key="1">
              <template #icon><FileTextOutlined /></template>
              示例会话 1
            </a-menu-item>
            <a-menu-item key="2">
              <template #icon><FileTextOutlined /></template>
              示例会话 2
            </a-menu-item>
          </a-menu>
        </div>
      </a-layout-sider>

      <!-- 中间内容区域 -->
      <a-layout-content class="content">
        <div class="chat-placeholder">
          <!-- 对话页面，可以根据左侧测边框进行导航 -->
          <router-view />
        </div>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import {
  MenuFoldOutlined,
  FileTextOutlined
} from '@ant-design/icons-vue'
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
  font-weight: 500;
  color: #333;
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
  box-shadow: 0 1px 3px rgba(0,0,0,0.05);
  overflow: hidden;
}

.left-sider {
  display: flex;
  flex-direction: column;
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
  padding: 16px;
  font-size: 16px;
  font-weight: 500;
  border-bottom: 1px solid #f0f0f0;
}

.menu-container {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}

.menu {
  border-right: none;
}

.chat-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
}
</style>
