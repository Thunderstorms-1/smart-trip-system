<script setup lang="ts">
import type { Day } from "../types";
import HomeIcon from "../HomeIcon.vue";
defineProps<{
  day: Day;
  dayIndex: number;
  estimated: string;
  duration: number;
  missingDuration: number;
  expanded: boolean;
}>();
const emit = defineEmits<{ expand: [] }>();
</script>
<template>
  <section class="wb-timeline-panel editor-panel" aria-label="当日行程时间轴">
    <header class="wb-timeline-heading">
      <div>
        <h1>{{ day.dayTheme || "自由探索" }}</h1>
        <p>
          第 {{ dayIndex + 1 }} 天 · {{ day.items.length }} 个安排<span
            v-if="day.dayDate"
          >
            · {{ day.dayDate }}</span
          >
        </p>
      </div>
      <button
        type="button"
        class="wb-drawer-toggle"
        :aria-expanded="expanded"
        aria-controls="workbench-timeline-content"
        @click="emit('expand')"
      >
        {{ expanded ? "收起时间轴" : "展开时间轴" }}<HomeIcon name="arrow" />
      </button>
    </header>
    <div
      id="workbench-timeline-content"
      class="wb-timeline-content"
      :class="{ 'is-collapsed': !expanded }"
    >
      <div class="wb-timeline-overview">
        <span
          ><HomeIcon name="clock" />已填 {{ duration }} 小时<span
            v-if="missingDuration"
          >
            · {{ missingDuration }} 项待补充</span
          ></span
        ><span>{{ estimated }}</span>
      </div>
      <slot name="details" />
      <div class="wb-timeline-nodes"><slot /></div>
      <footer class="wb-timeline-footer"><slot name="footer" /></footer>
    </div>
  </section>
</template>
