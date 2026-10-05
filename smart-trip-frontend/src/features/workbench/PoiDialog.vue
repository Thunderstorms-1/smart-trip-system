<script setup lang="ts">
import type { Poi } from "../types";
defineProps<{
  poi: Poi[];
  destination: string;
  busy: boolean;
  error?: string;
  searched?: boolean;
  unavailable?: string;
}>();
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
    /><button class="button" :disabled="busy || !!unavailable">搜索</button>
  </form>
  <p class="muted">请核对城市、地址与地点名称，选择后只保存地点引用。</p>
  <p v-if="unavailable" class="notice">{{ unavailable }}</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
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
  <p v-if="!poi.length && !error && !unavailable" class="empty">
    {{
      busy
        ? "正在搜索真实地点…"
        : searched
          ? "没有找到有效候选地点，请核对名称或更换关键词。"
          : "输入名称搜索候选地点。"
    }}
  </p>
</template>
