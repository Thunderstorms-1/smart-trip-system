<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { onBeforeRouteLeave } from "vue-router";
import type { Task } from "./types";
import HomeIcon from "./HomeIcon.vue";
import RagSources from "./RagSources.vue";
import {
  budgetSummary,
  dateLabel,
  dateTimeLabel,
  issueLocation,
  issueTarget,
  itemTypeIcon,
  itemTypeLabel,
  money,
  userText,
  type DraftIssue,
  type EditableItem,
  type EditableTrip,
} from "./trip-draft-presentation";
import "./trip-draft-workspace.css";

const props = defineProps<{
  draft: EditableTrip;
  title: string;
  task: Task;
  busy: boolean;
  expired: boolean;
  dirty: boolean;
  conflict: boolean;
  notice: string;
  error: string;
}>();
const emit = defineEmits<{
  "update:title": [value: string];
  "save-draft": [];
  apply: [];
  reload: [];
  "budget-change": [payload: { budgetMax: number; requestKey: string }];
  "open-saved": [];
  restart: [];
}>();
const root = ref<HTMLElement>(),
  dayHeading = ref<HTMLElement>(),
  budgetPanel = ref<HTMLElement>();
const daysNav = ref<HTMLElement>(),
  daySelectionStyle = ref<Record<string, string>>({});
const selectionReady = ref(false),
  dayDirection = ref(1);
const selectedDay = ref(0),
  highlighted = ref<number[]>([]),
  warningsOpen = ref(false);
const day = computed(() => props.draft.days[selectedDay.value]);
const requirement = computed(() => props.task.generation?.requirement);
const issues = computed(() => props.task.generation?.issues ?? []);
const blocked = computed(
  () => props.task.generation?.saveBlocked === true || issues.value.length > 0,
);
const warnings = computed(() => [
  ...new Set(
    (props.task.generation?.warnings ?? []).map(userText).filter(Boolean),
  ),
]);
const corrections = computed(() => [
  ...new Set(
    (props.task.generation?.corrections ?? []).map(userText).filter(Boolean),
  ),
]);
const budget = computed(() => budgetSummary(props.draft, requirement.value));
const applied = computed(() => props.task.status === "APPLIED");
const editable = computed(
  () => !props.busy && !props.expired && !applied.value,
);
const selectedTotal = computed(() =>
  day.value ? budgetSummary({ ...props.draft, days: [day.value] }).total : null,
);
const activeDayIssues = computed(() =>
  issues.value.filter((issue) => issue.dayIndex === selectedDay.value + 1),
);
const canChangeBudget = computed(() => {
  const r = requirement.value;
  return (
    !!r &&
    r.currency === "CNY" &&
    (r.budgetScope === "TOTAL" ||
      (r.budgetScope === "PER_PERSON" && !!r.travelers)) &&
    (r.budgetMax ?? r.budget) != null
  );
});
const scopeLabel = computed(() =>
  requirement.value?.budgetScope === "PER_PERSON"
    ? "人均预算上限"
    : "全体预算上限",
);
const savedStatus = computed(() => {
  if (applied.value) return "已创建正式路书";
  if (props.expired) return "临时草稿已过期";
  if (props.busy) return "正在处理，请稍候";
  if (props.conflict) return "服务器草稿已更新";
  return props.dirty ? "有修改尚未保存" : "草稿已暂存";
});
const activityColors: Record<string, string> = {
  SCENIC: "scenic",
  FOOD: "food",
  HOTEL: "hotel",
  TRAFFIC: "traffic",
  OTHER: "other",
};
let flashTimer: ReturnType<typeof setTimeout> | undefined;
let pendingLocation:
  | {
      dayIndex: number;
      itemIndex?: number;
      related?: number;
      editor?: "activity" | "day" | "trip";
      field?: string;
      add?: boolean;
    }
  | undefined;
watch(
  () => props.draft.days.length,
  (count) => {
    selectedDay.value = Math.max(0, Math.min(selectedDay.value, count - 1));
  },
);
watch(
  () => props.task.id,
  () => {
    selectedDay.value = 0;
    highlighted.value = [];
    budgetRequest.value = undefined;
  },
);
let selectionFrame = 0;
let dayResizeObserver: ResizeObserver | undefined;
function queueDaySelection() {
  cancelAnimationFrame(selectionFrame);
  selectionFrame = requestAnimationFrame(() => {
    const nav = daysNav.value,
      button =
        nav?.querySelectorAll<HTMLElement>(".dw-day-button")[selectedDay.value];
    if (!nav || !button) return;
    daySelectionStyle.value = {
      width: button.offsetWidth + "px",
      height: button.offsetHeight + "px",
      transform:
        "translate3d(" + button.offsetLeft + "px," + button.offsetTop + "px,0)",
    };
    selectionReady.value = true;
    let top = nav.scrollTop,
      left = nav.scrollLeft;
    if (button.offsetTop < top) top = button.offsetTop;
    else if (button.offsetTop + button.offsetHeight > top + nav.clientHeight)
      top = button.offsetTop + button.offsetHeight - nav.clientHeight;
    if (button.offsetLeft < left) left = button.offsetLeft;
    else if (button.offsetLeft + button.offsetWidth > left + nav.clientWidth)
      left = button.offsetLeft + button.offsetWidth - nav.clientWidth;
    nav.scrollTo({
      left,
      top,
      behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "instant"
        : "smooth",
    });
  });
}
watch(selectedDay, queueDaySelection, { flush: "post" });
onMounted(() => {
  queueDaySelection();
  if (typeof ResizeObserver !== "undefined" && daysNav.value) {
    dayResizeObserver = new ResizeObserver(queueDaySelection);
    dayResizeObserver.observe(daysNav.value);
  } else window.addEventListener("resize", queueDaySelection);
});
function dayIssues(index: number) {
  return issues.value.filter((issue) => issue.dayIndex === index + 1).length;
}
function shortDate(value?: string) {
  if (!value) return "日期待定";
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return match
    ? Number(match[2]) + "月" + Number(match[3]) + "日"
    : dateLabel(value);
}
function duration(item: EditableItem) {
  const value = item.costTimeHours;
  return value == null || value === "" || !Number.isFinite(Number(value))
    ? "时长待确认"
    : value + " 小时";
}
function activityCost(item: EditableItem) {
  const value = item.costEstimate;
  return value == null || value === "" || !Number.isFinite(Number(value))
    ? "费用待确认"
    : money(Number(value));
}
function selectDay(index: number) {
  pendingLocation = undefined;
  clearTimeout(flashTimer);
  if (index !== selectedDay.value)
    dayDirection.value = index > selectedDay.value ? 1 : -1;
  selectedDay.value = index;
  highlighted.value = [];
}
function finishPendingLocation() {
  const location = pendingLocation;
  if (!location || location.dayIndex !== selectedDay.value) return;
  const content = root.value?.querySelector<HTMLElement>(
    '[data-day-index="' + location.dayIndex + '"]',
  );
  if (!content) return;
  const target =
    location.itemIndex == null
      ? content.querySelector<HTMLElement>("h2")
      : content.querySelector<HTMLElement>(
          '[data-activity-index="' + location.itemIndex + '"]',
        );
  if (!target) return;
  pendingLocation = undefined;
  highlighted.value = [location.itemIndex, location.related].filter(
    (i): i is number => i != null && i >= 0,
  );
  target.focus({ preventScroll: true });
  target.scrollIntoView({
    block: "nearest",
    behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
      ? "instant"
      : "smooth",
  });
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => (highlighted.value = []), 3200);
  if (editable.value) {
    if (location.editor === "activity")
      openActivity(
        location.add ? -1 : (location.itemIndex ?? -1),
        location.field,
      );
    else if (location.editor === "day") openDayEditor(location.field);
    else if (location.editor === "trip") openTripEditor(location.field);
  }
}
async function locateActivity(
  dayIndex: number,
  itemIndex?: number,
  related?: number,
  editor?: {
    editor: "activity" | "day" | "trip";
    field?: string;
    add?: boolean;
  },
) {
  if (dayIndex < 0 || dayIndex >= props.draft.days.length) return;
  clearTimeout(flashTimer);
  pendingLocation = { dayIndex, itemIndex, related, ...editor };
  const changingDay = selectedDay.value !== dayIndex;
  if (changingDay) dayDirection.value = dayIndex > selectedDay.value ? 1 : -1;
  selectedDay.value = dayIndex;
  highlighted.value = [];
  if (changingDay) return;
  await nextTick();
  finishPendingLocation();
}
function issueEditField(code: string) {
  if (["MISSING_COST", "INVALID_COST", "UNKNOWN_COST_AS_ZERO"].includes(code))
    return "costEstimate";
  if (["MISSING_DURATION", "INVALID_DURATION"].includes(code))
    return "costTimeHours";
  if (
    [
      "TIME_OVERLAP",
      "FIXED_NODE_CONFLICT",
      "INVALID_START_TIME",
      "FIXED_TIME_MISSING",
      "DAY_END_OVERFLOW",
    ].includes(code)
  )
    return "startTime";
  if (code === "INVALID_ITEM_TYPE") return "itemType";
  return "itemName";
}
async function locateIssue(issue: DraftIssue) {
  const target = issueTarget(issue, props.draft);
  if (target.kind === "budget") {
    budgetPanel.value?.focus({ preventScroll: true });
    budgetPanel.value?.scrollIntoView({
      block: "center",
      behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "instant"
        : "smooth",
    });
  } else if (target.dayIndex != null) {
    const editor =
      target.kind === "activity"
        ? { editor: "activity" as const, field: issueEditField(issue.code) }
        : ["DAY_EMPTY", "MISSING_ITEMS"].includes(issue.code)
          ? { editor: "activity" as const, add: true }
          : issue.code === "DAY_DATE"
            ? { editor: "trip" as const, field: "startDate" }
            : { editor: "day" as const, field: "dayTheme" };
    await locateActivity(
      target.dayIndex - 1,
      target.itemIndex == null ? undefined : target.itemIndex - 1,
      issue.relatedItemIndex == null ? undefined : issue.relatedItemIndex - 1,
      editor,
    );
  } else if (editable.value)
    openTripEditor(
      issue.code === "START_DATE"
        ? "startDate"
        : issue.code === "SUMMARY"
          ? "summary"
          : issue.code === "DESTINATION"
            ? "destination"
            : "title",
    );
}
type Dialog = "activity" | "trip" | "day" | "budget";
const dialog = ref<Dialog>(),
  dialogPanel = ref<HTMLElement>(),
  dialogError = ref("");
const activityBuffer = ref<EditableItem>(),
  editingIndex = ref(-1);
const metadata = ref({
  title: "",
  destination: "",
  startDate: "",
  summary: "",
  dayTheme: "",
  daySummary: "",
});
const proposedBudget = ref<number | "">(""),
  budgetConfirmed = ref(false);
const budgetRequest = ref<{
  taskId: string;
  budgetMax: number;
  requestKey: string;
}>();
let dialogBaseline = "",
  dialogDraft: EditableTrip | undefined,
  dialogRevision = 0;
let previousFocus: HTMLElement | null = null;
let activityOriginal: EditableItem | undefined;
let originalDescription = { summary: "", dayTheme: "", daySummary: "" };
function bufferState() {
  return JSON.stringify(
    dialog.value === "activity"
      ? activityBuffer.value
      : dialog.value === "budget"
        ? { value: proposedBudget.value, confirmed: budgetConfirmed.value }
        : metadata.value,
  );
}
const dialogDirty = computed(
  () => !!dialog.value && bufferState() !== dialogBaseline,
);
const dialogTitle = computed(
  () =>
    ({
      activity: editingIndex.value < 0 ? "添加一段旅程" : "编辑活动",
      trip: "编辑旅行信息",
      day: "编辑当日主题",
      budget: "调整本次预算上限",
    })[dialog.value || "trip"],
);
async function showDialog(kind: Dialog, focusField?: string) {
  previousFocus =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
  dialog.value = kind;
  dialogError.value = "";
  dialogDraft = props.draft;
  dialogRevision = props.task.generation?.draftRevision ?? 0;
  dialogBaseline = bufferState();
  await nextTick();
  (
    (focusField
      ? dialogPanel.value?.querySelector<HTMLElement>(
          '[data-edit-field="' + focusField + '"]',
        )
      : null) ||
    dialogPanel.value?.querySelector<HTMLElement>("[data-autofocus]") ||
    dialogPanel.value?.querySelector<HTMLElement>("input, textarea, button")
  )?.focus();
}
function openActivity(index = -1, focusField?: string) {
  if (!editable.value || !day.value) return;
  editingIndex.value = index;
  activityBuffer.value =
    index < 0
      ? {
          itemName: "",
          itemType: "OTHER",
          startTime: "",
          costTimeHours: "",
          costEstimate: "",
          scheduleLocked: false,
        }
      : JSON.parse(JSON.stringify(day.value.items[index]));
  const buffer = activityBuffer.value!;
  activityOriginal = JSON.parse(JSON.stringify(buffer));
  buffer.itemName ??= "";
  buffer.costEstimate ??= "";
  buffer.costTimeHours ??= "";
  if (buffer.aiTips) buffer.aiTips = userText(buffer.aiTips);
  if (buffer.transportTips)
    buffer.transportTips = userText(buffer.transportTips);
  void showDialog("activity", focusField);
}
function openTripEditor(focusField?: string) {
  if (!editable.value) return;
  originalDescription = {
    summary: props.draft.summary || "",
    dayTheme: "",
    daySummary: "",
  };
  metadata.value = {
    title: props.title,
    destination: props.draft.destination || "",
    startDate: props.draft.startDate || "",
    summary: userText(props.draft.summary || ""),
    dayTheme: "",
    daySummary: "",
  };
  void showDialog("trip", focusField);
}
function openDayEditor(focusField?: string) {
  if (!editable.value || !day.value) return;
  originalDescription = {
    summary: "",
    dayTheme: day.value.dayTheme || "",
    daySummary: day.value.daySummary || "",
  };
  metadata.value = {
    title: "",
    destination: "",
    startDate: "",
    summary: "",
    dayTheme: userText(day.value.dayTheme || ""),
    daySummary: userText(day.value.daySummary || ""),
  };
  void showDialog("day", focusField);
}
function openBudgetEditor() {
  if (!editable.value || !canChangeBudget.value) return;
  proposedBudget.value =
    budgetRequest.value?.taskId === props.task.id
      ? budgetRequest.value.budgetMax
      : (requirement.value?.budgetMax ?? requirement.value?.budget ?? "");
  budgetConfirmed.value = false;
  void showDialog("budget");
}
function closeDialog(force = false) {
  if (
    !force &&
    dialogDirty.value &&
    !window.confirm("编辑尚未应用，关闭会放弃这些修改。确定关闭吗？")
  )
    return;
  dialog.value = undefined;
  dialogError.value = "";
  void nextTick(() =>
    previousFocus?.isConnected
      ? previousFocus.focus()
      : dayHeading.value?.focus(),
  );
}
function dialogKeys(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    closeDialog();
  }
  if (event.key !== "Tab") return;
  const controls = Array.from(
    dialogPanel.value?.querySelectorAll<HTMLElement>(
      'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex="0"]',
    ) ?? [],
  ).filter((control) => control.getClientRects().length);
  const first = controls[0],
    last = controls[controls.length - 1];
  if (
    event.shiftKey &&
    (document.activeElement === first ||
      document.activeElement === dialogPanel.value)
  ) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
function commitEditor() {
  if (!editable.value || !dialog.value) return;
  if (
    dialogDraft !== props.draft ||
    dialogRevision !== (props.task.generation?.draftRevision ?? 0)
  ) {
    dialogError.value =
      "草稿已更新，当前输入仍保留。请关闭并重新打开编辑，核对最新内容后再修改。";
    return;
  }
  if (dialog.value === "budget") {
    const value = proposedBudget.value,
      old = requirement.value?.budgetMax ?? requirement.value?.budget;
    if (props.dirty || props.conflict) {
      dialogError.value = "请先保存草稿修改并处理版本冲突，再调整预算上限。";
      return;
    }
    if (
      value === "" ||
      !Number.isFinite(value) ||
      value <= Number(old) ||
      value > 9999999
    ) {
      dialogError.value = "请填写高于当前上限、且不超过 9,999,999 元的新预算。";
      return;
    }
    if (!budgetConfirmed.value) {
      dialogError.value = "请明确确认新的预算约束。";
      return;
    }
    if (
      !budgetRequest.value ||
      budgetRequest.value.taskId !== props.task.id ||
      budgetRequest.value.budgetMax !== value
    )
      budgetRequest.value = {
        taskId: props.task.id,
        budgetMax: value,
        requestKey: crypto.randomUUID(),
      };
    emit("budget-change", {
      budgetMax: value,
      requestKey: budgetRequest.value.requestKey,
    });
  } else if (dialog.value === "activity") {
    const item = activityBuffer.value!;
    if (!item.itemName.trim() || item.itemName.trim().length > 100) {
      dialogError.value = "请填写 1–100 字的活动名称。";
      return;
    }
    if (item.startTime && !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(item.startTime)) {
      dialogError.value = "请填写有效开始时间，暂不确定可以留空。";
      return;
    }
    if (
      item.costTimeHours !== "" &&
      item.costTimeHours != null &&
      (!Number.isFinite(Number(item.costTimeHours)) ||
        Number(item.costTimeHours) < 0.1 ||
        Number(item.costTimeHours) > 24)
    ) {
      dialogError.value = "停留时长请填 0.1–24 小时，未知时可以留空。";
      return;
    }
    if (
      item.costEstimate !== "" &&
      item.costEstimate != null &&
      (!Number.isFinite(Number(item.costEstimate)) ||
        Number(item.costEstimate) < 0 ||
        Number(item.costEstimate) > 9999999)
    ) {
      dialogError.value =
        "费用请填写有效的非负金额；未知请留空，只有确认免费才填 0。";
      return;
    }
    const updated = { ...item, itemName: item.itemName.trim() };
    for (const key of ["aiTips", "transportTips"] as const)
      if (updated[key] === userText(activityOriginal?.[key]))
        updated[key] = activityOriginal?.[key];
    if (editingIndex.value < 0) day.value?.items.push(updated);
    else day.value?.items.splice(editingIndex.value, 1, updated);
  } else if (dialog.value === "trip") {
    if (
      !metadata.value.title.trim() ||
      metadata.value.title.trim().length > 100
    ) {
      dialogError.value = "路书标题需要 1–100 字。";
      return;
    }
    emit("update:title", metadata.value.title.trim());
    props.draft.destination = metadata.value.destination.trim();
    props.draft.startDate = metadata.value.startDate || undefined;
    props.draft.summary =
      metadata.value.summary === userText(originalDescription.summary)
        ? originalDescription.summary
        : metadata.value.summary;
  } else if (day.value) {
    day.value.dayTheme =
      metadata.value.dayTheme === userText(originalDescription.dayTheme)
        ? originalDescription.dayTheme
        : metadata.value.dayTheme;
    day.value.daySummary =
      metadata.value.daySummary === userText(originalDescription.daySummary)
        ? originalDescription.daySummary
        : metadata.value.daySummary;
  }
  closeDialog(true);
}
function deleteActivity() {
  if (!editable.value || editingIndex.value < 0 || !day.value) return;
  if (
    !window.confirm("确定从本日行程中删除这项活动？删除后需保存草稿重新校验。")
  )
    return;
  day.value.items.splice(editingIndex.value, 1);
  closeDialog(true);
}
function reload() {
  if (
    (props.dirty || dialogDirty.value) &&
    !window.confirm("读取服务器草稿会放弃本页未保存修改。确定继续吗？")
  )
    return;
  closeDialog(true);
  emit("reload");
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dialogDirty.value) return;
  event.preventDefault();
  event.returnValue = "";
}
window.addEventListener("beforeunload", beforeUnload);
onBeforeRouteLeave(
  () =>
    !dialogDirty.value ||
    window.confirm("编辑抽屉里有尚未应用的修改，确定离开吗？"),
);
onBeforeUnmount(() => {
  clearTimeout(flashTimer);
  cancelAnimationFrame(selectionFrame);
  dayResizeObserver?.disconnect();
  window.removeEventListener("resize", queueDaySelection);
  window.removeEventListener("beforeunload", beforeUnload);
});
</script>

<template>
  <main ref="root" class="trip-draft-workspace" aria-label="旅行草稿工作台">
    <header class="dw-hero">
      <div class="dw-hero-copy">
        <p class="dw-eyebrow">
          <span class="dw-status-dot" /> YOUR NEXT CHAPTER <span>旅行草稿</span>
        </p>
        <h1>
          {{ title || "下一段旅程，正在成形" }}
          <button
            class="dw-icon-button dw-hero-edit"
            :disabled="!editable"
            aria-label="编辑旅行信息"
            @click="openTripEditor()"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m15 4 5 5M4 20l5-1L20 8a3.5 3.5 0 0 0-5-5L4 14Z" />
            </svg>
          </button>
        </h1>
        <div class="dw-trip-facts">
          <span
            ><HomeIcon name="pin" />{{
              draft.destination || "目的地待确认"
            }}</span
          >
          <span
            ><HomeIcon name="clock" />{{
              dateLabel(draft.startDate, "出发日期待定")
            }}</span
          >
          <span>{{ draft.days.length }} 天</span>
          <span
            ><HomeIcon name="user" />{{
              requirement?.travelers
                ? requirement.travelers + " 人同行"
                : userText(draft.travelGroup || "同行人数待确认")
            }}</span
          >
        </div>
      </div>
      <div class="dw-hero-side">
        <div class="dw-hero-actions">
          <span
            class="dw-state-chip"
            :class="{ 'is-blocked': blocked && !applied }"
          >
            <span class="dw-status-dot" />{{
              applied
                ? "已保存路书"
                : expired
                  ? "草稿已过期"
                  : "仅你可见 · 临时草稿"
            }}
          </span>
          <button
            class="dw-button dw-quiet"
            :disabled="busy || !!dialog"
            @click="emit('restart')"
          >
            <HomeIcon name="route" />修改旅行需求
          </button>
        </div>
        <div class="dw-hero-metrics">
          <div>
            <span>预计总费用 · 全体合计</span
            ><strong>{{ money(budget.total) }}</strong>
            <small v-if="budget.unknownCount"
              >{{ budget.unknownCount }} 项费用待确认，已知合计
              {{ money(budget.knownTotal) }}</small
            >
            <small v-else>按活动明细计算，不再乘人数或天数</small>
          </div>
          <div>
            <span>已确认总预算上限</span
            ><strong>{{ money(budget.limit) }}</strong>
            <small>{{
              requirement?.budgetScope === "PER_PERSON"
                ? "人均上限 × " + (requirement.travelers || "待确认") + " 人"
                : requirement?.budgetScope === "TOTAL"
                  ? "本次全体同行人总额"
                  : "预算口径尚待确认"
            }}</small>
          </div>
          <div class="dw-hero-check">
            <span>行程检查</span>
            <strong :class="{ 'dw-text-warning': blocked && !applied }">{{
              applied
                ? "已保存"
                : blocked
                  ? (issues.length || "有") + " 项待处理"
                  : "可以确认保存"
            }}</strong>
            <small>{{
              dirty ? "本页有修改，保存后更新检查结果" : "仅统计必须处理的问题"
            }}</small>
          </div>
        </div>
      </div>
    </header>

    <Transition name="motion-fade">
      <div v-if="expired || conflict || error" class="dw-alert" role="alert">
        <strong>{{
          expired
            ? "临时草稿已过期"
            : conflict
              ? "服务器草稿已更新"
              : "操作未完成"
        }}</strong>
        <p>
          {{
            expired
              ? "当前内容仍可查看，请返回需求重新生成。过期草稿无法继续保存。"
              : conflict
                ? "本页编辑仍保留。读取已存草稿会放弃本页未保存修改，请确认后再操作。"
                : userText(error)
          }}
        </p>
        <button
          v-if="conflict && !expired"
          class="dw-text-button"
          :disabled="busy"
          @click="reload"
        >
          读取已存草稿 <HomeIcon name="arrow" />
        </button>
      </div>
    </Transition>
    <div class="dw-layout">
      <aside class="dw-days dw-glass" aria-label="旅行日期">
        <div class="dw-section-top">
          <span class="dw-kicker">YOUR DAYS</span
          ><span>{{ draft.days.length }} 天旅程</span>
        </div>
        <h2>让每一天，<br />有自己的节奏。</h2>
        <nav ref="daysNav" class="dw-day-list" aria-label="选择行程日期">
          <span
            v-if="selectionReady"
            class="dw-day-selection"
            :style="daySelectionStyle"
            aria-hidden="true"
          />
          <button
            v-for="(entry, index) in draft.days"
            :key="index"
            class="dw-day-button"
            :class="{ 'is-selected': selectedDay === index }"
            :aria-current="selectedDay === index ? 'date' : undefined"
            @click="selectDay(index)"
          >
            <span class="dw-day-number"
              ><small>DAY</small>{{ String(index + 1).padStart(2, "0") }}</span
            >
            <span class="dw-day-copy"
              ><strong>{{ shortDate(entry.dayDate) }}</strong
              ><span>{{ userText(entry.dayTheme) || "当日主题待补充" }}</span>
              <small v-if="dayIssues(index)" class="dw-day-issue"
                >{{ dayIssues(index) }} 项待处理</small
              >
              <small v-else>查看当日安排</small>
            </span>
            <span class="dw-day-indicator" aria-hidden="true" />
          </button>
        </nav>
        <div class="dw-day-foot">
          <HomeIcon name="route" />
          <p>
            慢慢规划，认真出发。<br /><span
              >修改后先保存草稿，再确认成行。</span
            >
          </p>
        </div>
      </aside>

      <section
        class="dw-timeline dw-glass"
        :style="{ '--dw-day-shift': dayDirection > 0 ? '12px' : '-12px' }"
        aria-label="每日行程"
      >
        <Transition
          name="dw-day-transition"
          mode="out-in"
          @after-enter="finishPendingLocation"
        >
          <div
            v-if="day"
            :key="selectedDay"
            :data-day-index="selectedDay"
            class="dw-day-content"
          >
            <header class="dw-timeline-heading">
              <div>
                <p class="dw-kicker">
                  DAY {{ String(selectedDay + 1).padStart(2, "0") }}
                  <span>· {{ shortDate(day.dayDate) }}</span>
                </p>
                <h2 ref="dayHeading" tabindex="-1">
                  {{ userText(day.dayTheme) || "写下这一天的主题" }}
                </h2>
              </div>
              <button
                class="dw-icon-button"
                :disabled="!editable"
                aria-label="编辑当日主题和说明"
                @click="openDayEditor()"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="m15 4 5 5M4 20l5-1L20 8a3.5 3.5 0 0 0-5-5L4 14Z" />
                </svg>
              </button>
            </header>
            <p v-if="day.daySummary" class="dw-day-description">
              {{ userText(day.daySummary) }}
            </p>
            <div class="dw-day-stats">
              <span><HomeIcon name="pin" />{{ day.items.length }} 项活动</span
              ><span><HomeIcon name="clock" />按开始时间浏览</span
              ><span>{{ money(selectedTotal) }} <small>当日合计</small></span>
            </div>
            <p v-if="activeDayIssues.length" class="dw-inline-notice">
              <span class="dw-small-dot" />当天有
              {{ activeDayIssues.length }}
              项需要确认，可从右侧检查直接定位。<span v-if="dirty"
                >保存后更新。</span
              >
            </p>
            <ol v-if="day.items.length" class="dw-activities">
              <li
                v-for="(item, index) in day.items"
                :key="index"
                :data-activity-index="index"
                tabindex="-1"
                class="dw-activity"
                :class="[
                  { 'is-highlighted': highlighted.includes(index) },
                  'is-' + (activityColors[item.itemType] || 'other'),
                ]"
              >
                <div class="dw-time">
                  <time>{{ item.startTime || "待定" }}</time
                  ><span class="dw-time-dot">{{ index + 1 }}</span>
                </div>
                <article class="dw-activity-card">
                  <div class="dw-activity-title">
                    <span class="dw-type-icon"
                      ><HomeIcon :name="itemTypeIcon(item.itemType)"
                    /></span>
                    <div>
                      <span class="dw-category"
                        >{{ itemTypeLabel(item.itemType)
                        }}<span v-if="item.scheduleLocked" class="dw-locked">
                          · 时间固定</span
                        ></span
                      >
                      <h3>{{ item.itemName || "活动名称待填写" }}</h3>
                    </div>
                    <button
                      class="dw-icon-button"
                      :disabled="!editable"
                      :aria-label="'编辑' + (item.itemName || '活动')"
                      @click="openActivity(index)"
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          d="m15 4 5 5M4 20l5-1L20 8a3.5 3.5 0 0 0-5-5L4 14Z"
                        />
                      </svg>
                    </button>
                  </div>
                  <div class="dw-activity-facts">
                    <span><HomeIcon name="clock" />{{ duration(item) }}</span
                    ><span
                      >{{ activityCost(item) }} <small>全体合计</small></span
                    >
                  </div>
                  <p v-if="item.aiTips" class="dw-activity-snippet">
                    {{ userText(item.aiTips) }}
                  </p>
                  <p v-if="item.address" class="dw-address">
                    <HomeIcon name="pin" />{{ item.address }}
                  </p>
                  <details
                    v-if="item.aiTips || item.transportTips"
                    class="dw-activity-details"
                  >
                    <summary>
                      旅行建议与交通说明 <span aria-hidden="true">＋</span>
                    </summary>
                    <div>
                      <p v-if="item.aiTips">{{ userText(item.aiTips) }}</p>
                      <p v-if="item.transportTips">
                        <strong>如何前往</strong
                        >{{ userText(item.transportTips) }}
                      </p>
                    </div>
                  </details>
                </article>
              </li>
            </ol>
            <div v-else class="dw-empty-day">
              <HomeIcon name="route" />
              <h3>给这一天，留一点期待。</h3>
              <p>暂时没有活动。可以先保存草稿，补齐安排后再创建正式路书。</p>
            </div>
            <button
              class="dw-add-activity"
              :disabled="!editable"
              @click="openActivity()"
            >
              <span>＋</span>添加行程活动 <small>每一站，由你决定</small>
            </button>
            <p class="dw-estimate-note">
              时间、费用和交通建议为估算，出发前请核实实际条件。
            </p>
          </div>
          <div v-else class="dw-empty-day">
            <HomeIcon name="route" />
            <p>当前草稿没有可浏览的日期，请返回需求检查行程。</p>
          </div>
        </Transition>
      </section>

      <aside class="dw-inspection" aria-label="行程检查和预算">
        <section class="dw-check-panel dw-glass">
          <div class="dw-panel-heading">
            <h2>行程检查</h2>
            <span class="dw-count" :class="{ 'is-clear': !blocked }">{{
              issues.length
            }}</span>
          </div>
          <p class="dw-panel-intro">
            {{
              blocked
                ? "把这些小问题，留在出发之前。"
                : "必要检查已通过，下一站由你决定。"
            }}
          </p>
          <p v-if="dirty" class="dw-stale-note">
            检查结果来自已存草稿，保存后更新。
          </p>
          <ol v-if="issues.length" class="dw-issue-list">
            <li
              v-for="(issue, index) in issues"
              :key="issue.code + ':' + index"
            >
              <button @click="locateIssue(issue)">
                <span class="dw-issue-marker" aria-hidden="true">!</span>
                <span
                  ><small>{{
                    userText(issueLocation(issue)) || "整段旅程"
                  }}</small
                  ><strong>{{ userText(issue.message) }}</strong
                  ><span class="dw-issue-action"
                    >查看并处理 <HomeIcon name="arrow" /></span
                ></span>
              </button>
            </li>
          </ol>
          <p v-else-if="blocked" class="dw-inline-notice">
            草稿仍需完善，请保存修改以重新校验。
          </p>
          <div v-else class="dw-check-clear">
            <span>✓</span>
            <p>没有必须处理的问题<small>普通提醒不会阻止保存</small></p>
          </div>
        </section>

        <details
          class="dw-reference dw-glass"
          :open="warningsOpen"
          @toggle="warningsOpen = ($event.target as HTMLDetailsElement).open"
        >
          <summary>
            <span><HomeIcon name="pin" />出发前，再确认</span
            ><span>{{ warnings.length }} 条 <b aria-hidden="true">＋</b></span>
          </summary>
          <p>这些是参考提醒，不计入必须处理的问题。</p>
          <ul>
            <li v-for="(warning, index) in warnings" :key="index">
              {{ warning }}
            </li>
          </ul>
          <p v-if="!warnings.length">暂无额外提醒。</p>
        </details>

        <section
          ref="budgetPanel"
          class="dw-budget dw-glass"
          tabindex="-1"
          aria-label="预算分析"
        >
          <div class="dw-panel-heading">
            <h2>旅程预算</h2>
            <span class="dw-currency">CNY</span>
          </div>
          <p class="dw-panel-intro">所有活动费用，均为同行人合计。</p>
          <div class="dw-budget-main">
            <span>预计总花费</span><strong>{{ money(budget.total) }}</strong
            ><small v-if="budget.unknownCount"
              >{{ budget.unknownCount }} 项费用待确认 · 已知
              {{ money(budget.knownTotal) }}</small
            >
          </div>
          <div class="dw-budget-limit">
            <span>已确认总上限</span><strong>{{ money(budget.limit) }}</strong>
          </div>
          <p
            v-if="budget.over != null && budget.over > 0"
            class="dw-budget-difference is-over"
          >
            超出预算 <strong>{{ money(budget.over) }}</strong
            ><span>可调整高费用安排，或主动确认更高上限。</span>
          </p>
          <p v-else-if="budget.remaining != null" class="dw-budget-difference">
            预算余量 <strong>{{ money(budget.remaining) }}</strong>
          </p>
          <p v-else class="dw-budget-unconfirmed">
            {{ budget.comparisonNote }}。
          </p>
          <div class="dw-category-list">
            <div
              v-for="category in budget.categories"
              :key="category.type"
              class="dw-budget-category"
              :class="'is-' + (activityColors[category.type] || 'other')"
            >
              <div>
                <span
                  ><i />{{ category.label
                  }}<small v-if="category.unknownCount">
                    · {{ category.unknownCount }} 项待定</small
                  ></span
                ><strong>{{
                  category.total == null
                    ? money(category.knownTotal) + " +"
                    : money(category.total)
                }}</strong>
              </div>
              <span class="dw-category-track"
                ><i
                  :style="{
                    width:
                      (budget.knownTotal > 0
                        ? Math.min(
                            100,
                            (category.knownTotal / budget.knownTotal) * 100,
                          )
                        : 0) + '%',
                  }"
              /></span>
            </div>
          </div>
          <div v-if="budget.highCosts.length" class="dw-high-costs">
            <h3>从这些支出开始看看</h3>
            <button
              v-for="entry in budget.highCosts"
              :key="entry.dayIndex + ':' + entry.itemIndex"
              @click="locateActivity(entry.dayIndex - 1, entry.itemIndex - 1)"
            >
              <span
                ><small>DAY {{ String(entry.dayIndex).padStart(2, "0") }}</small
                >{{ entry.name || "未命名活动" }}</span
              ><strong
                >{{ money(entry.amount) }} <HomeIcon name="arrow"
              /></strong>
            </button>
          </div>
          <button
            v-if="canChangeBudget && !applied"
            class="dw-button dw-budget-change"
            :disabled="!editable || dirty || conflict"
            @click="openBudgetEditor"
          >
            主动调整预算上限 <HomeIcon name="arrow" />
          </button>
          <p class="dw-budget-footnote">
            {{
              dirty
                ? "先保存草稿修改，再调整预算上限。"
                : "修改估算需有依据，不会自动压低费用或把未知费用记为免费。"
            }}
          </p>
        </section>

        <RagSources :task="task" />
        <details v-if="corrections.length" class="dw-reference dw-glass">
          <summary>
            <span>草稿整理记录</span
            ><span
              >{{ corrections.length }} 条 <b aria-hidden="true">＋</b></span
            >
          </summary>
          <ul>
            <li v-for="(correction, index) in corrections" :key="index">
              {{ correction }}
            </li>
          </ul>
        </details>
      </aside>
    </div>

    <footer class="dw-save-bar dw-glass" aria-label="草稿保存操作">
      <div class="dw-save-state" role="status">
        <span
          class="dw-save-dot"
          :class="{ 'is-dirty': dirty || conflict || expired, 'is-busy': busy }"
        />
        <div>
          <strong>{{ savedStatus }}</strong>
          <span v-if="notice && !dirty">{{ userText(notice) }}</span
          ><span v-else-if="task.generation?.expiresAt && !expired"
            >有效至 {{ dateTimeLabel(task.generation.expiresAt) }} ·
            确认后才创建正式路书</span
          ><span v-else>当前修改仅属于临时草稿</span>
        </div>
      </div>
      <div class="dw-save-actions">
        <button
          v-if="conflict"
          class="dw-button dw-quiet"
          :disabled="busy || !!dialog"
          @click="reload"
        >
          读取已存草稿
        </button>
        <button
          v-if="!applied"
          class="dw-button dw-quiet"
          :disabled="busy || expired || conflict || !dirty || !!dialog"
          @click="emit('save-draft')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 3h12l4 4v14H3V3Zm2 0v6h10V3M7 21v-8h10v8" /></svg
          >{{ busy ? "处理中…" : "保存草稿修改" }}
        </button>
        <button
          v-if="applied"
          class="dw-button dw-primary"
          :disabled="busy || !!dialog"
          @click="emit('open-saved')"
        >
          打开已保存路书 <HomeIcon name="arrow" />
        </button>
        <button
          v-else
          class="dw-button dw-primary"
          :disabled="
            busy ||
            expired ||
            conflict ||
            dirty ||
            blocked ||
            !title.trim() ||
            !!dialog
          "
          @click="emit('apply')"
        >
          确认保存正式路书 <HomeIcon name="arrow" />
        </button>
      </div>
    </footer>

    <Teleport to="body">
      <Transition name="dw-dialog-transition">
        <div
          v-if="dialog"
          class="trip-draft-dialog"
          @keydown="dialogKeys"
          @click.self="closeDialog()"
        >
          <section
            ref="dialogPanel"
            class="dw-editor dw-glass"
            role="dialog"
            aria-modal="true"
            aria-labelledby="dw-editor-title"
            tabindex="-1"
          >
            <header class="dw-editor-heading">
              <div>
                <p class="dw-kicker">
                  {{
                    dialog === "budget"
                      ? "YOUR BUDGET, YOUR CHOICE"
                      : "MAKE IT YOUR JOURNEY"
                  }}
                </p>
                <h2 id="dw-editor-title">{{ dialogTitle }}</h2>
              </div>
              <button
                class="dw-icon-button"
                aria-label="关闭编辑抽屉"
                @click="closeDialog()"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="m6 6 12 12M18 6 6 18" />
                </svg>
              </button>
            </header>
            <form class="dw-editor-form" @submit.prevent="commitEditor">
              <div class="dw-editor-scroll">
                <template v-if="dialog === 'activity' && activityBuffer">
                  <p class="dw-editor-intro">
                    DAY {{ String(selectedDay + 1).padStart(2, "0") }} ·
                    {{ shortDate(day?.dayDate)
                    }}<br />每一项安排，都是旅程的一部分。
                  </p>
                  <label class="dw-field"
                    >活动名称<input
                      v-model="activityBuffer.itemName"
                      data-edit-field="itemName"
                      data-autofocus
                      maxlength="100"
                      required
                      placeholder="写下这一站的名字"
                  /></label>
                  <div class="dw-field-grid">
                    <label class="dw-field"
                      >活动类型<select
                        v-model="activityBuffer.itemType"
                        data-edit-field="itemType"
                      >
                        <option
                          v-if="
                            ![
                              'SCENIC',
                              'FOOD',
                              'HOTEL',
                              'TRAFFIC',
                              'OTHER',
                            ].includes(activityBuffer.itemType)
                          "
                          :value="activityBuffer.itemType"
                          disabled
                        >
                          请选择活动类型
                        </option>
                        <option value="SCENIC">景点游览</option>
                        <option value="FOOD">餐饮</option>
                        <option value="HOTEL">住宿</option>
                        <option value="TRAFFIC">交通</option>
                        <option value="OTHER">其他体验</option>
                      </select></label
                    >
                    <label class="dw-field"
                      >开始时间<input
                        v-model="activityBuffer.startTime"
                        data-edit-field="startTime"
                        type="time"
                      /><small>暂不确定，可以留空</small></label
                    >
                    <label class="dw-field"
                      >预计时长 · 小时<input
                        v-model.number="activityBuffer.costTimeHours"
                        data-edit-field="costTimeHours"
                        type="number"
                        min="0.1"
                        max="24"
                        step="0.1"
                        placeholder="时长待确认"
                    /></label>
                    <label class="dw-field"
                      >预计费用 · 人民币<input
                        v-model.number="activityBuffer.costEstimate"
                        data-edit-field="costEstimate"
                        type="number"
                        min="0"
                        max="9999999"
                        step="0.01"
                        placeholder="费用未知请留空"
                      /><small>单次活动全体合计，确认免费才填 0</small></label
                    >
                  </div>
                  <label class="dw-check-field"
                    ><input
                      v-model="activityBuffer.scheduleLocked"
                      type="checkbox"
                    /><span
                      >固定此活动时间<small
                        >校验会保留你的安排，不会悄悄挪动。</small
                      ></span
                    ></label
                  >
                  <label class="dw-field"
                    >地点说明<input
                      v-model="activityBuffer.address"
                      maxlength="200"
                      placeholder="可补充地址或集合地点"
                  /></label>
                  <details class="dw-editor-more">
                    <summary>旅行建议与交通说明 <span>＋</span></summary>
                    <label class="dw-field"
                      >旅行建议<textarea
                        v-model="activityBuffer.aiTips"
                        rows="3"
                        maxlength="500"
                        placeholder="需要记住的事"
                      />
                    </label>
                    <label class="dw-field"
                      >交通说明<textarea
                        v-model="activityBuffer.transportTips"
                        rows="3"
                        maxlength="255"
                        placeholder="如何前往，哪些信息仍需核实"
                      />
                    </label>
                  </details>
                  <p class="dw-editor-note">
                    保留未知比填写一个没有依据的数字更可靠。应用后请保存草稿，重新检查时间与预算。
                  </p>
                </template>

                <template v-else-if="dialog === 'trip'">
                  <p class="dw-editor-intro">
                    为下一段旅程，写下属于你的名字。<br />草稿信息仍需符合此前确认的旅行需求。
                  </p>
                  <label class="dw-field"
                    >路书标题<input
                      v-model="metadata.title"
                      data-edit-field="title"
                      data-autofocus
                      maxlength="100"
                      required
                  /></label>
                  <div class="dw-field-grid">
                    <label class="dw-field"
                      >目的地<input
                        v-model="metadata.destination"
                        data-edit-field="destination"
                        maxlength="100"
                        placeholder="请输入实际目的地"
                    /></label>
                    <label class="dw-field"
                      >出发日期<input
                        v-model="metadata.startDate"
                        data-edit-field="startDate"
                        type="date"
                      /><small>日期尚待确认时可以清空</small></label
                    >
                  </div>
                  <label class="dw-field"
                    >旅程概要<textarea
                      v-model="metadata.summary"
                      data-edit-field="summary"
                      rows="5"
                      maxlength="500"
                      placeholder="想怎样度过这段旅程？"
                    />
                  </label>
                  <p class="dw-editor-note">
                    此处修改草稿本身，不会改写你已确认的目的地、日期或其他硬性需求。
                  </p>
                </template>

                <template v-else-if="dialog === 'day'">
                  <p class="dw-editor-intro">
                    DAY {{ String(selectedDay + 1).padStart(2, "0") }} ·
                    {{ shortDate(day?.dayDate) }}
                  </p>
                  <label class="dw-field"
                    >当日主题<input
                      v-model="metadata.dayTheme"
                      data-edit-field="dayTheme"
                      data-autofocus
                      maxlength="100"
                      placeholder="给这一天起个名字"
                  /></label>
                  <label class="dw-field"
                    >当日说明<textarea
                      v-model="metadata.daySummary"
                      rows="5"
                      maxlength="5000"
                      placeholder="为这一天留下几句安排"
                    />
                  </label>
                </template>

                <template v-else-if="dialog === 'budget'">
                  <p class="dw-editor-intro">
                    你可以调整安排，也可以在充分了解支出的前提下，主动提高本次预算。
                  </p>
                  <div class="dw-budget-confirm-summary">
                    <div>
                      <span>预算口径</span
                      ><strong>{{
                        requirement?.budgetScope === "PER_PERSON"
                          ? "人均上限"
                          : "全体合计上限"
                      }}</strong>
                    </div>
                    <div>
                      <span>同行人数</span
                      ><strong>{{
                        requirement?.travelers
                          ? requirement.travelers + " 人"
                          : "尚未确认"
                      }}</strong>
                    </div>
                    <div><span>币种</span><strong>人民币 · CNY</strong></div>
                    <div>
                      <span>当前{{ scopeLabel }}</span
                      ><strong>{{
                        money(
                          requirement?.budgetMax ?? requirement?.budget ?? null,
                        )
                      }}</strong>
                    </div>
                  </div>
                  <label class="dw-field"
                    >新的{{ scopeLabel }} · 人民币<input
                      v-model.number="proposedBudget"
                      data-autofocus
                      type="number"
                      min="0"
                      max="9999999"
                      step="0.01"
                      required
                  /></label>
                  <div class="dw-proposed-total">
                    <span>调整后的全体总上限</span
                    ><strong>{{
                      proposedBudget === ""
                        ? "待填写"
                        : money(
                            Number(proposedBudget) *
                              (requirement?.budgetScope === "PER_PERSON"
                                ? requirement.travelers || 0
                                : 1),
                          )
                    }}</strong>
                  </div>
                  <label class="dw-check-field"
                    ><input
                      v-model="budgetConfirmed"
                      type="checkbox"
                      required
                    /><span
                      >我明确同意提高本次草稿的预算上限<small
                        >此操作会更新后端保存的需求约束并重新校验，不会修改活动费用，也不会调用
                        AI。</small
                      ></span
                    ></label
                  >
                  <p v-if="dirty || conflict" class="dw-editor-error">
                    请先保存本页草稿并处理版本冲突，再调整预算上限。
                  </p>
                  <p class="dw-editor-note">
                    旅行天数、同行人数、预算口径和币种保持原确认值。请求结果不明时重试会沿用同一请求编号。
                  </p>
                </template>
                <Transition name="motion-fade">
                  <p v-if="dialogError" class="dw-editor-error" role="alert">
                    {{ userText(dialogError) }}
                  </p>
                </Transition>
              </div>
              <footer class="dw-editor-footer">
                <button
                  v-if="dialog === 'activity' && editingIndex >= 0"
                  class="dw-text-button dw-delete"
                  type="button"
                  :disabled="!editable"
                  @click="deleteActivity"
                >
                  删除活动
                </button>
                <div>
                  <button
                    class="dw-button dw-quiet"
                    type="button"
                    @click="closeDialog()"
                  >
                    取消</button
                  ><button
                    class="dw-button dw-primary"
                    type="submit"
                    :disabled="
                      !editable || (dialog === 'budget' && (dirty || conflict))
                    "
                  >
                    {{ dialog === "budget" ? "确认提高预算" : "应用到草稿"
                    }}<HomeIcon name="arrow" />
                  </button>
                </div>
              </footer>
            </form>
          </section>
        </div>
      </Transition>
    </Teleport>
  </main>
</template>
