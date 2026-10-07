<script setup lang="ts">
import type { Item, Trip } from "../types";
const props = defineProps<{
  item: Item;
  trip: Trip;
  dayIndex: number;
  index: number;
  editable: boolean;
  busy: boolean;
  dirty: boolean;
  geo: boolean;
}>();
const emit = defineEmits<{
  close: [];
  move: [delta: number];
  remove: [];
  transfer: [index: number];
  poi: [];
  record: [];
}>();
function transfer(event: Event) {
  const target = Number((event.target as HTMLSelectElement).value);
  if (
    props.editable &&
    !props.busy &&
    target !== props.dayIndex &&
    props.trip.days[target]
  )
    emit("transfer", target);
}
</script>
<template>
  <section
    class="wb-node-fields"
    aria-label="编辑选中安排"
    @keydown.esc.stop="emit('close')"
  >
    <fieldset class="draft-fieldset" :disabled="busy">
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
            maxlength="255"
        /></label>
      </div>

      <div v-if="editable" class="wb-node-actions">
        <button
          type="button"
          :disabled="index === 0"
          aria-label="向上移动节点"
          @click="emit('move', -1)"
        >
          上移
        </button>
        <button
          type="button"
          :disabled="index === trip.days[dayIndex]!.items.length - 1"
          aria-label="向下移动节点"
          @click="emit('move', 1)"
        >
          下移
        </button>
        <button type="button" class="wb-danger" @click="emit('remove')">
          删除
        </button>
        <label class="wb-transfer-label"
          >移动到
          <select
            :value="dayIndex"
            aria-label="移动节点到另一天"
            @change="transfer"
          >
            <option v-for="(_, idx) in trip.days" :key="idx" :value="idx">
              第 {{ idx + 1 }} 天
            </option>
          </select>
        </label>
        <button type="button" :disabled="!geo" @click="emit('poi')">
          {{ item.poiId ? "重新选择地点" : "关联真实地点" }}
        </button>
        <button
          type="button"
          :disabled="dirty || !item.id"
          @click="emit('record')"
        >
          记录这笔花费
        </button>
      </div>
      <p v-if="item.poiId" class="wb-field-note">
        已关联地点 ID：{{ item.poiId }}
      </p>
      <p v-if="!geo" class="wb-field-note">
        地点搜索暂未启用。可以先填写名称，之后再关联真实位置。
      </p>
    </fieldset>
  </section>
</template>
