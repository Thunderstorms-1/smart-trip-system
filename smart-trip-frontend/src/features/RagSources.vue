<script setup lang="ts">
import { computed } from "vue";
import type { Task, RagSource } from "./types";
const props = defineProps<{ task?: Task }>();
const rag = computed(() => props.task?.generation?.rag);
const sources = computed(() => rag.value?.sources ?? []);
const candidates = computed(() => rag.value?.candidates ?? []);
const modelCompleted = computed(
  () =>
    props.task?.generation?.phaseHistory?.some(
      (e) => e.phase === "MODEL_COMPLETED",
    ) ?? false,
);
const status = computed(() => {
  if (!rag.value) return "";
  if (rag.value.status === "SUCCESS") return "旅行资料检索完成";
  if (sources.value.length) return "已找到参考资料，部分检索能力降级";
  return props.task?.status === "FAILED"
    ? "旅行资料检索未完成，请查看任务原因"
    : "未找到可靠参考资料，沿原流程继续规划";
});
function url(source: RagSource): string | undefined {
  try {
    const value = new URL(source.sourceUrl ?? "");
    return ["http:", "https:"].includes(value.protocol) &&
      !value.username &&
      !value.password
      ? value.href
      : undefined;
  } catch {
    return undefined;
  }
}
</script>

<template>
  <details v-if="rag && rag.status !== 'DISABLED'" class="rag-sources">
    <summary>
      <strong>旅行参考资料</strong><span>{{ sources.length }} 条参考片段</span>
    </summary>
    <p role="status">{{ status }}</p>
    <p v-if="sources.length">
      {{
        modelCompleted
          ? "本次已传给生成模型的参考片段"
          : "已准备的参考片段，模型调用是否完成待确认"
      }}。检索资料不代表票价、开放时间、天气或交通已经实时核实。
    </p>
    <ul v-if="sources.length">
      <li v-for="source in sources" :key="source.chunkId">
        <span class="rag-source-ref">{{
          source.ref ? "[" + source.ref + "]" : "参考"
        }}</span>
        <a
          v-if="url(source)"
          :href="url(source)"
          target="_blank"
          rel="noopener noreferrer"
          >{{ source.title || source.sourceName }}</a
        >
        <strong v-else>{{ source.title || source.sourceName }}</strong>
        <small
          >{{ source.sourceName }} ·
          {{ source.sourceUpdatedAt || "更新时间未注明"
          }}{{
            source.freshness === "OLDER_STATIC_REFERENCE"
              ? " · 历史背景资料"
              : ""
          }}</small
        >
        <small v-if="source.validFrom && source.validUntil">
          资料适用期：{{ source.validFrom }} 至 {{ source.validUntil }}
        </small>
        <small v-if="source.applicability === 'PARTIALLY_APPLICABLE'">
          {{ source.applicabilityNote || ('仅适用于 ' + source.applicableFrom + ' 至 ' + source.applicableUntil + '，行程其余日期不适用。') }}
        </small>
      </li>
    </ul>
    <details v-if="candidates.length" class="rag-candidates">
      <summary>
        检索候选（{{ candidates.length }} 条，不代表全部用于生成）
      </summary>
      <ul>
        <li v-for="source in candidates" :key="source.chunkId">
          <span>{{ source.title || source.sourceName }}</span
          ><small
            >{{ source.sourceName }} ·
            {{ source.sourceUpdatedAt || "更新时间未注明" }}</small
          >
        </li>
      </ul>
    </details>
  </details>
</template>

<style scoped>
.rag-sources {
  padding: 14px 16px;
  margin-top: 16px;
  border: 1px solid #a9c5df70;
  border-radius: 14px;
  background: #f5faffb8;
  color: #25465f;
  font:
    14px/1.7 -apple-system,
    BlinkMacSystemFont,
    "Segoe UI",
    "Microsoft YaHei",
    sans-serif;
}
summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  cursor: pointer;
}
summary span,
small {
  color: #526c81;
  font-size: 12px;
}
p {
  margin: 10px 0 0;
}
ul {
  margin: 10px 0 0;
  padding-left: 20px;
}
li {
  margin: 7px 0;
  overflow-wrap: anywhere;
}
small {
  display: block;
}
a {
  color: #225e9b;
  text-decoration: underline;
  text-underline-offset: 3px;
}
a:focus-visible,
summary:focus-visible {
  outline: 2px solid #3e7eaf;
  outline-offset: 3px;
}
.rag-source-ref {
  margin-right: 6px;
  font-size: 12px;
}
.rag-candidates {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #a9c5df50;
}
.rag-candidates summary {
  font-size: 13px;
}
</style>
