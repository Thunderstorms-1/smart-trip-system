<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from "vue";
import type { Trip, Day } from "../types";
function dateLabel(day: Day) {
  const value = day.dayDate?.match(/^\d{4}-(\d{2})-(\d{2})/);
  return value ? Number(value[1]) + "月" + Number(value[2]) + "日" : "日期未定";
}
const props = defineProps<{ trip: Trip; editable: boolean; busy: boolean }>();
const dayIndex = defineModel<number>({ required: true });
const emit = defineEmits<{ "add-day": [] }>();
const list = ref<HTMLElement>();
const selection = ref<Record<string, string>>({});
const selectionReady = ref(false);
let observer: ResizeObserver | undefined;
let epoch = 0;
let disposed = false;
function positionSelection(reveal = false) {
  const host = list.value;
  const active = host?.querySelector<HTMLElement>(".wb-day-button.active");
  if (!host || !active) return;
  if (!selectionReady.value) {
    const material = getComputedStyle(active);
    selection.value = {
      background: material.background,
      border: material.border,
      borderRadius: material.borderRadius,
      boxShadow: material.boxShadow,
    };
  }
  selection.value = {
    ...selection.value,
    width: active.offsetWidth + "px",
    height: active.offsetHeight + "px",
    transform: `translate3d(${active.offsetLeft}px, ${active.offsetTop}px, 0)`,
  };
  selectionReady.value = true;
  if (reveal) {
    const left = active.offsetLeft,
      top = active.offsetTop;
    if (left < host.scrollLeft) host.scrollLeft = left;
    else if (left + active.offsetWidth > host.scrollLeft + host.clientWidth)
      host.scrollLeft = left + active.offsetWidth - host.clientWidth;
    if (top < host.scrollTop) host.scrollTop = top;
    else if (top + active.offsetHeight > host.scrollTop + host.clientHeight)
      host.scrollTop = top + active.offsetHeight - host.clientHeight;
  }
}
watch(
  [dayIndex, () => props.trip.id, () => props.trip.days.length],
  async () => {
    const current = ++epoch;
    await nextTick();
    if (!disposed && current === epoch) positionSelection(true);
  },
  { flush: "post" },
);
onMounted(() => {
  positionSelection();
  observer = new ResizeObserver(() => positionSelection());
  if (list.value) observer.observe(list.value);
});
onBeforeUnmount(() => {
  disposed = true;
  epoch++;
  observer?.disconnect();
});
</script>
<template>
  <nav class="wb-day-rail editor-sidebar" aria-label="行程天数">
    <div class="wb-day-rail-heading">
      <span class="wb-rail-caption">旅程日历</span
      ><span class="wb-day-total">{{ trip.days.length }} 天行程</span>
    </div>
    <div
      ref="list"
      class="wb-day-list"
      :class="{ 'has-moving-selection': selectionReady }"
    >
      <span
        class="wb-day-selection"
        :style="selection"
        aria-hidden="true"
      ></span>
      <button
        v-for="(d, n) in trip.days"
        :key="d.id || n"
        type="button"
        class="wb-day-button"
        :class="{ active: dayIndex === n }"
        :aria-pressed="dayIndex === n"
        :aria-label="
          '第 ' +
          (n + 1) +
          ' 天 · ' +
          (d.dayDate || '日期未定') +
          ' · ' +
          (d.dayTheme || '自由探索') +
          ' · ' +
          d.items.length +
          ' 项安排'
        "
        :title="d.dayTheme || '自由探索'"
        @click="dayIndex = n"
      >
        <span class="wb-day-number"
          ><small>DAY</small
          ><strong>{{ String(n + 1).padStart(2, "0") }}</strong></span
        >
        <span class="wb-day-description"
          ><time class="wb-day-date" :datetime="d.dayDate">{{
            dateLabel(d)
          }}</time
          ><span class="wb-day-theme">{{ d.dayTheme || "自由探索" }}</span
          ><span class="wb-day-count">{{
            d.items.length ? d.items.length + " 项安排" : "待安排"
          }}</span></span
        >
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
        <path d="M12 5v14M5 12h14" /></svg
      ><span class="wb-add-day-label">添加一天</span>
    </button>
  </nav>
</template>
