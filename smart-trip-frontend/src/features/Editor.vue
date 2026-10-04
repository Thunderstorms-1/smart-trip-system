<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, nextTick } from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { api, money, requestKey } from "./api";
import { followTask } from "./task-stream";
import { useAction, notify } from "./ui";
import { useSession } from "./session";
import type { Trip, Item, Poi, Revision, Share, Task, Weather } from "./types";
import TripRead from "./TripRead.vue";
import TravelTools from "./TravelTools.vue";
import MapWeather from "./MapWeather.vue";
import Members from "./Members.vue";
import QrcodeVue from "qrcode.vue";
const route = useRoute(),
  router = useRouter(),
  s = useSession(),
  trip = ref<Trip>(),
  saved = ref(""),
  dayIndex = ref(0),
  tab = ref("plan"),
  tools = ref<InstanceType<typeof TravelTools>>(),
  modal = ref(""),
  revisions = ref<Revision[]>([]),
  shares = ref<Share[]>([]),
  shareUrl = ref(""),
  poi = ref<Poi[]>([]),
  search = ref(""),
  selected = ref<Item>(),
  prompt = ref(""),
  task = ref<Task>(),
  preview = ref<Trip>(),
  role = ref("VIEWER");
const { busy, error, run } = useAction();
let timer: ReturnType<typeof setTimeout>;
const stream = new AbortController();
const dirty = computed(() =>
    Boolean(trip.value && JSON.stringify(trip.value) !== saved.value),
  ),
  owner = computed(() => trip.value?.userId === s.profile?.id),
  editable = computed(() => owner.value || role.value === "EDITOR"),
  day = computed(() => trip.value?.days[dayIndex.value]);
async function load() {
  trip.value = await api<Trip>("/workbench/" + route.params.tripId);
  saved.value = JSON.stringify(trip.value);
  dayIndex.value = Math.min(dayIndex.value, trip.value.days.length - 1);
  const members = await api<{ userId: number; role: string }[]>(
    "/membership/trips/" + route.params.tripId,
  );
  role.value =
    members.find((m) => m.userId === s.profile?.id)?.role || "VIEWER";
}
watch(
  () => route.params.tripId,
  () =>
    run(async () => {
      await s.refresh();
      await load();
    }),
  { immediate: true },
);
function save() {
  return run(async () => {
    if (!trip.value) return;
    trip.value = await api<Trip>(
      "/workbench/" + trip.value.id,
      "PUT",
      trip.value,
    );
    saved.value = JSON.stringify(trip.value);
  }, "路书已保存");
}
function leave() {
  return !dirty.value || confirm("还有未保存的修改，确定离开？");
}
onBeforeRouteLeave(() => leave());
function unload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
window.addEventListener("beforeunload", unload);
onBeforeUnmount(() => {
  clearTimeout(timer);
  stream.abort();
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
function addItem() {
  day.value?.items.push({
    itemType: "SCENIC",
    itemName: "",
    startTime: "09:00",
    costTimeHours: 2,
    costEstimate: 0,
    aiTips: "",
  });
}
function move(index: number, delta: number) {
  if (!day.value) return;
  const target = index + delta;
  if (target < 0 || target >= day.value.items.length) return;
  const [item] = day.value.items.splice(index, 1);
  day.value.items.splice(target, 0, item!);
}
function transfer(index: number, e: Event) {
  if (!trip.value || !day.value) return;
  const target = Number((e.target as HTMLSelectElement).value);
  if (target === dayIndex.value) return;
  const [item] = day.value.items.splice(index, 1);
  trip.value.days[target]?.items.push(item!);
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
function aiAdjust(weather?: Weather) {
  if (dirty.value) {
    notify("请先保存路书再发起AI调整", "error");
    return;
  }
  const pending = localStorage.getItem("SMARTTRIP_PROPOSAL_" + trip.value?.id);
  if (pending && !weather) {
    modal.value = "ai";
    poll(pending);
    return;
  }
  prompt.value = weather
    ? "依据以下天气预报调整第" +
      (dayIndex.value + 1) +
      "天，保留其他天数和已有节点ID：" +
      JSON.stringify(weather)
    : "";
  task.value = undefined;
  preview.value = undefined;
  modal.value = "ai";
}
async function poll(id: string) {
  try {
    task.value = await api<Task>("/ai/tasks/" + id);
    if (task.value.status === "PREVIEW") {
      preview.value = task.value.result;
    } else if (["QUEUED", "RUNNING"].includes(task.value.status))
      timer = setTimeout(() => poll(id), 1500);
  } catch (e) {
    notify(e instanceof Error ? e.message : "无法查询任务", "error");
  }
}
function requestAi() {
  run(async () => {
    if (!(await guardSaved())) return;
    task.value = await api<Task>("/ai/tasks", "POST", {
      requestKey: requestKey(),
      type: "MODIFY",
      tripId: trip.value!.id,
      prompt: prompt.value,
    });
    const id = task.value.id;
    localStorage.setItem("SMARTTRIP_PROPOSAL_" + trip.value!.id, id);
    followTask(
      id,
      (t) => {
        task.value = t;
        if (t.status === "PREVIEW") preview.value = t.result;
      },
      stream.signal,
    ).catch(() => {
      if (!stream.signal.aborted) poll(id);
    });
  });
}
function applyAi() {
  run(async () => {
    trip.value = await api<Trip>(
      "/ai/tasks/" + task.value!.id + "/apply",
      "POST",
      {},
    );
    saved.value = JSON.stringify(trip.value);
    dayIndex.value = 0;
    modal.value = "";
    localStorage.removeItem("SMARTTRIP_PROPOSAL_" + trip.value!.id);
  }, "AI修改已确认保存");
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
  <p v-if="busy && !trip" class="empty">正在打开你的路书…</p>
  <div v-if="error && !trip" class="empty paper">
    <p>{{ error }}</p>
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
        :readonly="!editable"
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
      <aside class="paper editor-sidebar">
        <button
          v-for="(d, n) in trip.days"
          :key="d.id || n"
          class="day-nav"
          :class="{ active: dayIndex === n }"
          @click="dayIndex = n"
        >
          <strong>{{ String(n + 1).padStart(2, "0") }}</strong
          ><span>{{ d.dayTheme || "自由探索" }}</span></button
        ><button
          v-if="editable"
          class="button secondary small full"
          :disabled="trip.days.length >= 30"
          @click="addDay"
        >
          ＋ 添加一天
        </button>
      </aside>
      <div v-if="tab === 'plan'" class="panel-stack">
        <section class="paper editor-panel">
          <div class="form-grid">
            <label
              >目的地<input
                v-model="trip.destination"
                :readonly="!editable"
                maxlength="100"
                required /></label
            ><label
              >出发日期<input
                v-model="trip.startDate"
                :readonly="!editable"
                type="date" /></label
            ><label class="wide"
              >行程简介<textarea
                v-model="trip.summary"
                :readonly="!editable"
                maxlength="500"
                rows="2"
              ></textarea>
            </label>
          </div>
          <div class="row">
            <h2>第 {{ dayIndex + 1 }} 天</h2>
            <button v-if="editable" class="text-button" @click="removeDay">
              删除这一天
            </button>
          </div>
          <label
            >当日主题<input
              v-model="day.dayTheme"
              :readonly="!editable"
              maxlength="100"
          /></label>
          <article
            v-for="(item, n) in day.items"
            :key="item.id || 'new-' + n"
            class="item-editor"
          >
            <div class="row">
              <h3>{{ n + 1 }} / {{ item.itemName || "新地点" }}</h3>
              <div v-if="editable" class="mini-actions">
                <button
                  :disabled="n === 0"
                  @click="move(n, -1)"
                  aria-label="向上移动节点"
                >
                  ↑</button
                ><button
                  :disabled="n === day.items.length - 1"
                  @click="move(n, 1)"
                  aria-label="向下移动节点"
                >
                  ↓</button
                ><button @click="day.items.splice(n, 1)">删除</button>
              </div>
            </div>
            <div class="form-grid">
              <label class="wide"
                >地点 / 活动<input
                  v-model="item.itemName"
                  @input="
                    item.poiId = undefined;
                    item.latitude = undefined;
                    item.longitude = undefined;
                    item.coordinateSystem = undefined;
                  "
                  :readonly="!editable"
                  maxlength="100"
                  required /></label
              ><label
                >类型<select v-model="item.itemType" :disabled="!editable">
                  <option value="SCENIC">景点</option>
                  <option value="FOOD">美食</option>
                  <option value="HOTEL">住宿</option>
                  <option value="TRAFFIC">交通</option>
                  <option value="OTHER">活动</option>
                </select></label
              ><label
                >开始时间<input
                  v-model="item.startTime"
                  :readonly="!editable"
                  type="time" /></label
              ><label
                >停留时长（小时）<input
                  v-model.number="item.costTimeHours"
                  :readonly="!editable"
                  type="number"
                  min="0"
                  max="24"
                  step="0.5" /></label
              ><label
                >预估花费（元）<input
                  v-model.number="item.costEstimate"
                  :readonly="!editable"
                  type="number"
                  min="0"
                  max="9999999"
                  step="0.01" /></label
              ><label class="wide"
                >旅行备注<textarea
                  v-model="item.aiTips"
                  :readonly="!editable"
                  maxlength="5000"
                  rows="2"
                ></textarea></label
              ><label class="wide"
                >交通提示<input
                  v-model="item.transportTips"
                  :readonly="!editable"
                  maxlength="5000"
              /></label>
            </div>
            <div v-if="editable" class="button-row">
              <button
                class="button secondary small"
                :disabled="!s.capabilities.geo"
                @click="selectPoi(item)"
              >
                {{ item.poiId ? "重新选择地点" : "关联真实地点" }}</button
              ><button
                class="text-button"
                :disabled="dirty || !item.id"
                @click="record(item)"
              >
                记录这笔花费</button
              ><select
                :value="dayIndex"
                style="width: auto; font-size: 12px; margin: 0"
                aria-label="移动节点到另一天"
                @change="transfer(n, $event)"
              >
                <option v-for="(_, idx) in trip.days" :key="idx" :value="idx">
                  移动到第 {{ idx + 1 }} 天
                </option>
              </select>
            </div>
            <p v-if="item.poiId" class="muted">
              已关联地点 ID：{{ item.poiId }}
            </p>
          </article>
          <div v-if="!day.items.length" class="empty">
            这一天还没有安排，添加第一个地点吧。
          </div>
          <button
            v-if="editable"
            class="button secondary full"
            style="margin-top: 20px"
            @click="addItem"
          >
            ＋ 添加行程节点
          </button>
        </section>
        <div v-if="!s.capabilities.geo" class="notice">
          地点搜索暂未启用。你仍可先写下地点名称，之后再关联真实位置。
        </div>
      </div>
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
      :readonly="!editable"
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
          <template v-if="modal === 'history'"
            ><p class="muted">
              恢复时会保留当前已保存版本，历史记录展示最近50次修改。
            </p>
            <div v-for="r in revisions" :key="r.id" class="packing-row">
              <span>版本 {{ r.version }} · {{ r.create_time }}</span
              ><button
                v-if="editable"
                class="text-button"
                :disabled="busy || dirty"
                @click="restore(r.id)"
              >
                恢复
              </button>
            </div>
            <p v-if="!revisions.length" class="empty">
              还没有历史记录。
            </p></template
          ><template v-else-if="modal === 'shares'"
            ><p>分享的是此刻的路书快照。之后的私密修改不会同步到链接。</p>
            <button class="button" :disabled="busy" @click="createShare">
              创建分享链接</button
            ><template v-if="shareUrl"
              ><a
                class="share-url"
                :href="shareUrl"
                target="_blank"
                rel="noopener"
                >{{ shareUrl }}</a
              ><QrcodeVue :value="shareUrl" :size="140" level="M"
            /></template>
            <div v-for="i in shares" :key="i.id" class="packing-row">
              <small>分享 #{{ i.id }} · {{ i.createdAt }}</small
              ><button
                v-if="!i.revoked"
                class="text-button"
                :disabled="busy"
                @click="revoke(i.id)"
              >
                撤销</button
              ><small v-else>已撤销</small>
            </div></template
          ><template v-else-if="modal === 'poi'"
            ><form class="search-bar" @submit.prevent="findPoi">
              <input
                v-model="search"
                required
                maxlength="100"
                aria-label="地点名称"
                :placeholder="trip.destination + '的地点'"
              /><button class="button" :disabled="busy">搜索</button>
            </form>
            <p class="muted">请核对城市与地点名称，选择后才会关联到节点。</p>
            <div class="poi-list">
              <button
                v-for="p in poi"
                :key="p.poiId"
                class="poi-option"
                @click="choose(p)"
              >
                <strong>{{ p.name }}</strong
                ><small>{{ p.address }} · 高德</small>
              </button>
            </div>
            <p v-if="!poi.length" class="empty">
              输入名称搜索候选地点。
            </p></template
          ><template v-else
            ><label
              >你想怎么调整？<textarea
                v-model="prompt"
                maxlength="3000"
                rows="4"
                placeholder="例如第二天减少步行，增加一个室内景点"
              ></textarea></label
            ><button
              class="button"
              :disabled="
                busy ||
                Boolean(task && ['QUEUED', 'RUNNING'].includes(task.status))
              "
              @click="requestAi"
            >
              生成修改预览
            </button>
            <p
              v-if="task && ['QUEUED', 'RUNNING'].includes(task.status)"
              class="notice"
            >
              <span class="status-pulse"></span>正在生成预览，原路书尚未改变。
            </p>
            <p v-if="task?.errorMessage" class="form-error">
              {{ task.errorMessage }}
            </p>
            <template v-if="preview"
              ><div class="preview-columns">
                <div>
                  <h3>当前安排</h3>
                  <template v-for="(d, i) in trip.days" :key="i"
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
                <button class="button" :disabled="busy" @click="applyAi">
                  确认保存修改</button
                ><button class="button secondary" @click="modal = ''">
                  暂不采用
                </button>
              </div></template
            ></template
          >
          <p v-if="error" class="form-error">{{ error }}</p>
        </div>
      </div></Teleport
    ></template
  >
</template>
