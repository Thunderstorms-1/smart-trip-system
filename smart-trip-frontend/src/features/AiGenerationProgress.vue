<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { Task } from "./types";
import { userText } from "./trip-draft-presentation";
import HomeIcon from "./HomeIcon.vue";
import RagSources from "./RagSources.vue";
import "./ai-generation-progress.css";

const props = withDefaults(
  defineProps<{
    task?: Task;
    submitting: boolean;
    submissionPhase?: string;
    submittedAt?: number;
    reconnecting?: boolean;
    error?: string;
  }>(),
  { submissionPhase: "", reconnecting: false, error: "" },
);
defineEmits<{ retry: [] }>();

const originalStages = [
  { phase: "REQUEST", label: "提交旅行需求", detail: "创建这一次规划任务" },
  { phase: "QUEUED", label: "任务排队", detail: "等待任务开始处理" },
  { phase: "MODEL", label: "AI 规划中", detail: "等待完整的规划结果" },
  {
    phase: "MODEL_COMPLETED",
    label: "模型输出完成",
    detail: "已收到完整模型响应",
  },
  {
    phase: "NORMALIZING",
    label: "整理每日行程",
    detail: "解析活动并整理时间顺序",
  },
  {
    phase: "VALIDATING",
    label: "检查行程与预算",
    detail: "核对费用、时间和已确认需求",
  },
  {
    phase: "PREVIEW",
    label: "草稿预览就绪",
    detail: "可继续修改，确认后才正式保存",
  },
];
const stages = computed(() => {
  const value = [...originalStages];
  if (
    props.task?.phase === "RETRIEVING" ||
    props.task?.generation?.phaseHistory?.some((e) => e.phase === "RETRIEVING")
  )
    value.splice(2, 0, {
      phase: "RETRIEVING",
      label: "检索旅行参考资料",
      detail: "查询适用的旅行知识与来源",
    });
  return value;
});
const now = ref(Date.now());
const pendingAt = ref<number | undefined>(
  props.submitting ? Date.now() : undefined,
);
const observedEnd = ref<number>();
let clock: ReturnType<typeof setInterval> | undefined;
const terminalStatuses = new Set(["FAILED", "PREVIEW", "APPLIED", "SUCCEEDED"]);
const active = computed(() =>
  props.task
    ? !terminalStatuses.has(props.task.status)
    : props.submitting || props.reconnecting,
);

// JDBC DATETIME without an offset uses this project's configured Asia/Shanghai database time.
// Explicit ISO offsets from the worker's phase history always take precedence.
function timestamp(value?: string | null): number | null {
  if (!value) return null;
  let iso = value.trim().replace(" ", "T");
  if (!/(?:Z|[+-]\d{2}:?\d{2})$/i.test(iso)) {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(iso))
      return null;
    iso += "+08:00";
  }
  const parsed = Date.parse(iso);
  return Number.isFinite(parsed) ? parsed : null;
}
const history = computed(() => props.task?.generation?.phaseHistory ?? []);
const currentPhase = computed(() => {
  const task = props.task;
  if (!task) return props.reconnecting ? "RECOVERING" : "REQUEST";
  if (["PREVIEW", "APPLIED", "SUCCEEDED"].includes(task.status))
    return "PREVIEW";
  if (task.status === "QUEUED") return "QUEUED";
  if (task.phase || task.generation?.phase)
    return task.phase || task.generation?.phase || "";
  if (task.status === "FAILED")
    return history.value[history.value.length - 1]?.phase ?? "FAILED";
  return "RUNNING";
});
const startAt = computed(
  () =>
    timestamp(props.task?.createdAt) ??
    timestamp(props.task?.startedAt) ??
    timestamp(props.task?.generation?.startedAt) ??
    props.submittedAt ??
    pendingAt.value,
);
const endAt = computed(() => {
  if (active.value) return now.value;
  const prepared = history.value.find((entry) => entry.phase === "PREVIEW");
  return timestamp(prepared?.at) ?? observedEnd.value;
});
const elapsed = computed(() => {
  if (startAt.value == null || endAt.value == null) return null;
  const seconds = Math.max(0, Math.floor((endAt.value - startAt.value) / 1000));
  const minutes = Math.floor(seconds / 60);
  return minutes
    ? minutes + " 分 " + String(seconds % 60).padStart(2, "0") + " 秒"
    : seconds + " 秒";
});
const failed = computed(
  () =>
    props.task?.status === "FAILED" || Boolean(props.error && !active.value),
);
const title = computed(() => {
  if (props.reconnecting && !props.task) return "正在找回这段旅程";
  if (failed.value) return "这次规划暂未完成";
  if (!props.task) return "从一个想法，向旅程出发";
  if (currentPhase.value === "QUEUED") return "旅行想法，已在等待出发";
  if (currentPhase.value === "RETRIEVING") return "正在检索这段旅程的参考资料";
  if (currentPhase.value === "MODEL") return "正在为你构思这段旅程";
  if (currentPhase.value === "MODEL_COMPLETED") return "旅程已有轮廓";
  if (currentPhase.value === "NORMALIZING") return "把旅程，整理成每一天";
  if (currentPhase.value === "VALIDATING") return "再为这段旅程，检查一遍";
  if (currentPhase.value === "PREVIEW") return "你的旅行草稿已就绪";
  return "正在处理这次旅行规划";
});
const description = computed(() => {
  if (props.reconnecting && !props.task)
    return "暂时无法读取原任务状态。正在查询同一个任务，不会重新提交需求或再次调用模型。";
  if (failed.value)
    return (
      userText(props.error || props.task?.errorMessage) ||
      "请查询原任务，查看原因或恢复已有草稿。"
    );
  if (!props.task)
    return props.submissionPhase || "正在提交需求并创建任务，请稍候。";
  if (currentPhase.value === "RETRIEVING")
    return "正在根据已确认的目的地和旅行条件检索资料，不会改变你的预算和日期约束。";
  if (currentPhase.value === "MODEL")
    return "正在等待 AI 返回完整计划。当前显示真实任务阶段，不逐字展示未校验的内容。";
  if (currentPhase.value === "QUEUED")
    return "任务已创建，正在等待处理。你可以离开后通过原任务继续查看。";
  if (currentPhase.value === "PREVIEW")
    return "尚未创建正式路书。可以先检查和修改，再由你确认保存。";
  if (currentPhase.value === "RUNNING")
    return "已找到原任务，正在等待最新处理阶段。无需重新提交。";
  return "正在处理已返回的规划内容，检查完成后进入可编辑草稿。";
});
const rows = computed(() => {
  const currentIndex = stages.value.findIndex(
    (stage) => stage.phase === currentPhase.value,
  );
  return stages.value.map((stage, index) => {
    const evidence = history.value.find((entry) => entry.phase === stage.phase);
    const created = stage.phase === "REQUEST" && Boolean(props.task?.id);
    let state = "pending";
    if (stage.phase === currentPhase.value)
      state = failed.value ? "failed" : active.value ? "active" : "done";
    else if (evidence || created) state = "done";
    else if (
      currentIndex > index ||
      (props.task && terminalStatuses.has(props.task.status))
    )
      state = "unrecorded";
    const at =
      stage.phase === "REQUEST"
        ? timestamp(props.task?.createdAt)
        : timestamp(evidence?.at);
    return { ...stage, state, at };
  });
});
function timeLabel(value: number): string {
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(value);
}
function updateClock() {
  if (active.value && !clock)
    clock = setInterval(() => {
      now.value = Date.now();
    }, 1000);
  if (!active.value && clock) {
    clearInterval(clock);
    clock = undefined;
  }
}
watch(
  () => props.submitting,
  (value) => {
    if (value && !props.task && props.submittedAt == null)
      pendingAt.value = Date.now();
  },
);
watch(
  () => props.task?.id,
  () => {
    observedEnd.value = undefined;
  },
);
watch(
  () => props.task?.status,
  (value, previous) => {
    if (
      value &&
      terminalStatuses.has(value) &&
      previous &&
      !terminalStatuses.has(previous)
    )
      observedEnd.value = Date.now();
  },
);
onMounted(() => {
  updateClock();
});
watch(active, updateClock);
onBeforeUnmount(() => {
  if (clock) clearInterval(clock);
});
</script>

<template>
  <section
    class="ai-generation-progress"
    :aria-busy="active"
    aria-labelledby="ai-progress-heading"
  >
    <header class="agp-header">
      <span class="agp-emblem" :class="{ 'is-active': active }"
        ><HomeIcon name="route"
      /></span>
      <div class="agp-heading" aria-live="polite" aria-atomic="true">
        <span class="agp-eyebrow">SMARTTRIP · 旅行规划</span>
        <h2 id="ai-progress-heading" tabindex="-1">{{ title }}</h2>
      </div>
      <span
        v-if="elapsed != null"
        class="agp-elapsed"
        role="timer"
        aria-live="off"
      >
        <HomeIcon name="clock" />{{ active ? "已等待" : "本次用时" }}
        {{ elapsed }}
      </span>
    </header>
    <p class="agp-description" :class="{ 'is-error': failed }" role="status">
      {{ description }}
    </p>
    <p v-if="reconnecting" class="agp-connection" role="status">
      正在重新连接任务状态，将继续查询同一个任务。
    </p>
    <ol class="agp-stages" aria-label="真实任务处理阶段">
      <li
        v-for="(row, index) in rows"
        :key="row.phase"
        :class="'is-' + row.state"
        :aria-current="row.state === 'active' ? 'step' : undefined"
      >
        <span class="agp-step-marker" aria-hidden="true">{{
          row.state === "done" ? "✓" : row.state === "failed" ? "!" : index + 1
        }}</span>
        <div class="agp-stage-text">
          <strong>{{ row.label }}</strong>
          <small
            v-if="
              row.state === 'active' ||
              row.state === 'done' ||
              row.state === 'failed'
            "
            >{{ row.detail }}</small
          >
          <small v-else-if="row.state === 'unrecorded'">此历史阶段未记录</small>
        </div>
        <time v-if="row.at != null" class="agp-stage-time">{{
          timeLabel(row.at)
        }}</time>
        <span v-else-if="row.state === 'active'" class="agp-stage-state"
          >进行中</span
        >
        <span v-else-if="row.state === 'pending'" class="agp-stage-state"
          >等待</span
        >
      </li>
    </ol>
    <RagSources :task="task" />
    <footer class="agp-footer">
      <p>查询与重连只读取原任务，不会再次生成或重复扣费。</p>
      <button
        v-if="task?.id && (reconnecting || failed || error)"
        type="button"
        class="agp-retry"
        @click="$emit('retry')"
      >
        查询最新状态 <HomeIcon name="arrow" />
      </button>
    </footer>
  </section>
</template>
