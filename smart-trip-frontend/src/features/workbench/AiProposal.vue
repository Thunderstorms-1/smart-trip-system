<script setup lang="ts">
import type { Trip, Task } from "../types";
import { money } from "../api";
import TripRead from "../TripRead.vue";
defineProps<{
  current: Trip;
  preview?: Trip;
  task?: Task;
  busy: boolean;
  dirty: boolean;
}>();
const prompt = defineModel<string>({ required: true });
const emit = defineEmits<{ generate: []; apply: []; close: [] }>();
</script>
<template>
  <label
    >你想怎么调整？<textarea
      v-model="prompt"
      maxlength="3000"
      rows="4"
      placeholder="例如第二天减少步行，增加一个室内景点"
    ></textarea></label
  ><button
    class="button"
    :disabled="
      busy || Boolean(task && ['QUEUED', 'RUNNING'].includes(task.status))
    "
    @click="emit('generate')"
  >
    生成修改预览
  </button>
  <p v-if="task && ['QUEUED', 'RUNNING'].includes(task.status)" class="notice">
    <span class="status-pulse"></span>正在生成预览，原路书尚未改变。
  </p>
  <p v-if="task?.errorMessage" class="form-error">
    {{ task.errorMessage }}
  </p>
  <template v-if="preview"
    ><div class="preview-columns">
      <div>
        <h3>当前安排</h3>
        <template v-for="(d, i) in current.days" :key="i"
          ><strong>第 {{ i + 1 }} 天 · {{ d.dayTheme }}</strong>
          <ol>
            <li v-for="x in d.items" :key="x.id">
              {{ x.itemName }} · {{ money(x.costEstimate) }}
            </li>
          </ol></template
        >
      </div>
      <div>
        <h3>AI 建议</h3>
        <template v-for="(d, i) in preview.days" :key="i"
          ><strong>第 {{ i + 1 }} 天 · {{ d.dayTheme }}</strong>
          <ol>
            <li v-for="(x, n) in d.items" :key="n">
              {{ x.itemName }} · {{ money(x.costEstimate) }}
            </li>
          </ol></template
        >
      </div>
    </div>
    <details>
      <summary>查看完整修改建议（日期、备注与时间）</summary>
      <TripRead :trip="preview" />
    </details>
    <div class="button-row">
      <button class="button" :disabled="busy || dirty" @click="emit('apply')">
        确认保存修改</button
      ><button class="button secondary" @click="emit('close')">暂不采用</button>
    </div></template
  >
</template>
