<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, nextTick } from "vue";
import {
  useRoute,
  useRouter,
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
} from "vue-router";
import { api, money } from "./api";
import { useAction, notify } from "./ui";
import { useSession } from "./session";
import type { Trip, Item, Poi, Revision, Share } from "./types";
import TripRead from "./TripRead.vue";
import TravelTools from "./TravelTools.vue";
import GeoCanvas from "./workbench/GeoCanvas.vue";
import ItineraryAnalysis from "./ItineraryAnalysis.vue";
import Members from "./Members.vue";
import HistoryDialog from "./workbench/HistoryDialog.vue";
import ShareDialog from "./workbench/ShareDialog.vue";
import PoiDialog from "./workbench/PoiDialog.vue";
import { useAiTask } from "./useAiTask";
import DayNavigation from "./workbench/DayNavigation.vue";
import PlanEditor from "./workbench/PlanEditor.vue";
import AiProposal from "./workbench/AiProposal.vue";
import { useTripDraft } from "./useTripDraft";
import { usePoiSearch } from "./usePoiSearch";
import { capabilityEnabled, capabilityNotice } from "./capabilities";
import WorkbenchShell from "./workbench/WorkbenchShell.vue";
import QualityInsight from "./workbench/QualityInsight.vue";
import { useWorkbenchQuality } from "./workbench/useWorkbenchQuality";
import { lockEditorScroll } from "./workbench/scrollLock";
import HomeIcon from "./HomeIcon.vue";
import "./workbench-v3.css";
const toolLabels: Record<string, string> = {
  map: "路线与天气",
  analysis: "行程诊断",
  tools: "记账与行李",
  members: "同行成员",
  read: "路书预览",
};
const selectedNode = ref("");
const timelineExpanded = ref(true);
const insightOpen = ref(false);
const nodeKeys = new WeakMap<Item, string>();
let nextNodeKey = 0;
function nodeKey(item: Item): string {
  if (item.id != null) return "item-" + item.id;
  if (!nodeKeys.has(item)) nodeKeys.set(item, "draft-" + ++nextNodeKey);
  return nodeKeys.get(item)!;
}
const modalDialog = ref<HTMLDialogElement>();
let modalTrigger: HTMLElement | undefined;
let releaseScroll: (() => void) | undefined;
function rememberModalTrigger() {
  modalTrigger =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : undefined;
}
function releaseModal() {
  modalDialog.value?.close();
  releaseScroll?.();
  releaseScroll = undefined;
  if (modalTrigger?.isConnected) modalTrigger.focus({ preventScroll: true });
  modalTrigger = undefined;
}
watch(
  modalDialog,
  (dialog) => {
    if (!dialog || !modal.value) return;
    if (!dialog.open) dialog.showModal();
    dialog.querySelector<HTMLButtonElement>("button")?.focus();
  },
  { flush: "post" },
);
function onModalBackdrop(event: MouseEvent) {
  const dialog = modalDialog.value;
  if (event.target !== dialog || !dialog) return;
  const rect = dialog.getBoundingClientRect();
  if (
    event.clientX < rect.left ||
    event.clientX > rect.right ||
    event.clientY < rect.top ||
    event.clientY > rect.bottom
  )
    modal.value = "";
}
const route = useRoute(),
  router = useRouter(),
  s = useSession(),
  dayIndex = ref(0),
  tab = ref("plan"),
  tools = ref<InstanceType<typeof TravelTools>>(),
  modal = ref(""),
  revisions = ref<Revision[]>([]),
  shares = ref<Share[]>([]),
  shareUrl = ref(""),
  search = ref(""),
  selected = ref<Item>();
const {
  trip,
  saved,
  role,
  dirty,
  loading,
  loadError,
  load,
  save: saveDraft,
  reset,
} = useTripDraft(() => String(route.params.tripId));
const {
  results: poi,
  busy: poiBusy,
  error: poiError,
  searched: poiSearched,
  search: searchPoi,
  reset: resetPoi,
} = usePoiSearch(
  () => ({
    tripId: trip.value?.id,
    version: trip.value?.version,
    destination: trip.value?.destination || "",
    item: selected.value,
    open: modal.value === "poi",
  }),
  () => search.value,
);
watch(
  modal,
  (value, before) => {
    if (value && !before) {
      if (!modalTrigger) rememberModalTrigger();
      releaseScroll = lockEditorScroll();
    } else if (!value) releaseModal();
  },
  { flush: "sync" },
);
onBeforeUnmount(releaseModal);
const { busy: actionBusy, error, run } = useAction();
const analysisBusy = ref(false);
const busy = computed(() => actionBusy.value || analysisBusy.value);
function analysisApplied(value: Trip) {
  saved.value = JSON.stringify(value);
  trip.value = value;
  notify("行程调整已保存");
}
const { prompt, task, preview, aiAdjust, requestAi, applyAi, resetTask } =
  useAiTask(trip, saved, dayIndex, dirty, modal, guardSaved, run);
const owner = computed(() => trip.value?.userId === s.profile?.id),
  editable = computed(() => owner.value || role.value === "EDITOR"),
  day = computed(() => trip.value?.days[dayIndex.value]);
watch(
  () => route.params.tripId,
  () => {
    resetTask();
    reset();
    dayIndex.value = 0;
    modal.value = "";
    // Queries must not be dropped by the mutation busy guard.
    void load().catch((e) => {
      if (!loading.value)
        notify(e instanceof Error ? e.message : "无法打开路书", "error");
    });
  },
  { immediate: true },
);
watch(
  () => trip.value?.days.length,
  (count) => {
    if (count) dayIndex.value = Math.min(dayIndex.value, count - 1);
  },
);
watch(
  trip,
  (value, before) => {
    if (!value || !before || value.id !== before.id) {
      selectedNode.value = "";
      return;
    }
    const previousDay = before.days.findIndex((d) =>
      d.items.some((i) => nodeKey(i) === selectedNode.value),
    );
    const previousIndex =
      previousDay < 0
        ? -1
        : before.days[previousDay]!.items.findIndex(
            (i) => nodeKey(i) === selectedNode.value,
          );
    if (previousIndex < 0) {
      selectedNode.value = "";
      return;
    }
    const oldItem = before.days[previousDay]!.items[previousIndex]!;
    const nextItem =
      oldItem.id != null
        ? value.days.flatMap((d) => d.items).find((i) => i.id === oldItem.id)
        : value.days[previousDay]?.items[previousIndex];
    selectedNode.value = nextItem ? nodeKey(nextItem) : "";
  },
  { flush: "sync" },
);
watch(
  dayIndex,
  () => {
    selectedNode.value = "";
  },
  { flush: "sync" },
);
watch(tab, async (value) => {
  if (value === "plan") return;
  await nextTick();
  document
    .querySelector<HTMLElement>(".wb-tool-panel")
    ?.focus({ preventScroll: true });
});
async function closeTool() {
  if (analysisBusy.value) return;
  tab.value = "plan";
  await nextTick();
  document.querySelector<HTMLElement>('[data-wb-tab="plan"]')?.focus();
}
const insight = useWorkbenchQuality(() => ({
  trip: trip.value,
  dirty: dirty.value,
  blocked: busy.value || loading.value,
}));
watch(insightOpen, async (open) => {
  if (!open) return;
  selectedNode.value = "";
  await nextTick();
  document
    .querySelector<HTMLButtonElement>("#workbench-insight .wb-insight-close")
    ?.focus();
});
function closeInsight() {
  insightOpen.value = false;
  nextTick(() =>
    document.querySelector<HTMLButtonElement>(".wb-insight-toggle")?.focus(),
  );
}
async function focusQuality(dayId: number, itemId?: number) {
  if (dirty.value || busy.value || loading.value || !trip.value) return;
  const index = trip.value.days.findIndex((d) => d.id === dayId);
  if (index < 0) return;
  insightOpen.value = false;
  dayIndex.value = index;
  tab.value = "plan";
  selectedNode.value = itemId ? "item-" + itemId : "";
  timelineExpanded.value = true;
  await nextTick();
  const target = itemId
    ? document.getElementById("plan-item-" + itemId)
    : document.querySelector(".editor-panel");
  target?.scrollIntoView({
    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches
      ? "instant"
      : "smooth",
    block: "nearest",
  });
  if (target instanceof HTMLElement) target.focus({ preventScroll: true });
}
function save() {
  return run(saveDraft, "路书已保存");
}
function leave() {
  if (!s.token) return true;
  if (busy.value) {
    notify("正在处理，请完成后再离开", "error");
    return false;
  }
  return !dirty.value || confirm("还有未保存的修改，确定离开？");
}
onBeforeRouteLeave(() => leave());
onBeforeRouteUpdate(() => leave());
function unload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
window.addEventListener("beforeunload", unload);
onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", unload);
});
function addDay() {
  if (!trip.value || trip.value.days.length >= 30) return;
  trip.value.days.push({ dayTheme: "新一天 · 自由探索", items: [] });
  dayIndex.value = trip.value.days.length - 1;
}
function removeDay() {
  if (!trip.value || trip.value.days.length <= 1) {
    notify("至少保留一天行程", "error");
    return;
  }
  if (!confirm("删除这一天及其节点？保存后生效。")) return;
  selectedNode.value = "";
  trip.value.days.splice(dayIndex.value, 1);
  dayIndex.value = Math.max(0, dayIndex.value - 1);
}
async function guardSaved() {
  if (dirty.value) {
    notify("请先保存当前修改", "error");
    return false;
  }
  return true;
}
function publish() {
  run(async () => {
    if (!(await guardSaved())) return;
    await api("/community/trips/" + trip.value!.id + "/publish", "POST", {});
    await load();
  }, "公开快照已更新");
}
function unpublish() {
  if (dirty.value) {
    notify("请先保存当前修改", "error");
    return;
  }
  if (!confirm("从旅行广场下架？已有分享链接仍独立有效。")) return;
  run(async () => {
    await api("/community/trips/" + trip.value!.id + "/publish", "DELETE");
    await load();
  }, "路书已下架");
}
function showShares() {
  rememberModalTrigger();
  run(async () => {
    if (!(await guardSaved())) return;
    shares.value = await api<Share[]>(
      "/community/trips/" + trip.value!.id + "/shares",
    );
    shareUrl.value = "";
    modal.value = "shares";
  });
}
function createShare() {
  run(async () => {
    const v = await api<{ path: string }>(
      "/community/trips/" + trip.value!.id + "/shares",
      "POST",
      {},
    );
    shareUrl.value = location.origin + v.path;
    shares.value = await api<Share[]>(
      "/community/trips/" + trip.value!.id + "/shares",
    );
  });
}
function revoke(id: number) {
  run(async () => {
    await api("/community/shares/" + id, "DELETE");
    shares.value = await api<Share[]>(
      "/community/trips/" + trip.value!.id + "/shares",
    );
    shareUrl.value = "";
  }, "分享已撤销");
}
function history() {
  rememberModalTrigger();
  run(async () => {
    revisions.value = await api<Revision[]>(
      "/workbench/" + trip.value!.id + "/revisions",
    );
    modal.value = "history";
  });
}
function restore(id: number) {
  if (!confirm("恢复这个版本？当前已保存版本也会保留在历史记录中。")) return;
  run(async () => {
    if (!(await guardSaved())) return;
    trip.value = await api<Trip>(
      "/workbench/" + trip.value!.id + "/revisions/" + id + "/restore",
      "POST",
      { version: trip.value!.version },
    );
    saved.value = JSON.stringify(trip.value);
    dayIndex.value = 0;
    modal.value = "";
  }, "已恢复历史版本");
}
async function removeTrip() {
  if (!confirm("删除整份路书？公开作品和分享链接也会失效。")) return;
  const removed = await run(async () => {
    await api("/workbench/" + trip.value!.id, "DELETE");
    saved.value = JSON.stringify(trip.value);
    return true;
  });
  if (removed) await router.push("/my-trips");
}
function selectPoi(item: Item) {
  rememberModalTrigger();
  selected.value = item;
  search.value = item.itemName;
  resetPoi();
  modal.value = "poi";
}
function findPoi() {
  if (!capabilityEnabled(s.capabilities, "poi")) return;
  void searchPoi();
}
function choose(p: Poi) {
  if (
    !selected.value ||
    modal.value !== "poi" ||
    !trip.value?.days.some((day) => day.items.includes(selected.value!))
  )
    return;
  selected.value.poiId = p.poiId;
  selected.value.itemName = p.name;
  selected.value.longitude = undefined;
  selected.value.latitude = undefined;
  selected.value.coordinateSystem = undefined;
  selected.value.address = undefined;
  modal.value = "";
  notify("地点已关联，保存后可以查询路线");
}
async function record(item: Item) {
  if (!item.id || dirty.value) {
    notify("请先保存节点再记账", "error");
    return;
  }
  tab.value = "tools";
  await nextTick();
  tools.value?.record(item.id);
}
function exportImage() {
  run(async () => {
    if (!(await guardSaved())) return;
    tab.value = "read";
    await nextTick();
    const element = document.querySelector(".trip-read") as HTMLElement;
    if (!element) return;
    const { default: html2canvas } = await import("html2canvas");
    const canvas = await html2canvas(element, {
      backgroundColor: "#f7f7f0",
      scale: 1.5,
    });
    const link = document.createElement("a");
    link.download = "旅行路书.png";
    link.href = canvas.toDataURL("image/png");
    link.click();
  });
}
function print() {
  if (dirty.value) {
    notify("先保存路书后再导出", "error");
    return;
  }
  tab.value = "read";
  nextTick(() => window.print());
}
</script>
<template>
  <div v-if="loading && !trip" class="wb-state" role="status">
    正在打开你的路书…
  </div>
  <div v-if="(loadError || error) && !trip" class="wb-state">
    <p role="alert">{{ loadError || error }}</p>
    <RouterLink to="/my-trips" class="button secondary"
      >返回我的路书</RouterLink
    >
  </div>
  <WorkbenchShell
    v-if="trip && day"
    :destination="trip.destination"
    :expanded="timelineExpanded"
    :tool-open="tab !== 'plan' && tab !== 'map'"
    :insight-open="insightOpen"
    :inspector-open="Boolean(selectedNode) && !insightOpen && tab === 'plan'"
    :geo-open="tab === 'map'"
  >
    <template #header>
      <header class="wb-tripbar">
        <div class="wb-trip-heading">
          <RouterLink to="/my-trips" class="wb-back" aria-label="返回我的路书"
            ><HomeIcon name="arrow"
          /></RouterLink>
          <div>
            <input
              v-model="trip.title"
              :readonly="!editable || busy || loading"
              maxlength="100"
              aria-label="路书标题"
            />
            <p>
              {{ trip.destination }} · {{ trip.days.length }} 天 · 预估
              {{
                money(
                  trip.days
                    .flatMap((d) => d.items)
                    .reduce((sum, i) => sum + Number(i.costEstimate || 0), 0),
                )
              }}<span
                >版本 {{ trip.version }} ·
                {{ dirty ? "有未保存修改" : "修改已保存" }}</span
              >
            </p>
          </div>
          <span class="wb-visibility">{{
            trip.isPublic ? "已发布快照" : "私密路书"
          }}</span>
        </div>
        <div class="wb-toolbar">
          <button
            v-if="editable"
            class="button"
            :disabled="busy || !dirty"
            @click="save"
          >
            {{ busy ? "保存中…" : "保存路书" }}</button
          ><button
            v-if="editable"
            class="button secondary"
            :disabled="busy || dirty || !s.capabilities.ai"
            @click="
              rememberModalTrigger();
              aiAdjust();
            "
          >
            AI 调整预览</button
          ><button
            class="button secondary"
            :disabled="busy || dirty"
            @click="tab = 'analysis'"
          >
            行程诊断</button
          ><button class="button secondary" :disabled="busy" @click="history">
            历史版本
          </button>
          <details class="wb-more-actions">
            <summary>更多操作</summary>
            <div class="wb-more-content">
              <button
                v-if="owner"
                class="button secondary"
                :disabled="busy || dirty"
                @click="publish"
              >
                {{ trip.isPublic ? "更新公开快照" : "发布到广场" }}</button
              ><button
                v-if="owner && trip.isPublic"
                class="text-button"
                :disabled="busy"
                @click="unpublish"
              >
                下架</button
              ><button
                v-if="owner"
                class="button secondary"
                :disabled="busy || dirty"
                @click="showShares"
              >
                分享</button
              ><button
                class="text-button"
                :disabled="busy"
                @click="exportImage"
              >
                导出图片</button
              ><button class="text-button" @click="print">
                打印 / 导出 PDF
              </button>
              <button
                v-if="owner"
                class="text-button wb-danger"
                :disabled="busy"
                @click="removeTrip"
              >
                删除这份路书
              </button>
            </div>
          </details>
        </div>
      </header>
      <p v-if="error" class="form-error" role="alert">
        {{ error }}
        <button
          v-if="error.includes('刷新')"
          class="text-button"
          @click="leave() && run(load)"
        >
          重新加载
        </button>
      </p>
      <div v-if="!editable" class="notice">你以只读成员身份查看这份路书。</div>
      <nav class="wb-tabs" aria-label="工作台工具">
        <button
          class="wb-insight-toggle"
          :aria-expanded="insightOpen"
          aria-controls="workbench-insight"
          @click="
            tab = 'plan';
            insightOpen = !insightOpen;
          "
        >
          行程洞察
        </button>
        <button
          v-for="t in [
            { id: 'plan', label: '行程编辑' },
            { id: 'map', label: '路线与天气' },
            { id: 'analysis', label: '行程诊断' },
            { id: 'tools', label: '记账与行李' },
            { id: 'members', label: '同行成员' },
            { id: 'read', label: '路书预览' },
          ]"
          :key="t.id"
          :data-wb-tab="t.id"
          :class="{ active: tab === t.id }"
          :disabled="analysisBusy"
          @click="
            tab = t.id;
            insightOpen = false;
          "
        >
          {{ t.label }}
        </button>
      </nav>
    </template>
    <template #canvas
      ><GeoCanvas
        :trip="trip"
        :day="day"
        :dirty="dirty"
        :active="tab === 'plan' || tab === 'map'"
        :controls-open="tab === 'map'"
        :selected-node="selectedNode"
        :node-key="nodeKey"
        @close="closeTool"
        @adjust="tab = 'analysis'"
        @select="
          (item) => {
            selectedNode = nodeKey(item);
            tab = 'plan';
            insightOpen = false;
            timelineExpanded = true;
          }
        "
    /></template>
    <template #rail
      ><DayNavigation
        v-model="dayIndex"
        :trip="trip"
        :editable="editable"
        :busy="busy || loading"
        @add-day="addDay"
    /></template>
    <template #timeline
      ><PlanEditor
        v-model:selected-node="selectedNode"
        @update:selected-node="
          (value) => {
            if (value && tab === 'map') tab = 'plan';
          }
        "
        v-model:expanded="timelineExpanded"
        :trip="trip"
        :day-index="dayIndex"
        :editable="editable"
        :busy="busy || loading"
        :dirty="dirty"
        :geo="capabilityEnabled(s.capabilities, 'poi')"
        :node-key="nodeKey"
        :active="tab === 'plan' && !insightOpen"
        :error="error"
        @save="save"
        @remove-day="removeDay"
        @poi="selectPoi"
        @record="record"
    /></template>
    <template #context
      ><QualityInsight
        :trip="trip"
        :report="insight.report.value"
        :valid="insight.valid.value"
        :state="insight.state.value"
        :busy="insight.busy.value"
        :disabled="busy || loading || dirty"
        :error="insight.error.value"
        @analyze="insight.analyze"
        @focus="focusQuality"
        @close="closeInsight"
      />
      <Teleport
        defer
        :to="
          tab === 'analysis'
            ? '#workbench-analysis-host'
            : '#workbench-adjustment-host'
        "
      >
        <ItineraryAnalysis
          :insight="tab !== 'analysis'"
          :trip="trip"
          :day-index="dayIndex"
          :dirty="dirty"
          :editable="editable"
          :busy="actionBusy"
          :capabilities="s.capabilities"
          @report="insight.accept"
          @applied="analysisApplied"
          @focus="focusQuality"
          @busy="analysisBusy = $event"
        />
      </Teleport>
    </template>
    <template #tools
      ><section
        v-show="tab !== 'plan' && tab !== 'map'"
        class="wb-tool-panel"
        tabindex="-1"
        aria-label="工作台工具面板"
        @keydown.esc.stop="closeTool"
      >
        <header class="wb-tool-heading">
          <h2>{{ toolLabels[tab] }}</h2>
          <button
            type="button"
            class="wb-text-button"
            :disabled="analysisBusy"
            @click="closeTool"
          >
            返回行程编辑
          </button>
        </header>
        <div id="workbench-analysis-host"></div>
        <TravelTools
          v-if="tab === 'tools'"
          ref="tools"
          :trip="trip"
          :readonly="!editable || busy || loading"
        /><Members
          v-if="tab === 'members'"
          :trip-id="trip.id!"
          :owner="owner"
        /><TripRead v-if="tab === 'read'" :trip="trip" /></section
    ></template>
  </WorkbenchShell>
  <Teleport to="body"
    ><dialog
      v-if="modal && trip"
      ref="modalDialog"
      class="modal wb-modal"
      aria-labelledby="editor-modal-title"
      @cancel.prevent="modal = ''"
      @click="onModalBackdrop"
    >
      <header class="row">
        <h2 id="editor-modal-title">
          {{
            modal === "history"
              ? "历史版本"
              : modal === "shares"
                ? "分享旅行快照"
                : modal === "poi"
                  ? "选择真实地点"
                  : "AI 调整预览"
          }}
        </h2>
        <button class="text-button" @click="modal = ''">关闭</button>
      </header>
      <HistoryDialog
        v-if="modal === 'history'"
        :revisions="revisions"
        :editable="editable"
        :busy="busy"
        :dirty="dirty"
        @restore="restore"
      /><ShareDialog
        v-else-if="modal === 'shares'"
        :shares="shares"
        :share-url="shareUrl"
        :busy="busy"
        @create="createShare"
        @revoke="revoke"
      /><PoiDialog
        v-else-if="modal === 'poi'"
        v-model="search"
        :poi="poi"
        :destination="trip.destination"
        :busy="poiBusy"
        :error="poiError"
        :searched="poiSearched"
        :unavailable="capabilityNotice(s.capabilities, 'poi')"
        @search="findPoi"
        @choose="choose"
      /><AiProposal
        v-else
        v-model="prompt"
        :current="trip"
        :preview="preview"
        :task="task"
        :busy="busy"
        :dirty="dirty"
        @generate="requestAi"
        @apply="applyAi"
        @close="modal = ''"
      />
      <p v-if="error" class="form-error">{{ error }}</p>
    </dialog></Teleport
  >
</template>
