<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { Day } from "../types";
import HomeIcon from "../HomeIcon.vue";
const props = defineProps<{
  day: Day;
  dayIndex: number;
  estimated: string;
  duration: number;
  missingDuration: number;
  expanded: boolean;
  travelGroup?: string;
}>();
const emit = defineEmits<{ expand: [] }>();
const dayVersion = ref(0);
const direction = ref("wb-v5-day-forward");
const stageKey = computed(() => props.dayIndex + "-" + dayVersion.value);
const fullDate = computed(() => {
  const match = props.day.dayDate?.match(/^(\d{4})-(\d{2})-(\d{2})/);
  return match
    ? match[1] + "年" + Number(match[2]) + "月" + Number(match[3]) + "日"
    : "日期待定";
});
watch(
  () => props.dayIndex,
  (value, before) => {
    direction.value =
      value < before ? "wb-v5-day-backward" : "wb-v5-day-forward";
    dayVersion.value++;
  },
  { flush: "sync" },
);
// Outgoing views are display-only. A unique key prevents a rapid return from
// reviving a stale view with old event bindings or duplicated landmark IDs.
function retireDay(element: Element) {
  const host = element as HTMLElement;
  host.inert = true;
  host.setAttribute("aria-hidden", "true");
  for (const node of [host, ...host.querySelectorAll<HTMLElement>("*")]) {
    node.removeAttribute("id");
    node.removeAttribute("aria-controls");
    node.removeAttribute("data-node-key");
    node.removeAttribute("data-activity-key");
    if (node.matches("button, a, input, select, textarea, summary, [tabindex]"))
      node.tabIndex = -1;
  }
}
</script>
<template>
  <section
    class="wb-timeline-panel editor-panel"
    tabindex="-1"
    aria-label="当日行程时间轴"
  >
    <header class="wb-timeline-heading">
      <div class="wb-v5-day-heading-wrap">
        <Transition :name="direction" @before-leave="retireDay">
          <div :key="stageKey" class="wb-v5-day-heading">
            <span class="wb-v5-day-kicker">YOUR DAILY JOURNEY</span>
            <h1 :title="day.dayTheme || '自由探索'">
              <span class="wb-v5-day-word">DAY</span>
              {{ String(dayIndex + 1).padStart(2, "0") }}
            </h1>
            <p class="wb-v5-day-theme">{{ day.dayTheme || "自由探索" }}</p>
          </div>
        </Transition>
      </div>
      <button
        type="button"
        class="wb-drawer-toggle"
        :aria-label="expanded ? '收起时间轴' : '展开时间轴'"
        :title="expanded ? '收起时间轴' : '展开时间轴'"
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
      <div class="wb-v5-day-facts">
        <span
          ><HomeIcon name="clock" /><time :datetime="day.dayDate"
            ><span class="wb-v5-date-full">{{ fullDate }}</span
            ><span class="wb-v5-date-compact">{{
              day.dayDate || "日期待定"
            }}</span></time
          ></span
        >
        <span v-if="travelGroup"
          ><HomeIcon name="user" />{{ travelGroup }}</span
        >
        <span>{{ day.items.length }} 项安排</span>
        <span
          ><HomeIcon name="clock" />已安排 {{ duration }} 小时<span
            v-if="missingDuration"
          >
            · {{ missingDuration }} 项时长待补充</span
          ></span
        >
        <span class="wb-v5-day-budget">{{ estimated }}</span>
      </div>
      <slot name="details" />
      <div class="wb-v5-node-stage">
        <Transition :name="direction" @before-leave="retireDay">
          <div :key="stageKey" class="wb-timeline-nodes"><slot /></div>
        </Transition>
      </div>
      <footer class="wb-timeline-footer"><slot name="footer" /></footer>
    </div>
  </section>
</template>
