<script setup lang="ts">
import { computed } from "vue";
import type { Trip, Item } from "../types";
const props = defineProps<{
  trip: Trip;
  dayIndex: number;
  editable: boolean;
  busy: boolean;
  dirty: boolean;
  geo: boolean;
}>();
const emit = defineEmits<{
  "remove-day": [];
  poi: [item: Item];
  record: [item: Item];
}>();
const day = computed(() => props.trip.days[props.dayIndex]!);
function addItem() {
  if (!props.editable || props.busy) return;
  day.value.items.push({
    itemType: "SCENIC",
    itemName: "",
    startTime: "09:00",
    costTimeHours: 2,
    costEstimate: 0,
    environmentType: "UNKNOWN",
    scheduleLocked: false,
    aiTips: "",
  });
}
function move(index: number, delta: number) {
  if (!props.editable || props.busy) return;
  const target = index + delta;
  if (target < 0 || target >= day.value.items.length) return;
  const [item] = day.value.items.splice(index, 1);
  day.value.items.splice(target, 0, item!);
}
function transfer(index: number, event: Event) {
  if (!props.editable || props.busy) return;
  const target = Number((event.target as HTMLSelectElement).value);
  if (target === props.dayIndex || !props.trip.days[target]) return;
  const [item] = day.value.items.splice(index, 1);
  props.trip.days[target]!.items.push(item!);
}
</script>
<template>
  <fieldset class="panel-stack draft-fieldset" :disabled="busy">
    <section class="paper editor-panel">
      <div class="form-grid">
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
        ><label class="wide"
          >行程简介<textarea
            v-model="trip.summary"
            :readonly="!editable || busy"
            maxlength="500"
            rows="2"
          ></textarea>
        </label>
      </div>
      <div class="row">
        <h2>第 {{ dayIndex + 1 }} 天</h2>
        <button v-if="editable" class="text-button" @click="emit('remove-day')">
          删除这一天
        </button>
      </div>
      <label
        >当日主题<input
          v-model="day.dayTheme"
          :readonly="!editable || busy"
          maxlength="100"
      /></label>
      <article
        v-for="(item, n) in day.items"
        :key="item.id || 'new-' + n"
        class="item-editor"
      >
        <div class="row">
          <h3>{{ n + 1 }} / {{ item.itemName || "新地点" }}</h3>
          <div v-if="editable" class="mini-actions">
            <button
              :disabled="n === 0"
              @click="move(n, -1)"
              aria-label="向上移动节点"
            >
              ↑</button
            ><button
              :disabled="n === day.items.length - 1"
              @click="move(n, 1)"
              aria-label="向下移动节点"
            >
              ↓</button
            ><button @click="day.items.splice(n, 1)">删除</button>
          </div>
        </div>
        <div class="form-grid">
          <label class="wide"
            >地点 / 活动<input
              v-model="item.itemName"
              @input="
                item.poiId = undefined;
                item.latitude = undefined;
                item.longitude = undefined;
                item.coordinateSystem = undefined;
              "
              :readonly="!editable || busy"
              maxlength="100"
              required /></label
          ><label
            >类型<select v-model="item.itemType" :disabled="!editable">
              <option value="SCENIC">景点</option>
              <option value="FOOD">美食</option>
              <option value="HOTEL">住宿</option>
              <option value="TRAFFIC">交通</option>
              <option value="OTHER">活动</option>
            </select></label
          ><label
            >活动环境（由你确认）<select
              v-model="item.environmentType"
              :disabled="!editable"
            >
              <option value="UNKNOWN">不确定</option>
              <option value="INDOOR">室内</option>
              <option value="OUTDOOR">室外</option>
            </select></label
          ><label
            >智能调整时固定此安排<input
              type="checkbox"
              v-model="item.scheduleLocked"
              :disabled="!editable"
            />保留日期、开始时间和时长</label
          ><label
            >开始时间<input
              v-model="item.startTime"
              :readonly="!editable || busy"
              type="time" /></label
          ><label
            >停留时长（小时）<input
              v-model.number="item.costTimeHours"
              :readonly="!editable || busy"
              type="number"
              min="0"
              max="24"
              step="0.5" /></label
          ><label
            >预估花费（元）<input
              v-model.number="item.costEstimate"
              :readonly="!editable || busy"
              type="number"
              min="0"
              max="9999999"
              step="0.01" /></label
          ><label class="wide"
            >旅行备注<textarea
              v-model="item.aiTips"
              :readonly="!editable || busy"
              maxlength="500"
              rows="2"
            ></textarea></label
          ><label class="wide"
            >交通提示<input
              v-model="item.transportTips"
              :readonly="!editable || busy"
              maxlength="500"
          /></label>
        </div>
        <div v-if="editable" class="button-row">
          <button
            class="button secondary small"
            :disabled="!geo"
            @click="emit('poi', item)"
          >
            {{ item.poiId ? "重新选择地点" : "关联真实地点" }}</button
          ><button
            class="text-button"
            :disabled="dirty || !item.id"
            @click="emit('record', item)"
          >
            记录这笔花费</button
          ><select
            :value="dayIndex"
            style="width: auto; font-size: 12px; margin: 0"
            aria-label="移动节点到另一天"
            @change="transfer(n, $event)"
          >
            <option v-for="(_, idx) in trip.days" :key="idx" :value="idx">
              移动到第 {{ idx + 1 }} 天
            </option>
          </select>
        </div>
        <p v-if="item.poiId" class="muted">已关联地点 ID：{{ item.poiId }}</p>
      </article>
      <div v-if="!day.items.length" class="empty">
        这一天还没有安排，添加第一个地点吧。
      </div>
      <button
        v-if="editable"
        class="button secondary full"
        style="margin-top: 20px"
        @click="addItem"
      >
        ＋ 添加行程节点
      </button>
    </section>
    <div v-if="!geo" class="notice">
      地点搜索暂未启用。你仍可先写下地点名称，之后再关联真实位置。
    </div>
  </fieldset>
</template>
