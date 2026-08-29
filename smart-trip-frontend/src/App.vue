<template>
  <RouterView />
</template>

<script setup>
import { RouterView } from 'vue-router'
</script>

<style>
/* 全局重置样式 */
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}
body {
  margin: 0;
}
</style>