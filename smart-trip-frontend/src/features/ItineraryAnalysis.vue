<script setup lang="ts">
import { watch } from "vue";
import type { Trip, Capabilities } from "./types";
import { useItineraryAnalysis } from "./useItineraryAnalysis";
import TripRead from "./TripRead.vue";
import QualityOverview from "./analysis/QualityOverview.vue";
import DayQualityCards from "./analysis/DayQualityCards.vue";
import QualityFindings from "./analysis/QualityFindings.vue";
import QualityComparison from "./analysis/QualityComparison.vue";
import { capabilityEnabled, capabilityNotice } from "./capabilities";
const props = defineProps<{
  trip: Trip;
  dayIndex: number;
  dirty: boolean;
  editable: boolean;
  busy: boolean;
  capabilities: Capabilities;
}>();
const emit = defineEmits<{
  applied: [trip: Trip];
  busy: [value: boolean];
  focus: [dayId: number, itemId?: number];
}>();
const a = useItineraryAnalysis(
  () => props,
  (trip) => emit("applied", trip),
);
const {
  dayIds,
  mode,
  report,
  task,
  error,
  busy,
  applying,
  running,
  stale,
  expired,
  canApply,
  sourceBusy,
  sourceErrors,
} = a;
watch(applying, (value) => emit("busy", value), { flush: "sync" });
const labels: Record<string, string> = {
  MOVE: "跨天移动",
  REORDER: "调整顺序",
  TIME: "修改开始时间",
};
const status: Record<string, string> = {
  READY: "已取得",
  PARTIAL: "部分可用",
  UNAVAILABLE: "无法取得",
  FAILED: "查询失败",
};
function dayNumber(id: number) {
  return props.trip.days.findIndex((d) => d.id === id) + 1;
}
function when(value?: string) {
  return value ? new Date(value).toLocaleString("zh-CN") : "未查询";
}
</script>
<template>
  <section class="paper panel-stack analysis-panel">
    <header>
      <p class="eyebrow">CHECK YOUR PLAN</p>
      <h2>行程诊断</h2>
      <p>先检查已有安排，再按需补充天气与路线。所有调整都由你预览确认。</p>
    </header>
    <p v-if="dirty" class="notice">请先保存当前修改，再分析。</p>
    <fieldset
      :disabled="busy || applying || props.busy || dirty || running"
      class="draft-fieldset"
    >
      <legend>调整范围（默认当前一天）</legend>
      <div class="analysis-days">
        <label v-for="(day, index) in trip.days" :key="day.id"
          ><input type="checkbox" v-model="dayIds" :value="day.id" />第
          {{ index + 1 }} 天</label
        >
      </div>
      <label
        >交通方式<select v-model="mode">
          <option value="walking">步行</option>
          <option value="driving">驾车</option>
        </select></label
      >
      <div class="button-row">
        <button class="button" @click="a.analyze" :disabled="!dayIds.length">
          {{ busy ? "检查中…" : "检查行程" }}</button
        ><button class="text-button" @click="a.restore">查看上次提案</button>
      </div>
    </fieldset>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <template v-if="report">
      <QualityOverview
        v-if="report.quality"
        :quality="report.quality"
        :trip="trip"
      />
      <section aria-label="基础检查结果">
        <h3>当前发现</h3>
        <p v-if="!report.issues.length">
          已提供的信息中未发现时间冲突；尚未查询的天气、路线仍无法判断。
        </p>
        <article
          v-for="issue in report.issues"
          :key="issue.id"
          class="analysis-card"
        >
          <strong
            >{{
              issue.severity === "ERROR"
                ? "时间冲突"
                : issue.severity === "WARNING"
                  ? "需要留意"
                  : "信息待补充"
            }}
            · 第 {{ dayNumber(issue.dayId) }} 天</strong
          >
          <p>{{ issue.message }}</p>
          <small>{{
            issue.itemIds
              .map(
                (id) =>
                  trip.days.flatMap((d) => d.items).find((i) => i.id === id)
                    ?.itemName,
              )
              .join(" / ")
          }}</small>
        </article>
      </section>
      <DayQualityCards
        v-if="report.quality"
        :quality="report.quality"
        :disabled="dirty || busy || applying || props.busy || running"
        @focus="(d, i) => emit('focus', d, i)"
      />
      <QualityFindings
        v-if="report.quality"
        :quality="report.quality"
        :trip="trip"
        :day-ids="dayIds"
        :disabled="dirty || busy || applying || props.busy || running"
        @focus="(d, i) => emit('focus', d, i)"
      />
      <section>
        <h3>按需补充依据</h3>
        <p v-if="!capabilities.geo" class="notice">
          基础检查可继续使用，路线与天气按各自配置提供，当前未调用外部服务。
        </p>
        <article v-for="id in report.dayIds" :key="id" class="analysis-card">
          <h4>第 {{ dayNumber(id) }} 天</h4>
          <div
            v-for="kind in ['WEATHER', 'ROUTE'] as const"
            :key="kind"
            class="analysis-source"
          >
            <p
              v-if="
                !capabilityEnabled(
                  capabilities,
                  kind === 'WEATHER' ? 'weather' : 'route',
                )
              "
              class="notice"
            >
              {{
                capabilityNotice(
                  capabilities,
                  kind === "WEATHER" ? "weather" : "route",
                )
              }}
            </p>
            <div class="button-row">
              <strong>{{ kind === "WEATHER" ? "日天气" : "路线耗时" }}</strong>
              <button
                class="text-button"
                :disabled="
                  !capabilityEnabled(
                    capabilities,
                    kind === 'WEATHER' ? 'weather' : 'route',
                  ) ||
                  dirty ||
                  props.busy ||
                  applying ||
                  sourceBusy[kind + ':' + id]
                "
                @click="a.source(id, kind)"
              >
                {{
                  sourceBusy[kind + ":" + id]
                    ? "查询中…"
                    : "查询 / 使用当前结果"
                }}
              </button>
              <button
                class="text-button"
                :disabled="
                  !capabilityEnabled(
                    capabilities,
                    kind === 'WEATHER' ? 'weather' : 'route',
                  ) ||
                  dirty ||
                  props.busy ||
                  applying ||
                  sourceBusy[kind + ':' + id]
                "
                @click="a.source(id, kind, true)"
              >
                重新查询
              </button>
            </div>
            <template
              v-for="source in report.sources.filter(
                (s) => s.dayId === id && s.kind === kind,
              )"
              :key="source.id"
              ><p>{{ status[source.status] }} · {{ source.summary }}</p>
              <small
                >来源：高德（失败或不可用时无有效数据） · 查询：{{
                  when(source.retrievedAt)
                }}<template v-if="source.issuedAt">
                  · 预报发布：{{ source.issuedAt }}</template
                ></small
              ></template
            >
            <p
              v-if="
                !report.sources.some((s) => s.dayId === id && s.kind === kind)
              "
            >
              尚未查询，不能判断。
            </p>
            <p v-if="sourceErrors[kind + ':' + id]" class="form-error">
              {{ sourceErrors[kind + ":" + id] }}
            </p>
          </div>
        </article>
      </section>
      <div>
        <button
          class="button"
          :disabled="
            !editable ||
            !capabilities.ai ||
            props.busy ||
            busy ||
            applying ||
            dirty ||
            running ||
            Object.values(sourceBusy).some(Boolean)
          "
          @click="a.generate"
        >
          {{ running ? "正在生成提案…" : "生成调整提案" }}
        </button>
        <p v-if="!capabilities.ai" class="notice">
          AI 尚未启用。不会使用模拟建议替代真实 AI。
        </p>
        <p>
          只调整所选日期的已有节点。固定安排不会移动，不改变地点、费用或停留时长。
        </p>
      </div>
    </template>
    <p v-if="running" class="notice">
      正在生成预览，原路书尚未改变。离开页面后可通过“查看上次提案”继续查看。
    </p>
    <p v-if="task?.errorMessage" class="form-error">{{ task.errorMessage }}</p>
    <section
      v-if="task?.status === 'PREVIEW' && task.proposal && task.result"
      aria-label="调整差异"
    >
      <h3>调整差异</h3>
      <QualityComparison
        v-if="task.proposal.qualityComparison"
        :comparison="task.proposal.qualityComparison"
      />
      <p>
        涉及第
        {{ task.proposal.dayIds.map(dayNumber).join("、") }}
        天，原路书尚未改变。
      </p>
      <p v-if="stale || expired" class="form-error">
        {{
          stale ? "路书版本已变化" : "外部分析依据已过期"
        }}，请重新检查并生成提案。当前提案保留供查看。
      </p>
      <p v-if="!task.proposal.changes.length" class="notice">
        无需调整，或当前约束下没有可行变更。请查看仍需处理的问题。
      </p>
      <article
        v-for="change in task.proposal.changes"
        :key="change.itemId"
        class="analysis-card"
      >
        <strong
          >{{ change.itemName }} ·
          {{ change.kinds.map((k) => labels[k]).join("、") }}</strong
        >
        <p>
          第 {{ change.fromDayIndex }} 天 / 第 {{ change.fromOrder }} 项 /
          {{ change.fromTime || "未定时间" }} → 第 {{ change.toDayIndex }} 天 /
          第 {{ change.toOrder }} 项 / {{ change.toTime || "未定时间" }}
        </p>
        <p
          v-for="(reason, index) in task.proposal.reasons.filter((r) =>
            r.itemIds.includes(change.itemId),
          )"
          :key="index"
        >
          {{ reason.text
          }}<small>（依据：{{ reason.evidenceIds.join("；") }}）</small>
        </p>
      </article>
      <p v-if="task.proposal.unverifiedRouteDayIds.length" class="notice">
        第
        {{ task.proposal.unverifiedRouteDayIds.map(dayNumber).join("、") }}
        天的路线发生变化，新增路段尚未复核。此提案不承诺节省交通时间；保存后可查询新路线。
      </p>
      <details>
        <summary>查看采用依据与剩余问题</summary>
        <p v-for="source in task.proposal.sources" :key="source.id">
          {{ source.summary }} · 查询：{{ when(source.retrievedAt) }}
        </p>
        <p v-for="issue in task.proposal.remainingIssues" :key="issue.id">
          第 {{ dayNumber(issue.dayId) }} 天：{{ issue.message }}
        </p>
      </details>
      <details>
        <summary>查看完整调整后的路书</summary>
        <TripRead :trip="task.result" />
      </details>
      <button
        class="button"
        :disabled="!editable || props.busy || !canApply"
        @click="a.apply"
      >
        {{ applying ? "保存中…" : "整份确认保存" }}
      </button>
    </section>
  </section>
</template>
