<script setup lang="ts">
import { computed } from "vue";
import type { Item } from "../types";
import { money } from "../api";
import HomeIcon from "../HomeIcon.vue";
const props = defineProps<{
  item: Item;
  index: number;
  nodeKey: string;
  selected: boolean;
}>();
const emit = defineEmits<{ select: [] }>();
const types: Record<string, string> = {
  SCENIC: "景点",
  FOOD: "美食",
  HOTEL: "住宿",
  TRAFFIC: "交通",
  OTHER: "活动",
};
const duration = computed(() =>
  props.item.costTimeHours == null
    ? "时长待填写"
    : props.item.costTimeHours + " 小时",
);
</script>
<template>
  <article
    class="wb-timeline-node"
    :class="{ 'is-selected': selected }"
    :id="item.id ? 'plan-item-' + item.id : undefined"
    tabindex="-1"
  >
    <button
      type="button"
      class="wb-node-select"
      :id="'timeline-' + nodeKey"
      :data-node-key="nodeKey"
      :aria-label="'选择安排：' + (item.itemName || '新地点')"
      :aria-expanded="selected"
      :aria-controls="selected ? 'fields-' + nodeKey : undefined"
      @click="emit('select')"
    >
      <span class="wb-node-time">{{ item.startTime || "时间未定" }}</span>
      <span class="wb-node-order">{{ index + 1 }}</span>
      <span class="wb-node-info"
        ><strong>{{ item.itemName || "新地点" }}</strong
        ><span class="wb-node-meta"
          ><HomeIcon
            :name="
              item.itemType === 'FOOD'
                ? 'food'
                : item.itemType === 'TRAFFIC'
                  ? 'route'
                  : 'pin'
            "
          />{{ types[item.itemType] || "活动" }}<span>·</span
          >{{ duration }}</span
        ><span class="wb-node-cost"
          >{{
            item.costEstimate == null
              ? "费用待填写"
              : "预估 " + money(item.costEstimate)
          }}<span v-if="item.scheduleLocked" class="wb-lock"
            >智能调整固定</span
          ></span
        ></span
      >
    </button>
    <div v-if="selected" :id="'fields-' + nodeKey"><slot /></div>
  </article>
</template>
