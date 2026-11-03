<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { Trip } from "../types";
import { money } from "../api";
import TripRead from "../TripRead.vue";

const props = withDefaults(
  defineProps<{
    trip: Trip;
    active?: boolean;
    busy?: boolean;
    dirty?: boolean;
    canShare?: boolean;
    canPrint?: boolean;
    canExport?: boolean;
  }>(),
  {
    active: true,
    busy: false,
    dirty: false,
    canShare: false,
    canPrint: true,
    canExport: true,
  },
);
const dayIndex = defineModel<number>("dayIndex", { default: 0 });
const emit = defineEmits<{ share: []; print: []; export: [] }>();
const reader = ref<HTMLElement>(),
  outline = ref<HTMLElement>();
const outputMode = ref(false);
const scrollPositions = new Map<number, number>();
let scrollEpoch = 0;
let outputSnapshot: { top: number; day: number } | undefined;
const activityCount = computed(() =>
  props.trip.days.reduce((sum, day) => sum + day.items.length, 0),
);
const currentDay = computed(() => props.trip.days[dayIndex.value]);
const estimated = computed(() => {
  const items = props.trip.days.flatMap((day) => day.items);
  if (!items.length) return "费用待安排";
  const known = items.filter(
    (item) =>
      typeof item.costEstimate === "number" &&
      Number.isFinite(item.costEstimate),
  );
  if (!known.length) return "费用待补充";
  const amount = known.reduce((sum, item) => sum + item.costEstimate, 0);
  return (known.length < items.length ? "已知预估 " : "预估 ") + money(amount);
});
function dateLabel(value?: string) {
  const parts = value?.match(/^\d{4}-(\d{2})-(\d{2})/);
  return parts ? Number(parts[1]) + "月" + Number(parts[2]) + "日" : "日期待定";
}
function chapterId(index: number) {
  return "preview-day-" + (props.trip.id ?? "draft") + "-" + index;
}
function revealSelection() {
  const host = outline.value;
  const button = host?.querySelector<HTMLElement>(".pw-day-button.is-active");
  if (!host || !button) return;
  if (button.offsetTop < host.scrollTop) host.scrollTop = button.offsetTop;
  else if (
    button.offsetTop + button.offsetHeight >
    host.scrollTop + host.clientHeight
  )
    host.scrollTop = button.offsetTop + button.offsetHeight - host.clientHeight;
  if (button.offsetLeft < host.scrollLeft) host.scrollLeft = button.offsetLeft;
  else if (
    button.offsetLeft + button.offsetWidth >
    host.scrollLeft + host.clientWidth
  )
    host.scrollLeft = button.offsetLeft + button.offsetWidth - host.clientWidth;
}
watch(dayIndex, async (value, before) => {
  if (reader.value && !outputMode.value)
    scrollPositions.set(before, reader.value.scrollTop);
  const current = ++scrollEpoch;
  await nextTick();
  if (current !== scrollEpoch || outputMode.value || !props.active) return;
  if (reader.value) reader.value.scrollTop = scrollPositions.get(value) ?? 0;
  revealSelection();
});
watch(
  () => props.trip.days.length,
  (length) => {
    if (dayIndex.value >= length) dayIndex.value = Math.max(0, length - 1);
  },
);
watch(
  () => props.active,
  async (active) => {
    if (!active) return;
    await nextTick();
    revealSelection();
  },
);
function selectDay(index: number) {
  if (index >= 0 && index < props.trip.days.length) dayIndex.value = index;
}
async function navigateDay(event: KeyboardEvent, index: number) {
  const delta =
    event.key === "ArrowDown" || event.key === "ArrowRight"
      ? 1
      : event.key === "ArrowUp" || event.key === "ArrowLeft"
        ? -1
        : 0;
  if (!delta && event.key !== "Home" && event.key !== "End") return;
  event.preventDefault();
  const target =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? props.trip.days.length - 1
        : Math.max(0, Math.min(props.trip.days.length - 1, index + delta));
  selectDay(target);
  await nextTick();
  outline.value
    ?.querySelectorAll<HTMLButtonElement>(".pw-day-button")
    [target]?.focus({ preventScroll: true });
}
function request(action: "share" | "print" | "export") {
  if (!props.active || props.busy) return;
  if (action !== "print" && props.dirty) return;
  if (action === "share" && props.canShare) emit("share");
  if (action === "print" && props.canPrint) emit("print");
  if (action === "export" && props.canExport) emit("export");
}
async function prepareOutput() {
  if (!outputSnapshot)
    outputSnapshot = { top: reader.value?.scrollTop ?? 0, day: dayIndex.value };
  outputMode.value = true;
  await nextTick();
}
async function finishOutput() {
  outputMode.value = false;
  await nextTick();
  if (reader.value)
    reader.value.scrollTop =
      outputSnapshot && outputSnapshot.day === dayIndex.value
        ? outputSnapshot.top
        : (scrollPositions.get(dayIndex.value) ?? 0);
  outputSnapshot = undefined;
}
defineExpose({ prepareOutput, finishOutput });
</script>
<template>
  <section
    class="preview-workspace-v6"
    :class="{ 'is-output-mode': outputMode }"
    aria-label="路书预览工作空间"
    :aria-busy="busy"
  >
    <aside class="pw-outline" aria-label="路书日期目录">
      <header class="pw-outline-heading">
        <span>行程目录</span><small>{{ trip.days.length }} 天</small>
      </header>
      <nav ref="outline" class="pw-day-list" aria-label="选择阅读日期">
        <button
          v-for="(day, index) in trip.days"
          :key="day.id ?? index"
          type="button"
          class="pw-day-button"
          :class="{ 'is-active': dayIndex === index }"
          :aria-pressed="dayIndex === index"
          :aria-controls="chapterId(index)"
          :aria-label="
            '第' +
            (index + 1) +
            '天 · ' +
            (day.dayDate || '日期待定') +
            ' · ' +
            (day.dayTheme || '自由探索')
          "
          @click="selectDay(index)"
          @keydown="navigateDay($event, index)"
        >
          <span class="pw-day-number"
            ><small>DAY</small
            ><strong>{{ String(index + 1).padStart(2, "0") }}</strong></span
          >
          <span class="pw-day-label"
            ><time :datetime="day.dayDate">{{ dateLabel(day.dayDate) }}</time
            ><span>{{ day.dayTheme || "自由探索" }}</span
            ><small>{{ day.items.length }} 项安排</small></span
          >
        </button>
      </nav>
    </aside>
    <article class="pw-reader-shell">
      <header class="pw-reader-header">
        <div>
          <span class="pw-kicker">TRAVEL JOURNAL</span>
          <h2>{{ trip.title || "我的旅行路书" }}</h2>
        </div>
        <span class="pw-reader-status" :class="{ 'is-dirty': dirty }">{{
          dirty ? "当前草稿" : "旅行手册"
        }}</span>
      </header>
      <div
        ref="reader"
        class="pw-reader-scroll"
        tabindex="0"
        :aria-label="'路书正文 · ' + (currentDay?.dayTheme || '自由探索')"
      >
        <TripRead
          :trip="trip"
          workspace
          :day-index="dayIndex"
          :all-days="outputMode"
        />
      </div>
    </article>
    <aside class="pw-actions" aria-label="路书预览操作">
      <div class="pw-actions-intro">
        <span class="pw-kicker">TAKE IT WITH YOU</span>
        <h3>把旅程，<br />带在身边。</h3>
        <p>
          {{ trip.destination }} · {{ trip.days.length }} 天<br />{{
            activityCount
          }}
          项安排 · {{ estimated }}
        </p>
      </div>
      <div class="pw-action-buttons">
        <button
          v-if="canShare"
          type="button"
          :disabled="busy || dirty"
          @click="request('share')"
        >
          分享路书 <span aria-hidden="true">↗</span>
        </button>
        <button
          type="button"
          :disabled="busy || !canPrint"
          @click="request('print')"
        >
          打印 / 导出 PDF <span aria-hidden="true">↗</span>
        </button>
        <button
          type="button"
          :disabled="busy || dirty || !canExport"
          @click="request('export')"
        >
          导出图片 <span aria-hidden="true">↗</span>
        </button>
      </div>
      <p v-if="dirty" class="pw-action-note" role="status">
        正在预览当前草稿，保存后可分享或导出。
      </p>
      <p v-else class="pw-action-note">
        分享与导出沿用当前路书的权限，内容取自已保存路书。
      </p>
    </aside>
  </section>
</template>
<style scoped>
.preview-workspace-v6 {
  --pw-ink: var(--wb-v5-ink, #163d57);
  --pw-text: var(--wb-v5-text, #284a62);
  --pw-muted: var(--wb-v5-muted, #496b82);
  --pw-edge: rgba(232, 247, 255, 0.8);
  display: grid;
  grid-template-columns: 228px minmax(0, 1fr) 218px;
  gap: 16px;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  color: var(--pw-ink);
}
.pw-outline,
.pw-reader-shell,
.pw-actions {
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--pw-edge);
  border-radius: 20px;
  background: linear-gradient(
    136deg,
    rgba(234, 247, 255, 0.87),
    rgba(211, 233, 248, 0.8)
  );
  box-shadow:
    inset 0 1px rgba(255, 255, 255, 0.8),
    0 8px 22px rgba(9, 40, 66, 0.1);
}
.pw-outline {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 20px 12px 12px;
}
.pw-outline-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 9px 17px;
  gap: 8px;
  color: var(--pw-ink);
  font-size: 16px;
  font-weight: 600;
}
.pw-outline-heading small {
  color: var(--pw-muted);
  font-size: 13px;
  font-weight: 400;
}
.pw-day-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 9px;
  overscroll-behavior: contain;
  padding: 1px;
  scrollbar-width: thin;
}
.pw-day-button {
  display: grid;
  grid-template-columns: 43px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  flex: none;
  width: 100%;
  border: 1px solid transparent;
  border-radius: 13px;
  background: transparent;
  padding: 13px 11px;
  color: var(--pw-text);
  text-align: left;
}
.pw-day-button:hover {
  background: rgba(245, 252, 255, 0.48);
}
.pw-day-button.is-active {
  background: linear-gradient(140deg, #e7f5ff, #c4e2f7);
  border-color: rgba(101, 156, 197, 0.42);
  box-shadow: inset 3px 0 #4c8cb8;
}
.pw-day-number {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.pw-day-number small {
  font-size: 10px;
  color: #547c99;
  letter-spacing: 0.08em;
}
.pw-day-number strong {
  font-size: 26px;
  line-height: 1.1;
  font-weight: 500;
  color: #2e658b;
}
.pw-day-label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.pw-day-label time {
  color: var(--pw-ink);
  font-size: 14px;
  font-weight: 600;
}
.pw-day-label > span {
  color: var(--pw-text);
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.pw-day-label small {
  font-size: 12px;
  color: var(--pw-muted);
}
.pw-reader-shell {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: linear-gradient(
    135deg,
    rgba(248, 253, 255, 0.95),
    rgba(231, 244, 253, 0.91)
  );
}
.pw-reader-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 25px 29px 21px;
  border-bottom: 1px solid rgba(104, 153, 186, 0.16);
  flex: none;
}
.pw-kicker {
  display: block;
  color: #567b97;
  font-size: 10px;
  letter-spacing: 0.16em;
  line-height: 1.6;
}
.pw-reader-header h2 {
  color: var(--pw-ink);
  font-family: "Noto Serif SC", "Songti SC", SimSun, serif;
  font-size: clamp(24px, 2.1vw, 32px);
  line-height: 1.5;
  font-weight: 600;
  margin: 8px 0 0;
  overflow-wrap: anywhere;
}
.pw-reader-status {
  flex: none;
  color: #406c8b;
  font-size: 12px;
  line-height: 1.6;
  padding: 4px 8px;
  border-radius: 7px;
  background: rgba(208, 233, 249, 0.7);
  margin-top: 3px;
}
.pw-reader-status.is-dirty {
  color: #8c592e;
  background: #f6e8d3;
}
.pw-reader-scroll {
  min-height: 0;
  flex: 1;
  overflow: auto;
  padding: 25px 31px 36px;
  scrollbar-gutter: stable;
  overscroll-behavior: contain;
  scroll-padding-block: 25px;
  outline-offset: -4px;
}
.pw-actions {
  padding: 25px 21px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 24px;
}
.pw-actions-intro h3 {
  margin: 15px 0;
  color: var(--pw-ink);
  font-family: "Noto Serif SC", "Songti SC", SimSun, serif;
  font-weight: 500;
  font-size: 25px;
  line-height: 1.7;
}
.pw-actions-intro p {
  color: var(--pw-muted);
  font-size: 13px;
  line-height: 1.9;
  margin: 0;
  overflow-wrap: anywhere;
}
.pw-action-buttons {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.pw-action-buttons button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 43px;
  padding: 10px 12px;
  border: 1px solid rgba(242, 251, 255, 0.95);
  border-radius: 10px;
  background: linear-gradient(
    132deg,
    rgba(245, 252, 255, 0.9),
    rgba(210, 234, 249, 0.75)
  );
  box-shadow:
    inset 0 1px #fff,
    0 3px 7px rgba(27, 78, 114, 0.05);
  color: var(--pw-ink);
  text-align: left;
  font-size: 14px;
  line-height: 1.5;
}
.pw-action-buttons button:hover:not(:disabled) {
  background: #f1faff;
  border-color: rgba(125, 176, 211, 0.7);
}
.pw-action-buttons button:disabled {
  cursor: not-allowed;
}
.pw-action-note {
  color: var(--pw-muted);
  font-size: 12px;
  line-height: 1.85;
  margin: 0;
}
.pw-day-button:focus-visible,
.pw-action-buttons button:focus-visible {
  outline: 2px solid #b76c38;
  outline-offset: 2px;
}
.preview-workspace-v6.is-output-mode {
  display: block;
  height: auto;
  overflow: visible;
}
.is-output-mode .pw-outline,
.is-output-mode .pw-actions {
  display: none;
}
.is-output-mode .pw-reader-shell {
  overflow: visible;
  height: auto;
}
.is-output-mode .pw-reader-scroll {
  overflow: visible;
  height: auto;
  padding: 25px 31px;
}
@media (max-width: 1200px) {
  .preview-workspace-v6 {
    grid-template-columns: 198px minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    gap: 12px;
  }
  .pw-outline {
    grid-column: 1;
    grid-row: 1 / span 2;
  }
  .pw-actions {
    grid-column: 2;
    grid-row: 1;
    display: block;
    padding: 10px 15px;
  }
  .pw-actions-intro,
  .pw-action-note {
    display: none;
  }
  .pw-action-buttons {
    flex-direction: row;
    gap: 8px;
    flex-wrap: wrap;
  }
  .pw-action-buttons button {
    flex: 1;
    min-height: 38px;
    justify-content: center;
    font-size: 13px;
    padding: 8px;
    white-space: nowrap;
  }
  .pw-reader-shell {
    grid-column: 2;
    grid-row: 2;
  }
  .pw-reader-header {
    padding: 20px 23px 16px;
    gap: 12px;
  }
  .pw-reader-scroll {
    padding: 21px 25px 29px;
  }
}
@media (max-width: 800px) {
  .preview-workspace-v6 {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: 90px auto minmax(0, 1fr);
    gap: 10px;
  }
  .pw-outline {
    grid-column: 1;
    grid-row: 1;
    padding: 5px;
    border-radius: 14px;
  }
  .pw-outline-heading {
    display: none;
  }
  .pw-day-list {
    flex-direction: row;
    gap: 7px;
    overflow-x: auto;
    overflow-y: hidden;
  }
  .pw-day-button {
    width: 173px;
    grid-template-columns: 35px minmax(0, 1fr);
    padding: 7px 10px;
    gap: 8px;
  }
  .pw-day-number strong {
    font-size: 23px;
  }
  .pw-day-label {
    gap: 2px;
  }
  .pw-day-label > span {
    -webkit-line-clamp: 1;
  }
  .pw-day-label small {
    display: none;
  }
  .pw-actions {
    grid-column: 1;
    grid-row: 2;
    padding: 8px 10px;
    border-radius: 12px;
  }
  .pw-reader-shell {
    grid-column: 1;
    grid-row: 3;
    border-radius: 15px;
  }
  .pw-reader-header {
    padding: 17px 18px 14px;
  }
  .pw-reader-header h2 {
    font-size: 23px;
    margin-top: 5px;
  }
  .pw-reader-status {
    display: none;
  }
  .pw-reader-scroll {
    padding: 18px 17px 25px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .pw-day-button,
  .pw-action-buttons button {
    transition: none;
  }
}
@media print {
  .preview-workspace-v6 {
    display: block;
    height: auto;
    overflow: visible;
  }
  .pw-outline,
  .pw-actions {
    display: none;
  }
  .pw-reader-shell {
    display: block;
    border: 0;
    border-radius: 0;
    background: #fff;
    box-shadow: none;
    overflow: visible;
    height: auto;
  }
  .pw-reader-scroll {
    display: block;
    overflow: visible;
    height: auto;
    padding: 18px 0 0;
  }
  .pw-reader-header {
    padding: 0 0 18px;
    border-color: #d7e0e6;
  }
  .pw-reader-status {
    display: none;
  }
}
</style>
