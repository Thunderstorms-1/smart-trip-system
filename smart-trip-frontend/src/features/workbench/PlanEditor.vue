<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import type { Trip, Item } from "../types";
import { money } from "../api";
import TimelinePanel from "./TimelinePanel.vue";
import TimelineNode from "./TimelineNode.vue";
import NodeInspector from "./NodeInspector.vue";
const props = defineProps<{
  trip: Trip;
  dayIndex: number;
  editable: boolean;
  busy: boolean;
  dirty: boolean;
  geo: boolean;
  active: boolean;
  error: string;
  nodeKey: (item: Item) => string;
}>();
const selectedNode = defineModel<string>("selectedNode", { required: true });
const expanded = defineModel<boolean>("expanded", { required: true });
let focusEpoch = 0;
let disposed = false;
const daySwitching = ref(false);
let switchFrame = 0;
let switchEpoch = 0;
watch(
  () => props.dayIndex,
  async () => {
    daySwitching.value = true;
    focusEpoch++;
    const current = ++switchEpoch;
    await nextTick();
    if (disposed || current !== switchEpoch) return;
    cancelAnimationFrame(switchFrame);
    switchFrame = requestAnimationFrame(() => {
      daySwitching.value = false;
    });
  },
  { flush: "sync" },
);
onBeforeUnmount(() => {
  disposed = true;
  focusEpoch++;
  cancelAnimationFrame(switchFrame);
});
const emit = defineEmits<{
  "remove-day": [];
  save: [];
  poi: [item: Item];
  record: [item: Item];
}>();
const day = computed(() => props.trip.days[props.dayIndex]!);
const estimated = computed(() => {
  const known = day.value.items.filter(
    (item) =>
      typeof item.costEstimate === "number" &&
      Number.isFinite(item.costEstimate),
  );
  const missing = day.value.items.length - known.length;
  return (
    money(known.reduce((sum, item) => sum + item.costEstimate, 0)) +
    (missing ? " · " + missing + "项费用待填" : "")
  );
});
const duration = computed(
  () =>
    Math.round(
      day.value.items.reduce(
        (sum, item) => sum + Number(item.costTimeHours || 0),
        0,
      ) * 1000000,
    ) / 1000000,
);
const missingDuration = computed(
  () =>
    day.value.items.filter(
      (item) =>
        typeof item.costTimeHours !== "number" ||
        !Number.isFinite(item.costTimeHours),
    ).length,
);
const selectedItem = computed(() =>
  day.value.items.find((item) => props.nodeKey(item) === selectedNode.value),
);
function select(item: Item) {
  if (selectedNode.value === props.nodeKey(item)) {
    void close(item);
    return;
  }
  focusEpoch++;
  selectedNode.value = props.nodeKey(item);
  expanded.value = true;
}
function reindex(items: Item[]) {
  items.forEach((item, index) => {
    item.sortOrder = index + 1;
  });
}
function addItem() {
  if (
    !props.editable ||
    props.busy ||
    day.value.items.length >= 40 ||
    props.trip.days.reduce((n, d) => n + d.items.length, 0) >= 500
  )
    return;
  const item: Item = {
    itemType: "SCENIC",
    itemName: "",
    startTime: "09:00",
    costTimeHours: 2,
    costEstimate: 0,
    environmentType: "UNKNOWN",
    scheduleLocked: false,
    aiTips: "",
  };
  day.value.items.push(item);
  reindex(day.value.items);
  const added = day.value.items[day.value.items.length - 1]!;
  select(added);
  const current = ++focusEpoch;
  void nextTick(() => {
    if (disposed || current !== focusEpoch || !props.active) return;
    const node = document.getElementById("timeline-" + props.nodeKey(added));
    node
      ?.closest(".wb-timeline-node")
      ?.scrollIntoView({ block: "nearest", behavior: "auto" });
    document
      .querySelector<HTMLInputElement>(".wb-node-fields input")
      ?.focus({ preventScroll: true });
  });
}
function move(item: Item, delta: number) {
  if (!props.editable || props.busy) return;
  const index = day.value.items.indexOf(item),
    target = index + delta;
  if (index < 0 || target < 0 || target >= day.value.items.length) return;
  day.value.items.splice(index, 1);
  day.value.items.splice(target, 0, item);
  reindex(day.value.items);
  const key = props.nodeKey(item);
  void nextTick(() => {
    if (!disposed && props.active && day.value.items.includes(item))
      document
        .getElementById("timeline-" + key)
        ?.closest(".wb-timeline-node")
        ?.scrollIntoView({ block: "nearest" });
  });
}
async function close(item: Item) {
  selectedNode.value = "";
  const current = ++focusEpoch;
  await nextTick();
  if (
    !disposed &&
    current === focusEpoch &&
    props.active &&
    !selectedNode.value
  )
    document
      .getElementById("timeline-" + props.nodeKey(item))
      ?.focus({ preventScroll: true });
}
async function remove(item: Item) {
  if (!props.editable || props.busy) return;
  const index = day.value.items.indexOf(item);
  if (index < 0) return;
  if (
    !window.confirm(
      "确定删除“" + (item.itemName || "这个安排") + "”吗？保存路书后生效。",
    )
  )
    return;
  day.value.items.splice(index, 1);
  reindex(day.value.items);
  selectedNode.value = "";
  const current = ++focusEpoch;
  await nextTick();
  if (disposed || current !== focusEpoch || !props.active || selectedNode.value)
    return;
  const next = day.value.items[Math.min(index, day.value.items.length - 1)];
  if (next) document.getElementById("timeline-" + props.nodeKey(next))?.focus();
  else document.getElementById("wb-add-node")?.focus();
}
async function transfer(item: Item, target: number) {
  if (
    !props.editable ||
    props.busy ||
    target === props.dayIndex ||
    !props.trip.days[target] ||
    props.trip.days[target]!.items.length >= 40
  )
    return;
  const index = day.value.items.indexOf(item);
  if (index < 0) return;
  day.value.items.splice(index, 1);
  props.trip.days[target]!.items.push(item);
  reindex(day.value.items);
  reindex(props.trip.days[target]!.items);
  selectedNode.value = "";
  const current = ++focusEpoch;
  await nextTick();
  if (
    !disposed &&
    current === focusEpoch &&
    props.active &&
    !selectedNode.value
  )
    document.getElementById("wb-add-node")?.focus({ preventScroll: true });
}
</script>
<template>
  <TimelinePanel
    :day="day"
    :day-index="dayIndex"
    :estimated="estimated"
    :duration="duration"
    :missing-duration="missingDuration"
    :travel-group="trip.travelGroup"
    :expanded="expanded"
    @expand="expanded = !expanded"
  >
    <template #details>
      <details class="wb-trip-details">
        <summary>旅行信息与当日主题</summary>
        <fieldset class="draft-fieldset" :disabled="busy">
          <label
            >目的地<input
              v-model="trip.destination"
              :readonly="!editable || busy"
              maxlength="100"
              required /></label
          ><label
            >出发日期<input
              v-model="trip.startDate"
              :readonly="!editable || busy"
              type="date" /></label
          ><label
            >行程简介<textarea
              v-model="trip.summary"
              :readonly="!editable || busy"
              maxlength="500"
              rows="2"
            ></textarea></label
          ><label
            >当日主题<input
              v-model="day.dayTheme"
              :readonly="!editable || busy"
              maxlength="100" /></label
          ><button
            v-if="editable"
            type="button"
            class="wb-text-button wb-danger"
            :disabled="busy"
            @click="emit('remove-day')"
          >
            删除这一天
          </button>
        </fieldset>
      </details>
    </template>
    <TransitionGroup
      name="wb-node-list"
      tag="div"
      class="wb-timeline-list"
      :css="!daySwitching"
    >
      <TimelineNode
        v-for="(item, index) in day.items"
        :key="nodeKey(item)"
        :item="item"
        :index="index"
        :node-key="nodeKey(item)"
        :selected="selectedNode === nodeKey(item)"
        :editable="editable"
        :busy="busy"
        :can-move-up="index > 0"
        :can-move-down="index < day.items.length - 1"
        @select="select(item)"
        @move="move(item, $event)"
        @remove="remove(item)"
      />
    </TransitionGroup>
    <div v-if="!day.items.length" class="wb-empty-day">
      <h2>留一段时间给新的发现</h2>
      <p>这一天还没有安排。添加地点或活动，开始规划。</p>
    </div>
    <template #footer
      ><button
        v-if="editable"
        id="wb-add-node"
        type="button"
        class="wb-add-node"
        aria-label="＋ 添加行程节点"
        :disabled="
          busy ||
          day.items.length >= 40 ||
          trip.days.reduce((n, d) => n + d.items.length, 0) >= 500
        "
        @click="addItem"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.7"
          aria-hidden="true"
        >
          <path d="M12 5v14M5 12h14" /></svg
        >添加行程节点
      </button>
      <p>
        {{
          dirty
            ? "修改保留在当前草稿，保存后生效。"
            : "正在查看已保存的行程安排。"
        }}
      </p></template
    >
  </TimelinePanel>
  <Teleport defer to="#workbench-inspector-host">
    <NodeInspector
      v-if="selectedItem && active"
      :item="selectedItem"
      :node-key="nodeKey(selectedItem)"
      :trip="trip"
      :day-index="dayIndex"
      :index="day.items.indexOf(selectedItem)"
      :editable="editable"
      :busy="busy"
      :dirty="dirty"
      :geo="geo"
      :error="error"
      @close="close(selectedItem)"
      @move="move(selectedItem, $event)"
      @remove="remove(selectedItem)"
      @transfer="transfer(selectedItem, $event)"
      @poi="emit('poi', selectedItem)"
      @record="emit('record', selectedItem)"
      @save="emit('save')"
    />
  </Teleport>
</template>
