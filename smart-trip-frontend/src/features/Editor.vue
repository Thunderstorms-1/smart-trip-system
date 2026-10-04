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
import MapWeather from "./MapWeather.vue";
import Members from "./Members.vue";
import HistoryDialog from "./workbench/HistoryDialog.vue";
import ShareDialog from "./workbench/ShareDialog.vue";
import PoiDialog from "./workbench/PoiDialog.vue";
import { useAiTask } from "./useAiTask";
import DayNavigation from "./workbench/DayNavigation.vue";
import PlanEditor from "./workbench/PlanEditor.vue";
import AiProposal from "./workbench/AiProposal.vue";
import { useTripDraft } from "./useTripDraft";
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
  poi = ref<Poi[]>([]),
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
const { busy, error, run } = useAction();
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
function removeTrip() {
  if (!confirm("删除整份路书？公开作品和分享链接也会失效。")) return;
  run(async () => {
    await api("/workbench/" + trip.value!.id, "DELETE");
    saved.value = JSON.stringify(trip.value);
    router.push("/my-trips");
  });
}
function selectPoi(item: Item) {
  selected.value = item;
  search.value = item.itemName;
  poi.value = [];
  modal.value = "poi";
}
function findPoi() {
  run(async () => {
    poi.value = await api<Poi[]>(
      "/geo/search?city=" +
        encodeURIComponent(trip.value!.destination) +
        "&keyword=" +
        encodeURIComponent(search.value),
    );
  });
}
function choose(p: Poi) {
  if (!selected.value) return;
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
  <p v-if="loading && !trip" class="empty">正在打开你的路书…</p>
  <div v-if="(loadError || error) && !trip" class="empty paper">
    <p>{{ loadError || error }}</p>
    <RouterLink to="/my-trips" class="button secondary"
      >返回我的路书</RouterLink
    >
  </div>
  <template v-if="trip && day"
    ><header class="editor-heading">
      <div class="meta-row">
        <RouterLink to="/my-trips">← 我的路书</RouterLink
        ><span class="pill">{{
          trip.isPublic ? "已发布快照" : "私密路书"
        }}</span
        ><span
          >版本 {{ trip.version }} ·
          {{ dirty ? "有未保存修改" : "修改已保存" }}</span
        >
      </div>
      <input
        v-model="trip.title"
        :readonly="!editable || busy || loading"
        maxlength="100"
        aria-label="路书标题"
      />
      <div class="meta-row">
        <span>{{ trip.destination }} · {{ trip.days.length }} 天</span
        ><span
          >预估
          {{
            money(
              trip.days
                .flatMap((d) => d.items)
                .reduce((sum, i) => sum + Number(i.costEstimate || 0), 0),
            )
          }}</span
        >
      </div>
    </header>
    <div class="editor-toolbar">
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
        @click="aiAdjust()"
      >
        AI 调整预览</button
      ><button class="button secondary" :disabled="busy" @click="history">
        历史版本</button
      ><button
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
      ><button class="text-button" :disabled="busy" @click="exportImage">
        导出图片</button
      ><button class="text-button" @click="print">打印 / 导出 PDF</button>
    </div>
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
    <div class="tabs">
      <button
        v-for="t in [
          { id: 'plan', label: '行程编辑' },
          { id: 'map', label: '路线与天气' },
          { id: 'tools', label: '记账与行李' },
          { id: 'members', label: '同行成员' },
          { id: 'read', label: '路书预览' },
        ]"
        :key="t.id"
        :class="{ active: tab === t.id }"
        @click="tab = t.id"
      >
        {{ t.label }}
      </button>
    </div>
    <div v-if="tab === 'plan' || tab === 'map'" class="editor-layout">
      <DayNavigation
        v-model="dayIndex"
        :trip="trip"
        :editable="editable"
        :busy="busy || loading"
        @add-day="addDay"
      />

      <PlanEditor
        v-if="tab === 'plan'"
        :trip="trip"
        :day-index="dayIndex"
        :editable="editable"
        :busy="busy || loading"
        :dirty="dirty"
        :geo="s.capabilities.geo"
        @remove-day="removeDay"
        @poi="selectPoi"
        @record="record"
      />

      <MapWeather
        v-else
        :trip="trip"
        :day="day"
        :dirty="dirty"
        @adjust="aiAdjust"
      />
    </div>
    <TravelTools
      v-if="tab === 'tools'"
      ref="tools"
      :trip="trip"
      :readonly="!editable || busy || loading"
    /><Members
      v-if="tab === 'members'"
      :trip-id="trip.id!"
      :owner="owner"
    /><TripRead v-if="tab === 'read'" :trip="trip" />
    <div v-if="owner" class="no-print" style="margin-top: 35px">
      <button class="text-button" :disabled="busy" @click="removeTrip">
        删除这份路书
      </button>
    </div>
    <Teleport to="body"
      ><div
        v-if="modal"
        class="modal-backdrop"
        @click.self="modal = ''"
        @keydown.esc="modal = ''"
      >
        <div
          class="modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="editor-modal-title"
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
            :busy="busy"
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
        </div>
      </div></Teleport
    ></template
  >
</template>
