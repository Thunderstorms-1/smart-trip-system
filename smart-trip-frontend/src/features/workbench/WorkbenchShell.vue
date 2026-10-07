<script setup lang="ts">
defineProps<{
  destination: string;
  expanded: boolean;
  toolOpen: boolean;
  insightOpen: boolean;
  inspectorOpen: boolean;
}>();
</script>
<template>
  <div
    class="workbench-v3"
    :class="{
      'timeline-expanded': expanded,
      'tool-open': toolOpen,
      'insight-open': insightOpen,
      'inspector-open': inspectorOpen,
    }"
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
        v-show="!toolOpen && !inspectorOpen"
        id="workbench-insight"
        class="wb-context-region"
        aria-label="行程洞察面板"
      >
        <slot name="context" />
      </aside>
      <aside
        id="workbench-inspector-host"
        class="wb-inspector-region"
        v-show="inspectorOpen && !toolOpen"
        aria-label="选中安排编辑面板"
      ></aside>
      <slot name="tools" />
    </div>
  </div>
</template>
