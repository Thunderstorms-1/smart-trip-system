<script setup lang="ts">
defineProps<{
  destination: string;
  expanded: boolean;
  toolOpen: boolean;
  insightOpen: boolean;
  inspectorOpen: boolean;
  geoOpen: boolean;
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
      'geo-open': geoOpen,
    }"
  >
    <slot name="header" />
    <div class="workbench-workspace">
      <div class="wb-planning-canvas"><slot name="canvas" /></div>
      <div class="wb-rail-region"><slot name="rail" /></div>
      <div class="wb-timeline-region"><slot name="timeline" /></div>
      <aside
        v-show="!toolOpen && !inspectorOpen && !geoOpen"
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
