<script setup lang="ts">
import { computed } from "vue";
import type { AnalysisReport, QualityFinding, Trip } from "../types";
import { dimensionLabels } from "../analysis/qualityLabels";
const p = defineProps<{
  trip: Trip;
  report?: AnalysisReport;
  valid: boolean;
  state: string;
  busy: boolean;
  disabled: boolean;
  error: string;
}>();
const emit = defineEmits<{
  analyze: [];
  focus: [dayId: number, itemId?: number];
  close: [];
}>();
const quality = computed(() => (p.valid ? p.report?.quality : undefined));
const keys = ["TIME", "PACE", "MEAL_REST", "BALANCE"] as const;
const dimensions = computed(() =>
  keys.map((key) => ({
    key,
    value: quality.value?.dimensions.find((d) => d.key === key),
  })),
);
function score(key: string) {
  const d = quality.value?.dimensions.find((d) => d.key === key);
  return d?.status === "READY" && d.score != null ? d.score : null;
}
function label(key: string) {
  const d = quality.value?.dimensions.find((d) => d.key === key);
  return score(key) != null
    ? score(key) + " / 100"
    : d?.status === "NOT_APPLICABLE"
      ? "不适用"
      : "暂无法判断";
}
const findings = computed(() => {
  const q = quality.value;
  if (!q) return [];
  const rows = [...q.findings];
  const basic = q.basicIssues ?? p.report?.issues ?? [];
  return [
    ...rows,
    ...basic
      .filter((i) => !rows.some((f) => f.id === i.id))
      .map((i) => ({
        id: i.id,
        message: i.message,
        severity: i.severity,
        dayIds: [i.dayId],
        itemIds: i.itemIds,
        evidence: [],
      })),
  ];
});
function targets(f: Pick<QualityFinding, "dayIds" | "itemIds">) {
  // Resolve actual membership. A multi-day finding's first node need not belong to its first day.
  const nodes = p.trip.days.flatMap((d, n) =>
    d.items
      .filter((i) => i.id != null && f.itemIds.includes(i.id))
      .map((i) => ({
        dayId: d.id!,
        itemId: i.id,
        label: "第 " + (n + 1) + " 天 · " + i.itemName,
      })),
  );
  return nodes.length
    ? nodes
    : p.trip.days.flatMap((d, n) =>
        d.id != null && f.dayIds.includes(d.id)
          ? [
              {
                dayId: d.id,
                itemId: undefined,
                label: "第 " + (n + 1) + " 天安排",
              },
            ]
          : [],
      );
}
</script>
<template>
  <section
    class="wb-quality-insight"
    aria-label="行程洞察"
    @keydown.esc.stop="emit('close')"
  >
    <header class="wb-insight-heading">
      <h2>行程洞察</h2>
      <button class="wb-text-button wb-insight-close" @click="emit('close')">
        关闭洞察
      </button>
    </header>
    <p class="wb-insight-state" role="status">{{ state }}</p>
    <template v-if="quality">
      <p
        v-if="quality.totalScore != null"
        class="wb-insight-score"
        aria-label="行程总分"
      >
        {{ quality.totalScore }}<small> / 100</small>
      </p>
      <p v-else class="wb-insight-message">信息不足，暂不评总分。</p>
      <p class="wb-insight-caption">
        全行程 · 版本 {{ report?.baseVersion }} · 仅评价已填写的计划
      </p>
      <dl class="wb-insight-dimensions">
        <div v-for="d in dimensions" :key="d.key">
          <dt>{{ dimensionLabels[d.key] }}</dt>
          <dd>{{ label(d.key) }}</dd>
          <progress
            v-if="score(d.key) != null"
            :value="score(d.key)!"
            max="100"
            :aria-label="dimensionLabels[d.key] + '评分'"
          />
          <p v-for="r in d.value?.unknownReasons" :key="r">{{ r }}</p>
        </div>
      </dl>
      <section class="wb-insight-findings" aria-label="洞察问题">
        <h3>需要留意</h3>
        <p v-if="!findings.length" class="wb-insight-caption">
          已填写信息中未发现质量提醒；未知信息仍需核实。
        </p>
        <article v-for="f in findings" :key="f.id">
          <strong>{{
            f.severity === "ERROR"
              ? "需要处理"
              : f.severity === "WARNING"
                ? "需要留意"
                : "待补充"
          }}</strong>
          <p>{{ f.message }}</p>
          <details v-if="f.evidence.length">
            <summary>查看依据</summary>
            <p v-for="(e, index) in f.evidence" :key="index">
              {{ e.actual }} {{ e.unit }} · 参考阈值 {{ e.threshold }}
              {{ e.unit }}
            </p>
          </details>
          <button
            v-for="t in targets(f)"
            :key="t.itemId ?? t.dayId"
            class="wb-insight-target"
            :disabled="disabled"
            @click="emit('focus', t.dayId, t.itemId)"
          >
            定位 {{ t.label }}
          </button>
        </article>
      </section>
      <p class="wb-insight-caption">
        规则
        {{
          quality.rulesVersion
        }}。路线与天气不参与核心评分，未取得的外部信息不能判断。
      </p>
    </template>
    <p v-else class="wb-insight-message">
      保存后检查行程，查看时间、节奏、用餐休息与每日均衡。
    </p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <button
      class="wb-insight-analyze"
      :disabled="disabled || busy"
      @click="emit('analyze')"
    >
      {{ busy ? "检查中…" : quality ? "重新分析行程" : "分析已保存行程" }}
    </button>
  </section>
</template>
