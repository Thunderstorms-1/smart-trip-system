<script setup lang="ts">
import { computed } from "vue";
import type { Item } from "../types";
import { money } from "../api";
import { userText } from "../trip-draft-presentation";
import HomeIcon from "../HomeIcon.vue";

const props = withDefaults(
  defineProps<{
    item: Item;
    index: number;
    nodeKey: string;
    selected: boolean;
    editable?: boolean;
    busy?: boolean;
    canMoveUp?: boolean;
    canMoveDown?: boolean;
  }>(),
  { editable: false, busy: false, canMoveUp: false, canMoveDown: false },
);
const emit = defineEmits<{ select: []; move: [delta: number]; remove: [] }>();
const types: Record<string, string> = {
  SCENIC: "景点",
  FOOD: "美食",
  HOTEL: "住宿",
  TRAFFIC: "交通",
  OTHER: "活动",
};
const duration = computed(() =>
  typeof props.item.costTimeHours !== "number" ||
  !Number.isFinite(props.item.costTimeHours)
    ? "时长待填写"
    : props.item.costTimeHours + " 小时",
);
const cost = computed(() =>
  typeof props.item.costEstimate === "number" &&
  Number.isFinite(props.item.costEstimate)
    ? "预估 " + money(props.item.costEstimate)
    : "费用待填写",
);
const tips = computed(() => userText(props.item.aiTips || ""));
const transport = computed(() => userText(props.item.transportTips || ""));
const excerpt = computed(() => {
  const text = tips.value.replace(/\s+/g, " ").trim();
  const first = text.match(/^.*?[。！？]/)?.[0] || text;
  return first.length > 88 ? first.slice(0, 88) + "…" : first;
});
function selectSurface(event: MouseEvent) {
  if (
    !(event.target instanceof Element) ||
    event.target.closest(
      "button, a, details, input, select, textarea, label",
    ) ||
    window.getSelection()?.toString()
  )
    return;
  emit("select");
}
function menuAction(event: Event, action: "up" | "down" | "remove") {
  if (!props.editable || props.busy) return;
  (event.currentTarget as HTMLElement)
    .closest("details")
    ?.removeAttribute("open");
  if (action === "remove") emit("remove");
  else emit("move", action === "up" ? -1 : 1);
}
</script>
<template>
  <article
    class="wb-timeline-node"
    :class="{ 'is-selected': selected }"
    :id="item.id ? 'plan-item-' + item.id : undefined"
    :data-activity-key="nodeKey"
    :style="{ '--wb-node-reveal-delay': Math.min(index, 4) * 22 + 'ms' }"
    @click="selectSurface"
    tabindex="-1"
  >
    <div class="wb-node-rail" aria-hidden="true">
      <span class="wb-node-time">{{ item.startTime || "时间未定" }}</span>
      <span class="wb-node-order">{{ index + 1 }}</span>
    </div>
    <div class="wb-node-card">
      <div class="wb-node-card-heading">
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
          <strong>{{ item.itemName || "新地点" }}</strong>
          <span class="wb-node-accessible-time">{{
            item.startTime || "时间未定"
          }}</span>
        </button>
        <div class="wb-node-card-actions">
          <button
            type="button"
            class="wb-node-edit"
            :aria-label="
              (selected
                ? '收起编辑：'
                : editable
                  ? '编辑安排：'
                  : '查看安排：') + (item.itemName || '新地点')
            "
            :aria-expanded="selected"
            @click="emit('select')"
          >
            {{ selected ? "收起" : editable ? "编辑" : "查看" }}
          </button>
          <details
            v-if="editable"
            class="wb-node-more"
            @keydown.esc.stop="
              ($event.currentTarget as HTMLDetailsElement).open = false
            "
          >
            <summary
              :aria-label="'更多操作：' + (item.itemName || '新地点')"
              :aria-disabled="busy"
              @click="busy && $event.preventDefault()"
            >
              •••
            </summary>
            <div
              class="wb-node-menu"
              :aria-label="'安排操作：' + (item.itemName || '新地点')"
            >
              <button
                type="button"
                :disabled="busy || !canMoveUp"
                @click="menuAction($event, 'up')"
              >
                向上移动
              </button>
              <button
                type="button"
                :disabled="busy || !canMoveDown"
                @click="menuAction($event, 'down')"
              >
                向下移动
              </button>
              <button
                type="button"
                class="wb-danger"
                :disabled="busy"
                @click="menuAction($event, 'remove')"
              >
                删除安排
              </button>
            </div>
          </details>
        </div>
      </div>
      <div class="wb-node-meta">
        <span class="wb-node-type"
          ><HomeIcon
            :name="
              item.itemType === 'FOOD'
                ? 'food'
                : item.itemType === 'TRAFFIC'
                  ? 'route'
                  : 'pin'
            "
          />{{ types[item.itemType] || "活动" }}</span
        >
        <span class="wb-node-duration"
          ><HomeIcon name="clock" />{{ duration }}</span
        >
        <span class="wb-node-cost">{{ cost }}</span>
        <span v-if="item.scheduleLocked" class="wb-lock">时间已固定</span>
      </div>
      <p v-if="item.address" class="wb-node-address">
        <HomeIcon name="pin" /><span>{{ item.address }}</span>
      </p>
      <p v-if="excerpt" class="wb-node-note">{{ excerpt }}</p>
      <details
        v-if="transport || tips.length > excerpt.length"
        class="wb-node-context"
      >
        <summary>旅行提示与交通</summary>
        <p v-if="tips.length > excerpt.length">{{ tips }}</p>
        <p v-if="transport"><span>交通</span>{{ transport }}</p>
      </details>
    </div>
  </article>
</template>
