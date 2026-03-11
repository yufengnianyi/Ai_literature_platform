<script setup lang="ts">
import BasicLayout from './layouts/BasicLayout.vue'
</script>

<template>
  <a-config-provider>
    <BasicLayout />
  </a-config-provider>
</template>

<style>
/* 全局重置样式 */
html, body {
  margin: 0;
  padding: 0;
  height: 100%;
  width: 100%;
}

#app {
  height: 100%;
  width: 100%;
}
</style>
