<script setup lang="ts">
import { ref, computed } from "vue";
import type { QualityReport, Trip } from "../types";
const p = defineProps<{
  quality: QualityReport;
  trip: Trip;
  dayIds: number[];
  disabled: boolean;
}>();
defineEmits<{ focus: [dayId: number, itemId?: number] }>();
const selected = ref(false);
const rows = computed(() =>
  p.quality.findings.filter(
    (f) => !selected.value || f.dayIds.some((id) => p.dayIds.includes(id)),
  ),
);
const day = (id: number) => p.trip.days.findIndex((d) => d.id === id) + 1;
</script>
<template>
  <section aria-label="质量提醒">
    <h3>质量提醒与待补充信息</h3>
    <label class="quality-filter"
      ><input type="checkbox" v-model="selected" />仅看选中日期的问题</label
    >
    <p v-if="!rows.length">
      当前已提供信息中没有这类提醒；未知信息仍不代表已经确认可行。
    </p>
    <article v-for="f in rows" :key="f.id" class="analysis-card">
      <strong
        >{{ f.severity === "WARNING" ? "需要留意" : "信息待确认" }} · 第
        {{ f.dayIds.map(day).join("、") }} 天</strong
      >
      <p>{{ f.message }}</p>
      <p v-for="e in f.evidence" :key="e.metric">
        规则依据：{{ e.actual }} {{ e.unit }} · 参考阈值 {{ e.threshold }}
        {{ e.unit
        }}<template v-if="f.penalty"> · 该规则扣 {{ f.penalty }} 分</template>
      </p>
      <p>
        <small>{{
          f.itemIds
            .map(
              (id) =>
                trip.days.flatMap((d) => d.items).find((i) => i.id === id)
                  ?.itemName,
            )
            .filter(Boolean)
            .join(" / ")
        }}</small>
      </p>
      <p v-if="f.action === 'MANUAL'" class="notice">
        需要手动补充；AI 不会新增餐饮或休息节点。
      </p>
      <button
        class="text-button"
        :disabled="disabled"
        @click="$emit('focus', f.dayIds[0]!, f.itemIds[0])"
      >
        定位相关安排
      </button>
    </article>
  </section>
</template>
