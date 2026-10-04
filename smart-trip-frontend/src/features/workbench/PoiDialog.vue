<script setup lang="ts">
import type { Poi } from "../types";
defineProps<{ poi: Poi[]; destination: string; busy: boolean }>();
const search = defineModel<string>({ required: true });
const emit = defineEmits<{ search: []; choose: [poi: Poi] }>();
</script>
<template>
  <form class="search-bar" @submit.prevent="emit('search')">
    <input
      v-model="search"
      required
      maxlength="100"
      aria-label="地点名称"
      :placeholder="destination + '的地点'"
    /><button class="button" :disabled="busy">搜索</button>
  </form>
  <p class="muted">请核对城市与地点名称，选择后才会关联到节点。</p>
  <div class="poi-list">
    <button
      v-for="p in poi"
      :key="p.poiId"
      class="poi-option"
      @click="emit('choose', p)"
    >
      <strong>{{ p.name }}</strong
      ><small>{{ p.address }} · 高德</small>
    </button>
  </div>
  <p v-if="!poi.length" class="empty">输入名称搜索候选地点。</p>
</template>
