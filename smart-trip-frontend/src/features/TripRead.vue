<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { Trip } from "./types";
import { money } from "./api";

const props = withDefaults(
  defineProps<{
    trip: Trip;
    workspace?: boolean;
    dayIndex?: number;
    allDays?: boolean;
  }>(),
  { workspace: false, dayIndex: 0, allDays: false },
);
const labels: Record<string, string> = {
  SCENIC: "游览",
  FOOD: "美食",
  HOTEL: "住宿",
  TRAFFIC: "交通",
  OTHER: "活动",
};
const direction = ref("read-day-forward");
const changingDay = ref(false);
let motionTimer: ReturnType<typeof setTimeout> | undefined;
watch(
  () => props.dayIndex,
  (value, before) => {
    direction.value = value < before ? "read-day-backward" : "read-day-forward";
    changingDay.value = true;
    clearTimeout(motionTimer);
    motionTimer = setTimeout(() => {
      changingDay.value = false;
    }, 300);
  },
);
onBeforeUnmount(() => clearTimeout(motionTimer));
function known(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}
function estimate(value: unknown) {
  return known(value) ? "预估 " + money(value) : "费用待补充";
}
const budgetLabel = computed(() => {
  if (!props.workspace) return "预估 " + money(props.trip.totalBudget);
  const items = props.trip.days.flatMap((day) => day.items);
  if (!items.length) return "费用待安排";
  const missing = items.filter((item) => !known(item.costEstimate)).length;
  if (missing === items.length) return "费用待补充 · " + missing + "项";
  const total = items.reduce(
    (sum, item) => sum + (known(item.costEstimate) ? item.costEstimate : 0),
    0,
  );
  return (
    (missing ? "已知预估 " : "预估 ") +
    money(total) +
    (missing ? " · " + missing + "项待补充" : "")
  );
});
function sectionId(index: number) {
  return "preview-day-" + (props.trip.id ?? "draft") + "-" + index;
}
function leaving(element: Element) {
  if (!props.workspace) return;
  (element as HTMLElement).inert = true;
  element.setAttribute("aria-hidden", "true");
}
function entering(element: Element) {
  (element as HTMLElement).inert = false;
  element.removeAttribute("aria-hidden");
}
</script>
<template>
  <div
    class="trip-read"
    :class="{ 'trip-read--workspace': workspace, 'is-all-days': allDays }"
  >
    <div class="meta-row">
      <span class="pill">{{ trip.destination }}</span
      ><span>{{ trip.days.length }} 天</span>
      <span>{{ trip.startDate || "日期待定" }}</span
      ><span>{{ budgetLabel }}</span>
    </div>
    <p v-if="!workspace || trip.summary" class="intro">
      {{ trip.summary || "留一点空白，给路上的惊喜。" }}
    </p>
    <div :class="{ 'read-day-stage': workspace }">
      <Transition
        v-for="(day, index) in trip.days"
        :key="day.id || index"
        :name="direction"
        :css="workspace && !allDays && changingDay"
        @before-leave="leaving"
        @before-enter="entering"
      >
        <section
          v-show="!workspace || allDays || dayIndex === index"
          :id="workspace ? sectionId(index) : undefined"
          class="day-block"
          :data-read-day="index"
        >
          <header class="day-heading">
            <span class="day-number">{{
              String(index + 1).padStart(2, "0")
            }}</span>
            <div>
              <small
                >DAY {{ index + 1 }} ·
                <time :datetime="day.dayDate">{{
                  day.dayDate || "日期待定"
                }}</time></small
              >
              <h2>{{ day.dayTheme || "自由探索" }}</h2>
            </div>
          </header>
          <p v-if="day.daySummary" class="read-day-summary">
            {{ day.daySummary }}
          </p>
          <div v-if="!day.items.length" class="empty">这一天还没有安排。</div>
          <article
            v-for="(item, n) in day.items"
            :key="item.id || n"
            class="stop"
          >
            <div class="stop-time">
              {{ item.startTime || "待定" }}<span>{{ n + 1 }}</span>
            </div>
            <div class="stop-content">
              <small>{{ labels[item.itemType] || "活动" }}</small>
              <h3>{{ item.itemName || (workspace ? "未命名活动" : "") }}</h3>
              <p v-if="workspace && item.address" class="read-address">
                {{ item.address }}
              </p>
              <p v-if="item.aiTips">{{ item.aiTips }}</p>
              <p v-if="item.transportTips" class="muted">
                交通 · {{ item.transportTips }}
              </p>
              <div class="meta-row">
                <span
                  v-if="
                    workspace ? known(item.costTimeHours) : item.costTimeHours
                  "
                  >约 {{ item.costTimeHours }} 小时</span
                >
                <span>{{
                  workspace
                    ? estimate(item.costEstimate)
                    : "预估 " + money(item.costEstimate)
                }}</span>
                <span v-if="item.poiId">已关联地点</span>
                <span
                  v-if="
                    item.environmentType && item.environmentType !== 'UNKNOWN'
                  "
                  >{{
                    item.environmentType === "INDOOR"
                      ? "室内（用户标记）"
                      : "室外（用户标记）"
                  }}</span
                >
                <span v-if="item.scheduleLocked">智能调整时固定</span>
              </div>
            </div>
          </article>
        </section>
      </Transition>
    </div>
  </div>
</template>
<style scoped>
.trip-read--workspace {
  max-width: none;
  width: 100%;
  color: var(--wb-v5-ink, #163c55);
}
.trip-read--workspace > .meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 9px 19px;
  font-size: 14px;
  color: var(--wb-v5-muted, #46677e);
}
.trip-read--workspace .pill {
  background: rgba(214, 237, 252, 0.72);
  border: 1px solid rgba(130, 177, 208, 0.25);
  color: var(--wb-v5-ink, #163c55);
  border-radius: 8px;
  padding: 3px 9px;
}
.trip-read--workspace .intro {
  margin: 20px 0 31px;
  color: var(--wb-v5-text, #284a62);
  font-size: 16px;
  line-height: 1.9;
  max-width: 78ch;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.trip-read--workspace .read-day-stage {
  position: relative;
  min-width: 0;
  margin-top: 30px;
}
.trip-read--workspace .day-block {
  margin: 0;
  padding: 0;
}
.trip-read--workspace .day-heading {
  display: flex;
  align-items: center;
  gap: 20px;
  margin: 0 0 25px;
  padding-bottom: 22px;
  border-bottom: 1px solid rgba(82, 130, 162, 0.2);
}
.trip-read--workspace .day-number {
  font-family: "Georgia", serif;
  font-size: 66px;
  font-weight: 400;
  line-height: 1;
  color: #3c80ac;
  letter-spacing: -0.055em;
  flex: none;
}
.trip-read--workspace .day-heading small {
  color: var(--wb-v5-muted, #46677e);
  font-size: 13px;
  letter-spacing: 0.035em;
}
.trip-read--workspace .day-heading h2 {
  margin: 6px 0 0;
  color: var(--wb-v5-ink, #163c55);
  font-family: "Noto Serif SC", "Songti SC", SimSun, serif;
  font-size: clamp(23px, 2vw, 30px);
  line-height: 1.55;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.trip-read--workspace .read-day-summary {
  max-width: 78ch;
  font-size: 16px;
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--wb-v5-text, #284a62);
  margin: 0 0 29px;
}
.trip-read--workspace .stop {
  display: flex;
  gap: 22px;
  margin: 0;
}
.trip-read--workspace .stop-time {
  width: 70px;
  padding-top: 9px;
  flex: none;
  color: #34698d;
  font-size: 15px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: center;
}
.trip-read--workspace .stop-time > span {
  width: 26px;
  height: 26px;
  line-height: 24px;
  margin: 12px auto;
  font-size: 12px;
  background: #e0eff9;
  border: 1px solid #b9d7ea;
  color: #34698d;
}
.trip-read--workspace .stop-time::after {
  top: 76px;
  left: 34px;
  bottom: 0;
  border-color: rgba(104, 153, 184, 0.38);
}
.trip-read--workspace .stop-content {
  min-width: 0;
  flex: 1;
  padding: 7px 0 30px;
  margin-bottom: 25px;
  border: 0;
  border-bottom: 1px solid rgba(97, 141, 171, 0.15);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.trip-read--workspace .stop:last-child .stop-content {
  border-bottom: 0;
  margin-bottom: 0;
}
.trip-read--workspace .stop-content > small {
  display: inline-block;
  padding: 2px 7px;
  background: rgba(212, 234, 249, 0.6);
  border-radius: 5px;
  font-size: 12px;
  color: #345f7d;
}
.trip-read--workspace .stop-content h3 {
  margin: 8px 0 11px;
  color: var(--wb-v5-ink, #163c55);
  font-size: 19px;
  font-weight: 600;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.trip-read--workspace .stop-content p {
  max-width: 78ch;
  margin: 11px 0 0;
  color: var(--wb-v5-text, #284a62);
  font-size: 16px;
  line-height: 1.95;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.trip-read--workspace .stop-content .read-address {
  color: var(--wb-v5-muted, #46677e);
  font-size: 14px;
}
.trip-read--workspace .stop-content p.muted {
  color: var(--wb-v5-muted, #46677e);
  font-size: 14px;
  line-height: 1.85;
}
.trip-read--workspace .stop-content .meta-row {
  gap: 7px 16px;
  margin-top: 15px;
  color: var(--wb-v5-muted, #46677e);
  font-size: 14px;
  line-height: 1.6;
}
.trip-read--workspace .empty {
  padding: 32px 20px;
  font-size: 15px;
  color: var(--wb-v5-muted, #46677e);
  background: rgba(255, 255, 255, 0.34);
  border-radius: 14px;
}
.trip-read--workspace.is-all-days .day-block {
  margin-bottom: 38px;
  opacity: 1;
  transform: none;
}
.read-day-forward-enter-active,
.read-day-forward-leave-active,
.read-day-backward-enter-active,
.read-day-backward-leave-active {
  transition:
    opacity 200ms ease,
    transform 280ms cubic-bezier(0.22, 1, 0.36, 1);
}
.trip-read--workspace
  .day-block:is(
    .read-day-forward-leave-active,
    .read-day-backward-leave-active
  ) {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  pointer-events: none;
}
.read-day-forward-enter-from {
  opacity: 0;
  transform: translateX(15px);
}
.read-day-forward-leave-to {
  opacity: 0;
  transform: translateX(-12px);
}
.read-day-backward-enter-from {
  opacity: 0;
  transform: translateX(-15px);
}
.read-day-backward-leave-to {
  opacity: 0;
  transform: translateX(12px);
}
@media (max-width: 700px) {
  .trip-read--workspace .day-heading {
    gap: 15px;
    padding-bottom: 17px;
  }
  .trip-read--workspace .day-number {
    font-size: 51px;
  }
  .trip-read--workspace .stop {
    gap: 10px;
  }
  .trip-read--workspace .stop-time {
    width: 47px;
    font-size: 13px;
  }
  .trip-read--workspace .stop-time::after {
    left: 23px;
  }
  .trip-read--workspace .stop-content h3 {
    font-size: 18px;
  }
  .trip-read--workspace .stop-content p {
    font-size: 15px;
    line-height: 1.85;
  }
}
@media (prefers-reduced-motion: reduce) {
  .read-day-forward-enter-active,
  .read-day-forward-leave-active,
  .read-day-backward-enter-active,
  .read-day-backward-leave-active {
    transition: none;
  }
  .read-day-forward-enter-from,
  .read-day-forward-leave-to,
  .read-day-backward-enter-from,
  .read-day-backward-leave-to {
    transform: none;
  }
}
@media print {
  .trip-read--workspace {
    color: #17384c;
    max-width: none;
  }
  .trip-read--workspace .day-block {
    display: block !important;
    position: static;
    opacity: 1;
    transform: none;
    margin-bottom: 28px;
    break-before: auto;
  }
  .trip-read--workspace .day-heading {
    break-after: avoid;
  }
  .trip-read--workspace .stop {
    break-inside: avoid;
  }
  .trip-read--workspace .stop-content {
    background: transparent;
  }
}
</style>
