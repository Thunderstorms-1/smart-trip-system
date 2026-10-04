<script setup lang="ts">
import { ref, reactive, watch } from "vue";
import { api, money, requestKey } from "./api";
import { useAction } from "./ui";
import type { Expense, ExpenseSummary, Packing, Trip } from "./types";
const props = defineProps<{ trip: Trip; readonly?: boolean }>(),
  summary = ref<ExpenseSummary>({ items: [], actual: 0, estimated: 0 }),
  packing = ref<Packing[]>([]),
  newItem = ref(""),
  editing = ref<number>(),
  modal = ref(false),
  source = ref<number>(),
  key = ref(requestKey());
const form = reactive<Expense>({
  category: "OTHER",
  expenseName: "",
  amount: 0,
  expenseDate: new Date().toLocaleDateString("en-CA"),
  remark: "",
});
const { busy, error, run } = useAction();
async function load() {
  [summary.value, packing.value] = await Promise.all([
    api<ExpenseSummary>("/tools/trips/" + props.trip.id + "/expenses"),
    api<Packing[]>("/tools/trips/" + props.trip.id + "/packing"),
  ]);
}
watch(
  () => props.trip.id,
  () => run(load),
  { immediate: true },
);
const labels: Record<string, string> = {
  CATERING: "餐饮",
  TRAFFIC: "交通",
  HOTEL: "住宿",
  TICKET: "门票",
  SHOPPING: "购物",
  OTHER: "其他",
};
function open(e?: Expense) {
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
      break;
    }
  }
}
function save() {
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
  if (!confirm("删除这条账单？")) return;
  run(async () => {
    await api("/tools/expenses/" + e.id, "DELETE");
    await load();
  });
}
function addPacking() {
  run(async () => {
    await api("/tools/trips/" + props.trip.id + "/packing", "POST", {
      itemName: newItem.value,
    });
    newItem.value = "";
    await load();
  });
}
function toggle(i: Packing) {
  run(async () => {
    await api("/tools/packing/" + i.id, "PATCH", { checked: !i.isChecked });
    await load();
  });
}
function deletePacking(i: Packing) {
  run(async () => {
    await api("/tools/packing/" + i.id, "DELETE");
    await load();
  });
}
function template() {
  run(async () => {
    await api(
      "/tools/trips/" + props.trip.id + "/packing/template",
      "POST",
      {},
    );
    await load();
  }, "基础清单已添加");
}
defineExpose({ record });
</script>
<template>
  <div class="tools-grid">
    <section class="paper">
      <div class="row">
        <h2>旅行记账</h2>
        <button
          class="button small"
          :disabled="busy || readonly"
          @click="open()"
        >
          ＋ 记一笔
        </button>
      </div>
      <div class="expense-total">
        <div>
          <small>实际消费</small><strong>{{ money(summary.actual) }}</strong>
        </div>
        <div>
          <small>行程预估</small><strong>{{ money(summary.estimated) }}</strong>
        </div>
      </div>
      <p class="muted">从节点记账会预填费用，确认金额后才记为实际消费。</p>
      <div v-for="e in summary.items" :key="e.id" class="expense-row">
        <div>
          <h3>{{ e.expenseName }}</h3>
          <p>{{ labels[e.category] }} · {{ e.expenseDate }}</p>
          <small>{{ e.remark }}</small>
        </div>
        <div>
          <strong>{{ money(e.amount) }}</strong>
          <div class="mini-actions">
            <button :disabled="busy || readonly" @click="open(e)">编辑</button
            ><button :disabled="busy || readonly" @click="remove(e)">
              删除
            </button>
          </div>
        </div>
      </div>
      <p v-if="!summary.items.length" class="empty">还没有记录花费。</p>
    </section>
    <section class="paper">
      <div class="row">
        <h2>行李清单</h2>
        <button
          class="text-button"
          :disabled="busy || readonly"
          @click="template"
        >
          添加基础清单
        </button>
      </div>
      <form class="search-bar" @submit.prevent="addPacking">
        <input
          v-model="newItem"
          required
          maxlength="100"
          aria-label="添加行李物品"
          placeholder="还要带些什么？"
        /><button class="button small" :disabled="busy || readonly">
          添加
        </button>
      </form>
      <div
        v-for="i in packing"
        :key="i.id"
        class="packing-row"
        :class="{ checked: i.isChecked }"
      >
        <input
          :id="'packing-' + i.id"
          type="checkbox"
          :checked="Boolean(i.isChecked)"
          :disabled="busy || readonly"
          @change="toggle(i)"
        /><label :for="'packing-' + i.id">{{ i.itemName }}</label
        ><button
          class="text-button"
          :disabled="busy || readonly"
          @click="deletePacking(i)"
        >
          删除
        </button>
      </div>
      <p v-if="!packing.length" class="empty">
        清单还空着，添加物品或使用基础清单。
      </p>
    </section>
  </div>
  <p v-if="error" class="form-error">{{ error }}</p>
  <Teleport to="body"
    ><div v-if="modal" class="modal-backdrop" @click.self="modal = false">
      <form
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="expense-title"
        @submit.prevent="save"
      >
        <header class="row">
          <h2 id="expense-title">
            {{ editing ? "编辑账单" : "记录实际消费" }}
          </h2>
          <button type="button" class="text-button" @click="modal = false">
            关闭
          </button>
        </header>
        <p v-if="source" class="notice">金额从行程预估预填，请核对实际支出。</p>
        <div class="form-grid">
          <label class="wide"
            >费用名称<input
              v-model="form.expenseName"
              maxlength="100"
              required /></label
          ><label
            >金额（元）<input
              v-model.number="form.amount"
              type="number"
              min="0"
              max="9999999"
              step="0.01"
              required /></label
          ><label
            >分类<select v-model="form.category">
              <option v-for="(v, k) in labels" :key="k" :value="k">
                {{ v }}
              </option>
            </select></label
          ><label class="wide"
            >消费日期<input
              v-model="form.expenseDate"
              type="date"
              required /></label
          ><label class="wide"
            >备注<input v-model="form.remark" maxlength="255"
          /></label>
        </div>
        <button class="button" :disabled="busy || readonly">
          {{ busy ? "保存中…" : "确认保存" }}
        </button>
      </form>
    </div></Teleport
  >
</template>
