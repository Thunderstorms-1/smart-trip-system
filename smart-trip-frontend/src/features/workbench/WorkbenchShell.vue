<script setup lang="ts">
defineProps<{ destination: string; expanded: boolean; toolOpen: boolean }>();
</script>
<template>
  <div
    class="workbench-v3"
    :class="{ 'timeline-expanded': expanded, 'tool-open': toolOpen }"
  >
    <slot name="header" />
    <div class="workbench-workspace">
      <div
        class="wb-planning-canvas"
        role="img"
        :aria-label="destination + '行程规划画布，非地理地图'"
      >
        <div class="wb-canvas-title">
          <strong>{{ destination || "你的目的地" }}</strong>
        </div>
        <div class="wb-canvas-surface" aria-hidden="true"></div>
        <p class="wb-canvas-notice">
          规划画布 · 不表示地理位置、真实道路或路线
        </p>
      </div>
      <div class="wb-rail-region"><slot name="rail" /></div>
      <div class="wb-timeline-region"><slot name="timeline" /></div>
      <aside
        v-show="!toolOpen"
        class="wb-context-region"
        aria-label="当前路书概览"
      >
        <slot name="context" />
      </aside>
      <slot name="tools" />
    </div>
  </div>
</template>
