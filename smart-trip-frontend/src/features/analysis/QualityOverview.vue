<script setup lang="ts">
import type { QualityReport, Trip } from "../types";
import { dimensionLabels, stateLabels } from "./qualityLabels";
const props = defineProps<{ quality: QualityReport; trip: Trip }>();
function reason(id: string) {
  const f = props.quality.findings.find((f) => f.id === id);
  if (f) return f.message;
  const i = props.quality.basicIssues?.find((i) => i.id === id);
  return i
    ? "基础检查 · 第 " +
        (props.trip.days.findIndex((d) => d.id === i.dayId) + 1) +
        " 天：" +
        i.message
    : id;
}
</script>
<template>
  <section aria-label="全行程质量报告" class="quality-section">
    <p class="eyebrow">PLAN QUALITY</p>
    <h3>全行程质量报告</h3>
    <p>评价整份已保存路书；AI 仅调整下方选中的日期。</p>
    <p class="quality-score" v-if="quality.totalScore != null">
      {{ quality.totalScore }} <small>/ 100</small>
    </p>
    <p v-else class="notice">
      信息不足，暂不评总分。可用分项和已知问题仍会展示。
    </p>
    <div class="quality-grid">
      <article
        class="analysis-card"
        v-for="d in quality.dimensions"
        :key="d.key"
      >
        <h4>{{ dimensionLabels[d.key] }}</h4>
        <p>
          {{ d.score == null ? stateLabels[d.status] : d.score + " / 100" }}
        </p>
        <progress
          v-if="d.score != null"
          :value="d.score"
          max="100"
          :aria-label="dimensionLabels[d.key] + '评分'"
        />
        <p v-for="r in d.unknownReasons" :key="r">{{ r }}</p>
        <details v-if="d.deductionIds.length">
          <summary>查看扣分原因</summary>
          <p v-for="id in d.deductionIds" :key="id">{{ reason(id) }}</p>
        </details>
      </article>
    </div>
    <p>
      <small
        >规则 {{ quality.rulesVersion }} ·
        仅评价已填写计划，不代表真实交通或开放时间。单日均衡不适用，适用维度权重归一化。</small
      >
    </p>
    <p>
      路线：{{ stateLabels[quality.external.route.status] }} ·
      {{ quality.external.route.available }} /
      {{ quality.external.route.expected }} 段；天气：{{
        stateLabels[quality.external.weather.status]
      }}
      · {{ quality.external.weather.available }} /
      {{ quality.external.weather.expected }} 天。
    </p>
    <p>
      <small>路线与天气不参与核心总分；尚未取得有效依据的部分不能判断。</small>
    </p>
  </section>
</template>
