<script setup lang="ts">
import type { Trip } from "../types";
defineProps<{ trip: Trip; editable: boolean; busy: boolean }>();
const dayIndex = defineModel<number>({ required: true });
const emit = defineEmits<{ "add-day": [] }>();
</script>
<template>
  <aside class="paper editor-sidebar">
    <button
      v-for="(d, n) in trip.days"
      :key="d.id || n"
      class="day-nav"
      :class="{ active: dayIndex === n }"
      @click="dayIndex = n"
    >
      <strong>{{ String(n + 1).padStart(2, "0") }}</strong
      ><span>{{ d.dayTheme || "自由探索" }}</span></button
    ><button
      v-if="editable"
      class="button secondary small full"
      :disabled="busy || trip.days.length >= 30"
      @click="emit('add-day')"
    >
      ＋ 添加一天
    </button>
  </aside>
</template>
