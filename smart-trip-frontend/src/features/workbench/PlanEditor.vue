<script setup lang="ts">
import { computed, nextTick } from "vue";
import type { Trip, Item } from "../types";
import { money } from "../api";
import TimelinePanel from "./TimelinePanel.vue";
import TimelineNode from "./TimelineNode.vue";
import NodeFields from "./NodeFields.vue";
const props = defineProps<{
  trip: Trip;
  dayIndex: number;
  editable: boolean;
  busy: boolean;
  dirty: boolean;
  geo: boolean;
  nodeKey: (item: Item) => string;
}>();
const selectedNode = defineModel<string>("selectedNode", { required: true });
const expanded = defineModel<boolean>("expanded", { required: true });
const emit = defineEmits<{
  "remove-day": [];
  poi: [item: Item];
  record: [item: Item];
}>();
const day = computed(() => props.trip.days[props.dayIndex]!);
const estimated = computed(() =>
  money(
    day.value.items.reduce(
      (sum, item) => sum + Number(item.costEstimate || 0),
      0,
    ),
  ),
);
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
  () => day.value.items.filter((item) => item.costTimeHours == null).length,
);
function select(item: Item) {
  selectedNode.value = props.nodeKey(item);
  expanded.value = true;
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
  select(day.value.items[day.value.items.length - 1]!);
  void nextTick(() =>
    document.querySelector<HTMLInputElement>(".wb-node-fields input")?.focus(),
  );
}
function move(item: Item, delta: number) {
  if (!props.editable || props.busy) return;
  const index = day.value.items.indexOf(item),
    target = index + delta;
  if (index < 0 || target < 0 || target >= day.value.items.length) return;
  day.value.items.splice(index, 1);
  day.value.items.splice(target, 0, item);
}
async function close(item: Item) {
  selectedNode.value = "";
  await nextTick();
  document.getElementById("timeline-" + props.nodeKey(item))?.focus();
}
async function remove(item: Item) {
  if (!props.editable || props.busy) return;
  const index = day.value.items.indexOf(item);
  if (index < 0) return;
  day.value.items.splice(index, 1);
  selectedNode.value = "";
  await nextTick();
  const next = day.value.items[Math.min(index, day.value.items.length - 1)];
  if (next) document.getElementById("timeline-" + props.nodeKey(next))?.focus();
  else document.getElementById("wb-add-node")?.focus();
}
async function transfer(item: Item, target: number) {
  if (
    !props.editable ||
    props.busy ||
    target === props.dayIndex ||
    !props.trip.days[target]
  )
    return;
  const index = day.value.items.indexOf(item);
  if (index < 0) return;
  day.value.items.splice(index, 1);
  props.trip.days[target]!.items.push(item);
  selectedNode.value = "";
  await nextTick();
  document.getElementById("wb-add-node")?.focus();
}
</script>
<template>
  <TimelinePanel
    :day="day"
    :day-index="dayIndex"
    :estimated="estimated"
    :duration="duration"
    :missing-duration="missingDuration"
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
    <TimelineNode
      v-for="(item, index) in day.items"
      :key="nodeKey(item)"
      :item="item"
      :index="index"
      :node-key="nodeKey(item)"
      :selected="selectedNode === nodeKey(item)"
      @select="select(item)"
    >
      <NodeFields
        :item="item"
        :trip="trip"
        :day-index="dayIndex"
        :index="index"
        :editable="editable"
        :busy="busy"
        :dirty="dirty"
        :geo="geo"
        @close="close(item)"
        @move="move(item, $event)"
        @remove="remove(item)"
        @transfer="transfer(item, $event)"
        @poi="emit('poi', item)"
        @record="emit('record', item)"
      />
    </TimelineNode>
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
</template>
