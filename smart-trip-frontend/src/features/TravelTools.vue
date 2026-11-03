<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick, onBeforeUnmount } from "vue";
import { api, money, requestKey } from "./api";
import { useAction } from "./ui";
import type { Expense, ExpenseSummary, Packing, Trip } from "./types";
import { lockEditorScroll } from "./workbench/scrollLock";
import { useWorkbenchMotion } from "./workbench/useWorkbenchMotion";
const props = defineProps<{
    trip: Trip;
    readonly?: boolean;
    workspace?: boolean;
  }>(),
  summary = ref<ExpenseSummary>({ items: [], actual: 0, estimated: 0 }),
  packing = ref<Packing[]>([]),
  newItem = ref(""),
  editing = ref<number>(),
  modal = ref(false),
  source = ref<number>(),
  key = ref(requestKey());
const form = reactive<Omit<Expense, "amount"> & { amount: number | "" }>({
  category: "OTHER",
  expenseName: "",
  amount: 0,
  expenseDate: new Date().toLocaleDateString("en-CA"),
  remark: "",
});
const { busy, error, run } = useAction();
const expensePanel = ref<HTMLFormElement>();
const loaded = ref(false),
  loading = ref(false),
  loadError = ref("");
const categoryFilter = ref("ALL"),
  packingFilter = ref("ALL");
let readEpoch = 0;
let readRequest: AbortController | undefined;
let summaryRefreshPending = false;
const locked = computed(() =>
  Boolean(props.readonly || busy.value || loading.value || !loaded.value),
);
const motion = useWorkbenchMotion();
let expenseTrigger: HTMLElement | undefined;
let releaseScroll: (() => void) | undefined;
let modalEpoch = 0;
let disposed = false;
function focusExpense() {
  expensePanel.value
    ?.querySelector<HTMLInputElement>("input:not(:disabled)")
    ?.focus({ preventScroll: true });
}
function containExpenseFocus(event: FocusEvent) {
  const panel = expensePanel.value;
  if (
    releaseScroll &&
    panel &&
    event.target instanceof Node &&
    !panel.contains(event.target)
  )
    focusExpense();
}
watch(
  [modal, expensePanel],
  async ([open, panel]) => {
    const current = ++modalEpoch;
    if (!open || !panel) return;
    if (!props.workspace) {
      releaseScroll ||= lockEditorScroll();
      document.addEventListener("focusin", containExpenseFocus);
    }
    await nextTick();
    if (!disposed && current === modalEpoch && modal.value) {
      if (props.workspace)
        panel.scrollIntoView({
          behavior: motion.reduced.value ? "instant" : "smooth",
          block: "nearest",
        });
      focusExpense();
    }
  },
  { flush: "post" },
);
function releaseExpense() {
  if (modal.value && !disposed) return;
  document.removeEventListener("focusin", containExpenseFocus);
  releaseScroll?.();
  releaseScroll = undefined;
  if (expenseTrigger?.isConnected)
    expenseTrigger.focus({ preventScroll: true });
  expenseTrigger = undefined;
}
function trapExpenseTab(event: KeyboardEvent) {
  if (props.workspace) return;
  const panel = expensePanel.value;
  if (!panel) return;
  const fields = [
    ...panel.querySelectorAll<HTMLElement>(
      "button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex='-1'])",
    ),
  ].filter((node) => node.getClientRects().length > 0);
  const first = fields[0],
    last = fields.at(-1),
    active = document.activeElement;
  if (!first || !last) {
    event.preventDefault();
    panel.focus();
    return;
  }
  if (
    !panel.contains(active) ||
    (event.shiftKey && active === first) ||
    (!event.shiftKey && active === last)
  ) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  }
}
function closeExpense(event?: KeyboardEvent) {
  if (!event?.isComposing) modal.value = false;
}
function enterExpense(element: Element, done: () => void) {
  if (props.workspace) {
    const height = (element as HTMLElement).scrollHeight;
    void motion
      .play(element as HTMLElement, [
        { height: "0px", opacity: 0.35 },
        { height: height + "px", opacity: 1 },
      ])
      .then(done);
    return;
  }
  const panel = element.querySelector<HTMLElement>(".tt-expense-form");
  void Promise.all([
    motion.play(element as HTMLElement, [{ opacity: 0.25 }, { opacity: 1 }]),
    motion.play(panel, [
      { transform: "translate3d(0,12px,0) scale(.985)", opacity: 0.6 },
      { transform: "translate3d(0,0,0) scale(1)", opacity: 1 },
    ]),
  ]).then(done);
}
function leaveExpense(element: Element, done: () => void) {
  if (props.workspace) {
    void motion
      .play(
        element as HTMLElement,
        [
          {
            height:
              (element as HTMLElement).getBoundingClientRect().height + "px",
            opacity: 1,
          },
          { height: "0px", opacity: 0 },
        ],
        "fast",
      )
      .then(done);
    return;
  }
  void Promise.all([
    motion.play(
      element as HTMLElement,
      [{ opacity: 1 }, { opacity: 0 }],
      "fast",
    ),
    motion.play(
      element.querySelector<HTMLElement>(".tt-expense-form"),
      [
        { transform: "translate3d(0,0,0) scale(1)", opacity: 1 },
        { transform: "translate3d(0,8px,0) scale(.99)", opacity: 0 },
      ],
      "fast",
    ),
  ]).then(done);
}
onBeforeUnmount(() => {
  disposed = true;
  readEpoch++;
  readRequest?.abort();
  modalEpoch++;
  releaseExpense();
});
async function load(summaryOnly = false) {
  if (disposed) return;
  readRequest?.abort();
  const request = new AbortController(),
    current = ++readEpoch,
    tripId = props.trip.id;
  readRequest = request;
  loading.value = true;
  loadError.value = "";
  try {
    const [nextSummary, nextPacking] = await Promise.all([
      api<ExpenseSummary>(
        "/tools/trips/" + tripId + "/expenses",
        "GET",
        undefined,
        request.signal,
      ),
      summaryOnly
        ? Promise.resolve(undefined)
        : api<Packing[]>(
            "/tools/trips/" + tripId + "/packing",
            "GET",
            undefined,
            request.signal,
          ),
    ]);
    if (disposed || current !== readEpoch || tripId !== props.trip.id) return;
    summary.value = nextSummary;
    if (nextPacking) packing.value = nextPacking;
    loaded.value = true;
  } catch (failure) {
    if (disposed || current !== readEpoch || request.signal.aborted) return;
    loadError.value =
      failure instanceof Error ? failure.message : "费用与行李读取失败，请重试";
    throw failure;
  } finally {
    if (current === readEpoch) loading.value = false;
  }
}
watch(
  () => props.trip.id,
  () => {
    summaryRefreshPending = false;
    modal.value = false;
    summary.value = { items: [], actual: 0, estimated: 0 };
    packing.value = [];
    newItem.value = "";
    categoryFilter.value = packingFilter.value = "ALL";
    loaded.value = false;
    void load().catch(() => undefined);
  },
  { immediate: true },
);
function flushSummaryRefresh() {
  if (
    !summaryRefreshPending ||
    disposed ||
    busy.value ||
    loading.value ||
    !loaded.value
  )
    return;
  summaryRefreshPending = false;
  void load(true).catch(() => undefined);
}
watch(
  () => [props.trip.id, props.trip.version] as const,
  ([tripId, version], before) => {
    if (!before || tripId !== before[0] || version === before[1]) return;
    summaryRefreshPending = true;
    flushSummaryRefresh();
  },
);
watch([busy, loading], flushSummaryRefresh, { flush: "post" });
const labels: Record<string, string> = {
  CATERING: "餐饮",
  TRAFFIC: "交通",
  HOTEL: "住宿",
  TICKET: "门票",
  SHOPPING: "购物",
  OTHER: "其他",
};
function open(e?: Expense) {
  if (props.readonly || busy.value) return;
  if (!modal.value && !releaseScroll)
    expenseTrigger =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : undefined;
  editing.value = e?.id;
  source.value = e?.sourceItemId;
  Object.assign(
    form,
    e || {
      category: "OTHER",
      expenseName: "",
      amount: 0,
      expenseDate:
        props.trip.startDate || new Date().toLocaleDateString("en-CA"),
      remark: "",
    },
  );
  key.value = requestKey();
  modal.value = true;
}
function record(itemId: number) {
  if (props.readonly || busy.value) return;
  for (const d of props.trip.days) {
    const i = d.items.find((i) => i.id === itemId);
    if (i) {
      open({
        category:
          i.itemType === "FOOD"
            ? "CATERING"
            : i.itemType === "HOTEL"
              ? "HOTEL"
              : i.itemType === "TRAFFIC"
                ? "TRAFFIC"
                : "TICKET",
        expenseName: i.itemName,
        amount: i.costEstimate,
        expenseDate:
          d.dayDate ||
          props.trip.startDate ||
          new Date().toLocaleDateString("en-CA"),
        sourceItemId: i.id,
      });
      if (
        typeof i.costEstimate !== "number" ||
        !Number.isFinite(i.costEstimate) ||
        i.costEstimate < 0
      )
        form.amount = "";
      break;
    }
  }
}
function save() {
  if (locked.value) return;
  run(async () => {
    if (editing.value)
      await api("/tools/expenses/" + editing.value, "PUT", form);
    else
      await api("/tools/trips/" + props.trip.id + "/expenses", "POST", {
        expense: form,
        requestKey: key.value,
        sourceItemId: source.value,
      });
    modal.value = false;
    await load();
  }, "账单已保存");
}
function remove(e: Expense) {
  if (locked.value) return;
  if (!confirm("删除这条账单？")) return;
  run(async () => {
    await api("/tools/expenses/" + e.id, "DELETE");
    await load();
  });
}
function addPacking() {
  if (locked.value) return;
  run(async () => {
    await api("/tools/trips/" + props.trip.id + "/packing", "POST", {
      itemName: newItem.value,
    });
    newItem.value = "";
    await load();
  });
}
function toggle(i: Packing, event?: Event) {
  const input = event?.target;
  if (input instanceof HTMLInputElement) input.checked = Boolean(i.isChecked);
  if (locked.value) return;
  run(async () => {
    await api("/tools/packing/" + i.id, "PATCH", { checked: !i.isChecked });
    await load();
  });
}
function deletePacking(i: Packing) {
  if (locked.value) return;
  run(async () => {
    await api("/tools/packing/" + i.id, "DELETE");
    await load();
  });
}
function template() {
  if (locked.value) return;
  run(async () => {
    await api(
      "/tools/trips/" + props.trip.id + "/packing/template",
      "POST",
      {},
    );
    await load();
  }, "基础清单已添加");
}
const categoryTotals = computed(() =>
  Object.entries(labels).map(([category, label]) => ({
    category,
    label,
    amount: summary.value.items
      .filter((item) => item.category === category)
      .reduce((sum, item) => sum + Number(item.amount || 0), 0),
  })),
);
const filteredExpenses = computed(() =>
  categoryFilter.value === "ALL"
    ? summary.value.items
    : summary.value.items.filter(
        (item) => item.category === categoryFilter.value,
      ),
);
const checkedCount = computed(
  () => packing.value.filter((item) => Boolean(item.isChecked)).length,
);
const filteredPacking = computed(() =>
  packing.value.filter(
    (item) =>
      packingFilter.value === "ALL" ||
      (packingFilter.value === "DONE"
        ? Boolean(item.isChecked)
        : !item.isChecked),
  ),
);
defineExpose({ record });
</script>
<template>
  <div
    class="tt-space"
    :class="{ 'tt-workspace': workspace }"
    :aria-busy="loading || busy"
  >
    <div class="tt-overview" aria-label="费用与行李概览">
      <div>
        <small>实际消费</small
        ><strong>{{
          loaded ? money(summary.actual) : loading ? "读取中…" : "尚未载入"
        }}</strong
        ><span>{{ summary.items.length }} 笔已登记账单</span>
      </div>
      <div>
        <small>路书预估</small
        ><strong>{{
          loaded ? money(summary.estimated) : loading ? "读取中…" : "尚未载入"
        }}</strong
        ><span>路书记录的预估值，非实时价格</span>
      </div>
      <div>
        <small>打包进度</small
        ><strong
          >{{ checkedCount }}<em> / {{ packing.length }}</em></strong
        ><span>已确认带上的物品</span>
      </div>
    </div>
    <p v-if="loadError || error" class="tt-error" role="alert">
      {{ error || loadError }}
      <button
        v-if="loadError"
        type="button"
        :disabled="loading || busy"
        @click="load().catch(() => undefined)"
      >
        重新读取
      </button>
    </p>
    <p v-if="readonly" class="tt-readonly">
      只读模式：可以查看费用和行李，修改由所有者或可编辑成员完成。
    </p>
    <div class="tt-columns">
      <section class="tt-ledger tt-panel" aria-labelledby="travel-ledger-title">
        <header class="tt-panel-heading">
          <div>
            <small>EXPENSES</small>
            <h2 id="travel-ledger-title">旅行记账</h2>
          </div>
          <button
            type="button"
            class="tt-primary"
            :disabled="locked"
            @click="open()"
          >
            ＋ 记一笔
          </button>
        </header>
        <div class="tt-ledger-scroll">
          <p class="tt-note">
            从行程节点进入会预填费用；核对实际金额并保存后，才计入消费。
          </p>
          <Teleport to="body" :disabled="Boolean(workspace)">
            <Transition
              :css="false"
              @enter="enterExpense"
              @leave="leaveExpense"
              @after-leave="releaseExpense"
            >
              <div
                v-if="modal"
                class="tt-expense-wrap"
                :class="workspace ? 'tt-expense-inline' : 'modal-backdrop'"
                @click.self="closeExpense()"
                @keydown.esc.stop.prevent="closeExpense"
              >
                <form
                  ref="expensePanel"
                  class="modal tt-expense-form"
                  :role="workspace ? undefined : 'dialog'"
                  tabindex="-1"
                  @keydown.tab="trapExpenseTab"
                  :aria-modal="workspace ? undefined : true"
                  aria-labelledby="expense-title"
                  @submit.prevent="save"
                >
                  <header class="tt-form-heading">
                    <h3 id="expense-title">
                      {{ editing ? "编辑账单" : "记录实际消费" }}
                    </h3>
                    <button type="button" @click="closeExpense()">取消</button>
                  </header>
                  <p v-if="source" class="tt-note">
                    金额从行程预估预填，请核对实际支出。
                  </p>
                  <div class="tt-form-grid">
                    <label class="tt-wide"
                      >费用名称<input
                        v-model="form.expenseName"
                        maxlength="100"
                        required
                        :disabled="busy || readonly"
                    /></label>
                    <label
                      >金额（元）<input
                        v-model.number="form.amount"
                        type="number"
                        min="0"
                        max="9999999"
                        step="0.01"
                        required
                        :disabled="busy || readonly"
                    /></label>
                    <label
                      >分类<select
                        v-model="form.category"
                        :disabled="busy || readonly"
                      >
                        <option
                          v-for="(label, category) in labels"
                          :key="category"
                          :value="category"
                        >
                          {{ label }}
                        </option>
                      </select></label
                    >
                    <label
                      >消费日期<input
                        v-model="form.expenseDate"
                        type="date"
                        required
                        :disabled="busy || readonly"
                    /></label>
                    <label
                      >备注<input
                        v-model="form.remark"
                        maxlength="255"
                        :disabled="busy || readonly"
                    /></label>
                  </div>
                  <p v-if="error" class="tt-error" role="alert">{{ error }}</p>
                  <button class="tt-primary" :disabled="locked">
                    {{ busy ? "保存中…" : "确认保存" }}
                  </button>
                </form>
              </div>
            </Transition>
          </Teleport>
          <nav class="tt-categories" aria-label="账单分类筛选">
            <button
              type="button"
              :aria-pressed="categoryFilter === 'ALL'"
              @click="categoryFilter = 'ALL'"
            >
              全部 <span>{{ summary.items.length }}</span>
            </button>
            <button
              v-for="category in categoryTotals"
              :key="category.category"
              type="button"
              :aria-pressed="categoryFilter === category.category"
              @click="categoryFilter = category.category"
            >
              {{ category.label }} <span>{{ money(category.amount) }}</span>
            </button>
          </nav>
          <TransitionGroup name="tt-list" tag="div" class="tt-expense-list">
            <article
              v-for="expense in filteredExpenses"
              :key="expense.id"
              class="tt-expense-row"
            >
              <div>
                <h3>{{ expense.expenseName }}</h3>
                <p>
                  {{ labels[expense.category] || expense.category }} ·
                  {{ expense.expenseDate
                  }}<span v-if="expense.sourceItemId"> · 来自行程节点</span>
                </p>
                <small v-if="expense.remark">{{ expense.remark }}</small>
              </div>
              <div class="tt-expense-amount">
                <strong>{{ money(expense.amount) }}</strong>
                <div class="tt-row-actions">
                  <button
                    type="button"
                    :disabled="locked"
                    @click="open(expense)"
                  >
                    编辑</button
                  ><button
                    type="button"
                    :disabled="locked"
                    @click="remove(expense)"
                  >
                    删除
                  </button>
                </div>
              </div>
            </article>
          </TransitionGroup>
          <p v-if="loaded && !filteredExpenses.length" class="tt-empty">
            {{
              summary.items.length
                ? "这个分类还没有账单。"
                : "还没有记录花费，记下旅途中的第一笔消费。"
            }}
          </p>
          <p v-if="loading" class="tt-note" role="status">
            正在读取最新费用与清单…
          </p>
        </div>
      </section>
      <section
        class="tt-packing tt-panel"
        aria-labelledby="travel-packing-title"
      >
        <header class="tt-panel-heading">
          <div>
            <small>PACKING</small>
            <h2 id="travel-packing-title">行李清单</h2>
          </div>
          <button type="button" :disabled="locked" @click="template">
            添加基础清单
          </button>
        </header>
        <div class="tt-packing-status">
          <span>已备齐 {{ checkedCount }} / {{ packing.length }}</span
          ><progress
            :value="checkedCount"
            :max="packing.length || 1"
            aria-label="行李打包进度"
          ></progress>
        </div>
        <form class="tt-packing-add" @submit.prevent="addPacking">
          <input
            v-model="newItem"
            required
            maxlength="100"
            :disabled="locked"
            aria-label="添加行李物品"
            placeholder="还要带些什么？"
          /><button class="tt-primary" :disabled="locked || !newItem.trim()">
            添加
          </button>
        </form>
        <nav class="tt-packing-filters" aria-label="行李状态筛选">
          <button
            type="button"
            :aria-pressed="packingFilter === 'ALL'"
            @click="packingFilter = 'ALL'"
          >
            全部</button
          ><button
            type="button"
            :aria-pressed="packingFilter === 'TODO'"
            @click="packingFilter = 'TODO'"
          >
            待打包 {{ packing.length - checkedCount }}</button
          ><button
            type="button"
            :aria-pressed="packingFilter === 'DONE'"
            @click="packingFilter = 'DONE'"
          >
            已备齐 {{ checkedCount }}
          </button>
        </nav>
        <div class="tt-packing-scroll">
          <TransitionGroup name="tt-list" tag="div" class="tt-packing-list">
            <div
              v-for="item in filteredPacking"
              :key="item.id"
              class="tt-packing-row"
              :class="{ checked: Boolean(item.isChecked) }"
            >
              <input
                :id="'packing-' + item.id"
                type="checkbox"
                :checked="Boolean(item.isChecked)"
                :disabled="locked"
                @change="toggle(item, $event)"
              /><label :for="'packing-' + item.id">{{ item.itemName }}</label
              ><button
                type="button"
                :disabled="locked"
                @click="deletePacking(item)"
              >
                删除
              </button>
            </div>
          </TransitionGroup>
          <p v-if="loaded && !filteredPacking.length" class="tt-empty">
            {{
              packing.length
                ? "当前状态没有物品。"
                : "清单还空着，可以逐项添加或使用基础清单。"
            }}
          </p>
        </div>
      </section>
    </div>
  </div>
</template>
<style scoped>
.tt-space {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  color: var(--wb-v5-panel-ink, #163c57);
}
.tt-workspace {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.tt-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  flex: none;
}
.tt-overview > div {
  padding: 15px 19px;
  border: 1px solid var(--wb-v5-edge, #e9f6ff);
  border-radius: 16px;
  background: var(
    --wb-v5-subpanel,
    linear-gradient(130deg, #dff0faec, #cce3f5df)
  );
  box-shadow: inset 0 1px 0 #ffffff9c;
}
.tt-overview small,
.tt-overview span {
  display: block;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 12px;
}
.tt-overview strong {
  display: block;
  margin: 4px 0;
  font-size: clamp(23px, 2vw, 29px);
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.tt-overview em {
  font-size: 16px;
  font-style: normal;
  font-weight: 500;
}
.tt-columns {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: 16px;
  min-height: 0;
  flex: 1;
}
.tt-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  padding: 19px;
  border: 1px solid var(--wb-v5-edge, #e9f6ff);
  border-radius: 21px;
  background: var(--wb-v5-panel, linear-gradient(135deg, #d4eafaed, #bfdaefdf));
  box-shadow: var(--wb-v5-shadow, 0 8px 25px #12344d16);
}
.tt-panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: none;
  margin-bottom: 12px;
}
.tt-panel-heading small {
  font-size: 9px;
  letter-spacing: 0.15em;
  color: var(--wb-v5-muted, #35566d);
}
.tt-panel-heading h2 {
  margin: 3px 0 0;
  font-size: 19px;
  font-weight: 650;
}
.tt-space button,
.tt-expense-form button {
  min-height: 31px;
  border: 1px solid var(--wb-v5-edge, #e9f6ff);
  border-radius: 10px;
  padding: 6px 10px;
  color: var(--wb-v5-panel-ink, #163c57);
  background: var(--wb-v5-control-bg, #daebf7);
  font: inherit;
  font-size: 12px;
  box-shadow: inset 0 1px 0 #fff8;
  cursor: pointer;
}
.tt-space button:hover:not(:disabled),
.tt-expense-form button:hover:not(:disabled) {
  filter: brightness(1.035);
}
.tt-space button:disabled,
.tt-expense-form button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.tt-space button:focus-visible,
.tt-expense-form button:focus-visible {
  outline: 2px solid #ad6037;
  outline-offset: 3px;
}
.tt-space .tt-primary,
.tt-expense-form .tt-primary {
  color: #fff;
  border-color: #e49d70;
  background: linear-gradient(135deg, #d86c31, #b94a1b);
  box-shadow:
    inset 0 1px 0 #ffd3b899,
    0 2px 5px #6f351918;
}
.tt-ledger-scroll,
.tt-packing-scroll {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: #7299b866 transparent;
}
.tt-workspace .tt-ledger-scroll,
.tt-workspace .tt-packing-scroll {
  flex: 1;
}
.tt-note,
.tt-readonly {
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 12px;
}
.tt-readonly {
  margin: 0;
}
.tt-categories {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}
.tt-categories button span {
  padding-left: 5px;
  opacity: 0.8;
  font-size: 11px;
}
.tt-categories button[aria-pressed="true"],
.tt-packing-filters button[aria-pressed="true"] {
  background: var(--wb-v5-input-bg, #eef7ff);
  color: #a44e27;
  border-color: #f4d3b8;
}
.tt-expense-list,
.tt-packing-list {
  display: grid;
  gap: 8px;
}
.tt-expense-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
  padding: 14px 12px;
  border: 1px solid var(--wb-v5-edge, #e9f6ff);
  border-radius: 13px;
  background: var(--wb-v5-subpanel, #d2e7f5);
}
.tt-expense-row > div:first-child {
  min-width: 0;
}
.tt-expense-row h3 {
  margin: 0 0 7px;
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.tt-expense-row p,
.tt-expense-row small {
  margin: 0;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 12px;
  line-height: 1.7;
}
.tt-expense-row small {
  display: block;
  margin-top: 4px;
  overflow-wrap: anywhere;
}
.tt-expense-amount {
  text-align: right;
  flex: none;
}
.tt-expense-amount strong {
  display: block;
  font-size: 17px;
  font-variant-numeric: tabular-nums;
  margin-bottom: 9px;
}
.tt-row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}
.tt-row-actions button,
.tt-packing-row button {
  border: 0;
  min-height: 24px;
  padding: 3px 6px;
  background: transparent;
  box-shadow: none;
  font-size: 11px;
}
.tt-empty {
  margin: 25px 10px;
  text-align: center;
  font-size: 13px;
  line-height: 1.8;
  color: var(--wb-v5-panel-muted, #35566d);
}
.tt-packing-status {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 0 0 16px;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 12px;
}
.tt-packing-status progress {
  width: auto;
  min-width: 0;
  flex: 1;
  height: 6px;
  accent-color: #508ea7;
}
.tt-packing-add {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.tt-space input,
.tt-expense-form input,
.tt-expense-form select {
  box-sizing: border-box;
  min-height: 37px;
  border: 1px solid var(--wb-v5-edge, #e9f6ff);
  border-radius: 10px;
  background: var(--wb-v5-input-bg, #ecf7ff);
  color: var(--wb-v5-panel-ink, #163c57);
  padding: 7px 10px;
  font: inherit;
  font-size: 13px;
}
.tt-space input:focus-visible,
.tt-expense-form input:focus-visible,
.tt-expense-form select:focus-visible {
  outline: 2px solid #75a1bf;
  outline-offset: 1px;
}
.tt-packing-add input {
  flex: 1;
  min-width: 0;
}
.tt-packing-filters {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}
.tt-packing-filters button {
  flex: 1;
  padding-inline: 4px;
}
.tt-packing-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 9px;
  border: 1px solid var(--wb-v5-edge, #e9f6ff);
  border-radius: 12px;
  background: var(--wb-v5-subpanel, #d2e7f5);
}
.tt-packing-row input {
  flex: none;
  width: 17px;
  height: 17px;
  min-height: 0;
  margin: 0;
  padding: 0;
  accent-color: #478794;
}
.tt-packing-row label {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  overflow-wrap: anywhere;
  transition: color var(--motion-fast, 160ms);
}
.tt-packing-row.checked label {
  text-decoration: line-through;
  color: var(--wb-v5-muted, #35566d);
}
.tt-packing-row.checked {
  background: color-mix(in srgb, #afd7de 55%, transparent);
}
.tt-error {
  margin: 0;
  color: #9c3433;
  font-size: 12px;
  line-height: 1.6;
}
.tt-expense-inline {
  overflow: hidden;
}
.tt-expense-inline .tt-expense-form {
  position: static;
  width: 100%;
  max-width: none;
  max-height: none;
  margin: 0 0 15px;
  padding: 16px;
  box-sizing: border-box;
  border-radius: 15px;
  background: var(--wb-v5-subpanel, #d8eaf6);
  color: var(--wb-v5-panel-ink, #163c57);
  border: 1px solid var(--wb-v5-edge, #e9f6ff);
  box-shadow: inset 0 1px 0 #ffffff80;
}
.tt-expense-form {
  color: #163c57;
}
.tt-form-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}
.tt-form-heading h3 {
  margin: 0;
  font-size: 17px;
}
.tt-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 13px;
  margin-bottom: 16px;
}
.tt-form-grid label {
  min-width: 0;
  display: grid;
  gap: 6px;
  color: inherit;
  font-size: 12px;
}
.tt-form-grid input,
.tt-form-grid select {
  width: 100%;
  min-width: 0;
}
.tt-wide {
  grid-column: 1/-1;
}
.tt-list-enter-active,
.tt-list-leave-active,
.tt-list-move {
  transition:
    opacity var(--motion-base, 280ms),
    transform var(--motion-base, 280ms)
      var(--motion-ease, cubic-bezier(0.22, 1, 0.36, 1));
}
.tt-list-enter-from,
.tt-list-leave-to {
  opacity: 0;
  transform: translateY(7px);
}
@media (max-width: 1000px) {
  .tt-columns {
    grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
    gap: 12px;
  }
  .tt-panel {
    padding: 15px;
  }
  .tt-expense-row {
    gap: 9px;
  }
}
@media (max-width: 760px) {
  .tt-workspace {
    overflow-y: auto;
  }
  .tt-overview {
    gap: 7px;
  }
  .tt-overview > div {
    padding: 12px 10px;
  }
  .tt-overview span {
    font-size: 10px;
  }
  .tt-overview strong {
    font-size: 21px;
  }
  .tt-columns {
    display: flex;
    flex-direction: column;
    flex: none;
  }
  .tt-workspace .tt-ledger-scroll,
  .tt-workspace .tt-packing-scroll {
    overflow: visible;
    flex: none;
  }
  .tt-panel {
    flex: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .tt-list-enter-active,
  .tt-list-leave-active,
  .tt-list-move,
  .tt-packing-row label {
    transition: none;
  }
}
</style>
