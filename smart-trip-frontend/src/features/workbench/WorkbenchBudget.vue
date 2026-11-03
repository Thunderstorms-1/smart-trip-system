<script setup lang="ts">
import { computed } from "vue";
import type { Trip, ItemType } from "../types";
import { money } from "../api";
const props = defineProps<{ trip: Trip }>();
const categories: Array<{ type: ItemType; name: string; color: string }> = [
  { type: "TRAFFIC", name: "交通", color: "#479de2" },
  { type: "HOTEL", name: "住宿", color: "#40bbae" },
  { type: "FOOD", name: "餐饮", color: "#efbd6c" },
  { type: "SCENIC", name: "景点", color: "#ee8e7f" },
  { type: "OTHER", name: "其他", color: "#8f9bc9" },
];
const items = computed(() => props.trip.days.flatMap((day) => day.items));
const known = computed(() =>
  items.value.filter(
    (item) =>
      typeof item.costEstimate === "number" &&
      Number.isFinite(item.costEstimate) &&
      item.costEstimate >= 0,
  ),
);
const missing = computed(() => items.value.length - known.value.length);
const sum = computed(() =>
  known.value.reduce((total, item) => total + item.costEstimate, 0),
);
const rows = computed(() =>
  categories.map((category) => ({
    ...category,
    amount: known.value
      .filter((item) => item.itemType === category.type)
      .reduce((total, item) => total + item.costEstimate, 0),
  })),
);
const target = computed(() =>
  typeof props.trip.totalBudget === "number" &&
  Number.isFinite(props.trip.totalBudget) &&
  props.trip.totalBudget >= 0
    ? props.trip.totalBudget
    : null,
);
const ring = computed(() => {
  if (sum.value <= 0) return "#c9dfed";
  let offset = 0;
  return (
    "conic-gradient(" +
    rows.value
      .map((row) => {
        const start = offset;
        offset += (row.amount / sum.value) * 100;
        return row.color + " " + start + "% " + offset + "%";
      })
      .join(",") +
    ")"
  );
});
</script>
<template>
  <section class="wb-budget-section" aria-labelledby="wb-budget-title">
    <header>
      <h3 id="wb-budget-title">费用概览</h3>
      <span>当前路书</span>
    </header>
    <div class="wb-budget-summary">
      <div
        class="wb-budget-ring"
        :style="{ background: ring }"
        aria-hidden="true"
      >
        <span></span>
      </div>
      <div>
        <small>已填活动预估</small><strong>{{ money(sum) }}</strong
        ><span v-if="target != null">路书记录预估 {{ money(target) }}</span
        ><span v-else>路书预估尚未填写</span>
      </div>
    </div>
    <details>
      <summary>查看费用分项</summary>
      <dl>
        <div v-for="row in rows" :key="row.type">
          <dt>
            <i :style="{ background: row.color }" aria-hidden="true"></i
            >{{ row.name }}
          </dt>
          <dd>{{ money(row.amount) }}</dd>
        </div>
      </dl>
    </details>
    <p v-if="missing" class="wb-budget-note">
      {{ missing }} 项费用尚未填写，未计入合计。
    </p>
    <p v-else-if="!items.length" class="wb-budget-note">
      添加活动并填写费用后，显示预估汇总。
    </p>
    <p class="wb-budget-note">
      按现有费用字段汇总，仅为预估，不代表已核实票价或实际支出。
    </p>
  </section>
</template>
<style scoped>
.wb-budget-section {
  color: var(--wb-v5-panel-ink);
  margin-top: 14px;
  padding: 15px;
  border: 1px solid var(--wb-v5-edge);
  border-radius: 15px;
  background: var(--wb-v5-subpanel);
  box-shadow: var(--wb-v5-shadow);
}
header {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
h3 {
  font:
    650 16px/1.4 "Segoe UI",
    "Microsoft YaHei",
    sans-serif;
  margin: 0;
}
header > span {
  font-size: 11px;
  color: var(--wb-v5-panel-muted);
}
.wb-budget-summary {
  display: flex;
  align-items: center;
  gap: 16px;
}
.wb-budget-ring {
  width: 76px;
  height: 76px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: none;
}
.wb-budget-ring > span {
  width: 53px;
  height: 53px;
  border-radius: 50%;
  background: var(--wb-v5-subpanel);
  box-shadow: inset 0 1px 0 var(--wb-v5-edge);
}
.wb-budget-summary > div:last-child {
  min-width: 0;
  display: grid;
  gap: 3px;
}
small {
  font-size: 12px;
  color: var(--wb-v5-panel-muted);
}
strong {
  font-size: 24px;
  line-height: 1.35;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}
.wb-budget-summary div > span {
  font-size: 12px;
  color: var(--wb-v5-panel-muted);
}
details {
  margin-top: 13px;
  font-size: 13px;
}
summary {
  cursor: pointer;
  padding: 4px 0;
  color: var(--wb-v5-panel-ink);
}
summary:focus-visible {
  outline: 2px solid #187aba;
  outline-offset: 3px;
  border-radius: 3px;
}
dl {
  margin: 7px 0;
}
dl > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
  border-bottom: 1px solid var(--wb-v5-edge);
}
dt {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}
i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.wb-budget-note {
  font-size: 11px;
  line-height: 1.6;
  color: var(--wb-v5-panel-muted);
  margin: 8px 0 0;
}
</style>
