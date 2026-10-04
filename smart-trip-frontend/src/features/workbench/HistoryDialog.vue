<script setup lang="ts">
import type { Revision } from "../types";
defineProps<{
  revisions: Revision[];
  editable: boolean;
  busy: boolean;
  dirty: boolean;
}>();
const emit = defineEmits<{ restore: [id: number] }>();
</script>
<template>
  <p class="muted">恢复时会保留当前已保存版本，历史记录展示最近50次修改。</p>
  <div v-for="r in revisions" :key="r.id" class="packing-row">
    <span>版本 {{ r.version }} · {{ r.create_time }}</span
    ><button
      v-if="editable"
      class="text-button"
      :disabled="busy || dirty"
      @click="emit('restore', r.id)"
    >
      恢复
    </button>
  </div>
  <p v-if="!revisions.length" class="empty">还没有历史记录。</p>
</template>
