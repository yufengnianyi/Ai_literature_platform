<script setup lang="ts">
const theme = {
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorText: '#111827',
    colorTextSecondary: '#6b7280',
    colorBgBase: '#f5f7fb',
    borderRadius: 14,
    fontFamily: "'IBM Plex Sans', 'Segoe UI', sans-serif",
  },
};
</script>

<template>
  <a-config-provider :theme="theme">
    // 根据路由动态切换页面
    <router-view />
  </a-config-provider>
</template>

<style>
html,
body,
#app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
}
</style>
