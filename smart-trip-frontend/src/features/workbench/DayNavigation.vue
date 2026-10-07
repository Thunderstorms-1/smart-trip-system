<script setup lang="ts">
import type { Trip } from "../types";
defineProps<{ trip: Trip; editable: boolean; busy: boolean }>();
const dayIndex = defineModel<number>({ required: true });
const emit = defineEmits<{ "add-day": [] }>();
</script>
<template>
  <nav class="wb-day-rail editor-sidebar" aria-label="行程天数">
    <span class="wb-rail-caption">天数</span>
    <div class="wb-day-list">
      <button
        v-for="(d, n) in trip.days"
        :key="d.id || n"
        type="button"
        class="wb-day-button"
        :class="{ active: dayIndex === n }"
        :aria-pressed="dayIndex === n"
        :aria-label="'第 ' + (n + 1) + ' 天 · ' + (d.dayTheme || '自由探索')"
        :title="d.dayTheme || '自由探索'"
        @click="dayIndex = n"
      >
        <small>DAY</small><strong>{{ String(n + 1).padStart(2, "0") }}</strong>
      </button>
    </div>
    <button
      v-if="editable"
      type="button"
      class="wb-add-day"
      :disabled="busy || trip.days.length >= 30"
      aria-label="＋ 添加一天"
      title="添加一天"
      @click="emit('add-day')"
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.7"
        aria-hidden="true"
      >
        <path d="M12 5v14M5 12h14" />
      </svg>
    </button>
  </nav>
</template>
