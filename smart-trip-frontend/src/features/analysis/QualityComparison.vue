<script setup lang="ts">
import type { QualityComparison } from "../types";
import { dimensionLabels, stateLabels } from "./qualityLabels";
defineProps<{ comparison: QualityComparison }>();
</script>
<template>
  <section aria-label="质量前后对比" class="quality-section">
    <h3>规则计算的质量前后对比</h3>
    <p>
      原总分 {{ comparison.before.totalScore ?? "暂不评" }} → 调整后
      {{ comparison.after.totalScore ?? "暂不评" }}。未知分项不按 0 或 100
      比较。
    </p>
    <div class="quality-grid">
      <article
        class="analysis-card"
        v-for="(d, index) in comparison.before.dimensions"
        :key="d.key"
      >
        <h4>{{ dimensionLabels[d.key] }}</h4>
        <p>
          {{ d.score ?? stateLabels[d.status] }} →
          {{
            comparison.after.dimensions[index]?.score ??
            stateLabels[comparison.after.dimensions[index]?.status ?? "UNKNOWN"]
          }}
        </p>
      </article>
    </div>
    <p>
      已消除质量提醒 {{ comparison.resolved.length }} 项 · 仍存在
      {{ comparison.remaining.length }} 项 · 新出现
      {{ comparison.added.length }} 项。
    </p>
    <details>
      <summary>调整后仍需处理的质量问题</summary>
      <p v-for="f in comparison.after.findings" :key="f.id">{{ f.message }}</p>
      <p v-if="!comparison.after.findings.length">
        没有已知质量提醒；尚未取得的路线或天气依据仍需核实。
      </p>
    </details>
    <p>
      <small
        >同一规则策略与来源快照计算；新路线尚未复核时不承诺节省交通时间。</small
      >
    </p>
  </section>
</template>
