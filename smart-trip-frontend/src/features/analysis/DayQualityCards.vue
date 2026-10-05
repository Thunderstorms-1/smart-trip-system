<script setup lang="ts">
import type { QualityReport } from "../types";
import { loadLabels, minutes } from "./qualityLabels";
defineProps<{ quality: QualityReport; disabled: boolean }>();
defineEmits<{ focus: [dayId: number, itemId?: number] }>();
</script>
<template>
  <section aria-label="每日负荷">
    <h3>每日负荷与时间覆盖</h3>
    <div class="quality-grid">
      <article v-for="d in quality.days" :key="d.dayId" class="analysis-card">
        <h4>第 {{ d.dayIndex }} 天 · {{ loadLabels[d.loadLevel] }}</h4>
        <p>
          有效时间 {{ d.coverage.complete }} / {{ d.coverage.total }} 项 ·
          {{ d.coverage.percent }}%
        </p>
        <dl class="quality-metrics">
          <dt>活动 / 景点</dt>
          <dd>
            {{ d.metrics.activityCount }} / {{ d.metrics.scenicCount }} 项
          </dd>
          <dt>已知活动占用</dt>
          <dd>{{ minutes(d.metrics.occupiedActivityMinutes) }}</dd>
          <dt>安排跨度</dt>
          <dd>{{ minutes(d.metrics.daySpanMinutes) }}</dd>
          <dt>最长连续安排</dt>
          <dd>{{ minutes(d.metrics.longestContinuousMinutes) }}</dd>
          <dt>未分配间隔</dt>
          <dd>{{ minutes(d.metrics.unallocatedMinutes) }}</dd>
          <dt>已填写餐饮 / 交通</dt>
          <dd>
            {{ d.metrics.scheduledFoodMinutes }} /
            {{ d.metrics.scheduledTrafficMinutes }} 分钟
          </dd>
        </dl>
        <p><small>已知统计不补缺失时间；未分配间隔不等于已确认休息。</small></p>
        <details v-if="d.metrics.gaps.length">
          <summary>逐段计划间隔</summary>
          <p v-for="g in d.metrics.gaps" :key="g.from + ':' + g.to">
            计划间隔 {{ g.plannedMinutes }} 分钟 · 路程
            {{ g.routeMinutes == null ? "尚未测得" : g.routeMinutes + " 分钟"
            }}<template v-if="g.remainingMinutes != null">
              · 余量 {{ g.remainingMinutes }} 分钟</template
            >
          </p>
        </details>
        <button
          class="text-button"
          :disabled="disabled"
          @click="$emit('focus', d.dayId)"
        >
          查看第 {{ d.dayIndex }} 天安排
        </button>
      </article>
    </div>
  </section>
</template>
