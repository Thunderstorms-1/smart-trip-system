<script setup lang="ts">
import {
  computed,
  ref,
  reactive,
  onBeforeUnmount,
  onMounted,
  nextTick,
  watch,
} from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { api, ApiError, requestKey } from "./api";
import { followTask } from "./task-stream";
import { useSession } from "./session";
import { useAction } from "./ui";
import type { Trip, Task, TravelRequirement, RequirementReview } from "./types";
import HomeIcon from "./HomeIcon.vue";
import TripDraftWorkspace from "./TripDraftWorkspace.vue";
import AiGenerationProgress from "./AiGenerationProgress.vue";
import JourneyFlowBackground from "./JourneyFlowBackground.vue";
import { userText, type EditableTrip } from "./trip-draft-presentation";
import "./generate-workspace-shell.css";
import "./generate-v3.css";
const route = useRoute(),
  router = useRouter(),
  s = useSession(),
  mode = ref(route.query.mode === "manual" ? "manual" : "ai"),
  form = reactive({
    title: "",
    destination: "",
    startDate: "",
    totalDays: 3,
    tripStyle: "城市漫步",
    travelGroup: "朋友同行",
    requirements: "",
  }),
  task = ref<Task>(),
  { busy, error, run } = useAction();
const submissionPhase = ref("");
const submittingGeneration = ref(false),
  submissionStartedAt = ref<number>(),
  taskReconnecting = ref(false);
let reconnectAttempts = 0;
const avatarFailed = ref(false),
  entranceKey = ref(0);
function replayEntrance(event: PageTransitionEvent) {
  if (
    event.persisted &&
    !matchMedia("(prefers-reduced-motion: reduce)").matches
  )
    entranceKey.value++;
  if (event.persisted) void refreshAiCapabilities();
}
function logout() {
  s.logout();
  router.push("/");
}
const manualDirection = ref("planning-stage-forward");
const step = ref(1),
  builderForm = ref<HTMLFormElement>(),
  destinationInput = ref<HTMLInputElement>(),
  daysInput = ref<HTMLInputElement>();
watch(step, async (value, previous) => {
  manualDirection.value =
    value < previous ? "planning-stage-back" : "planning-stage-forward";
  await nextTick();
  const heading = builderForm.value?.querySelector<HTMLElement>(
    '[data-stage="' + step.value + '"] h2',
  );
  heading?.focus({ preventScroll: true });
  heading?.scrollIntoView({ block: "nearest", behavior: "instant" });
});
const running = computed(() =>
  Boolean(task.value && ["QUEUED", "RUNNING"].includes(task.value.status)),
);
const creating = computed(
  () => busy.value || running.value || !!pending.value || recovering.value,
);
function advance() {
  if (creating.value) return;
  if (step.value === 1 && !destinationInput.value?.reportValidity()) return;
  if (step.value === 2 && !daysInput.value?.reportValidity()) return;
  if (step.value < 3) step.value++;
  else {
    if (!destinationInput.value?.checkValidity()) {
      step.value = 1;
      return;
    }
    if (!daysInput.value?.checkValidity()) {
      step.value = 2;
      return;
    }
    if (builderForm.value?.reportValidity()) submit();
  }
}
function submit() {
  run(async () => {
    const trip: Trip = {
      title: form.title || form.destination + "的旅行",
      destination: form.destination,
      startDate: form.startDate || undefined,
      tripStyle: form.tripStyle,
      travelGroup: form.travelGroup,
      summary: form.requirements,
      days: Array.from({ length: form.totalDays }, (_, i) => ({
        dayTheme: "第" + (i + 1) + "天 · 自由探索",
        items: [],
      })),
    };
    const t = await api<Trip>("/workbench", "POST", trip);
    router.push("/trip/edit/" + t.id);
  });
}
function aiUnavailableReason() {
  if (s.capabilities.aiReason) return s.capabilities.aiReason;
  switch (s.capabilities.status?.ai) {
    case "QUOTA_UNVERIFIED":
      return "AI 免费额度或模型权限尚未确认。确认后再启用，避免意外计费。";
    case "KEY_MISSING":
      return "AI Key 尚未配置。";
    case "DISABLED":
      return "AI 服务当前处于关闭状态。";
    case "CONFIG_INVALID":
      return "AI 模型配置不完整或格式无效。";
    default:
      return "AI 服务暂未启用。";
  }
}
function aiAction() {
  if (s.capabilities.aiAction) return s.capabilities.aiAction;
  return "请检查 AI 开关、模型名称、接口地址与 Key；更新服务配置后重新检查。本地规则整理和手动创建仍可使用。";
}
async function refreshAiCapabilities() {
  await s.loadCapabilities(true).catch(() => {});
}
function refreshOnVisibility() {
  if (document.visibilityState === "visible") void refreshAiCapabilities();
}
async function requireAi() {
  submissionPhase.value = "正在检查 AI 配置…";
  try {
    await s.loadCapabilities(true);
    if (!s.capabilities.ai)
      throw new Error(aiUnavailableReason() + " " + aiAction());
  } finally {
    submissionPhase.value = "";
  }
}
function emptyRequirement(): TravelRequirement {
  return {
    description: "",
    origin: "",
    destinations: [],
    days: null,
    startDate: null,
    timeDescription: "",
    includesTravelDays: null,
    travelers: null,
    companions: "",
    budget: null,
    budgetMax: null,
    budgetScope: "UNKNOWN",
    currency: "",
    budgetIncludes: [],
    interests: [],
    pace: "",
    exertion: "",
    transport: "",
    accommodation: "",
    mustVisit: [],
    avoid: [],
    timeConstraints: "",
    notes: "",
  };
}
function hydrateRequirement(
  value: Partial<TravelRequirement>,
): TravelRequirement {
  return Object.assign(
    emptyRequirement(),
    Object.fromEntries(Object.entries(value).filter(([, v]) => v != null)),
  );
}
const reviewedOnce = ref(false);
const editedFields = ref<(keyof TravelRequirement)[]>([]);
const requirement = ref<TravelRequirement>(emptyRequirement()),
  review = ref<RequirementReview>(),
  aiStage = ref<"input" | "confirm" | "preview">("input"),
  conflictsAccepted = ref(false),
  previewTitle = ref(""),
  now = ref(Date.now()),
  expiredRemotely = ref(false);
interface PendingGeneration {
  requestKey: string;
  requirement: TravelRequirement;
  kind?: "GENERATE" | "INTERPRET";
  editedFields?: (keyof TravelRequirement)[];
  reviewedOnce?: boolean;
  submittedAt?: number;
}
const pending = ref<PendingGeneration>(),
  recovering = ref(false),
  recoveryTaskId = ref("");
const aiRegion = ref<HTMLElement>();
const currencyNeedsReview = computed(() =>
  Boolean(
    review.value &&
      (review.value.requirement.currency || "") !== requirement.value.currency,
  ),
);
function focusAiStage(element?: Element) {
  const region = element instanceof HTMLElement ? element : aiRegion.value;
  region?.querySelector<HTMLElement>("h2")?.focus({ preventScroll: true });
}
function focusPlanningScene(element: Element) {
  if (!(element instanceof HTMLElement)) return;
  if (element.matches(".workspace-host, .generation-feedback"))
    element.focus({ preventScroll: true });
  else if (mode.value === "ai") focusAiStage();
  else
    element
      .querySelector<HTMLElement>('[data-stage="' + step.value + '"] h2')
      ?.focus({ preventScroll: true });
}
const expired = computed(
  () =>
    expiredRemotely.value ||
    Boolean(
      task.value?.generation?.expiresAt &&
        Date.parse(task.value.generation.expiresAt) <= now.value,
    ),
);
const editableDraft = ref<EditableTrip>(),
  draftBaseline = ref(""),
  draftConflict = ref(false),
  draftNotice = ref("");
const preview = computed(() => editableDraft.value);
const workspaceVisible = computed(() =>
  Boolean(
    mode.value === "ai" &&
      preview.value &&
      task.value?.type === "GENERATE" &&
      ["PREVIEW", "APPLIED"].includes(task.value.status),
  ),
);
const progressVisible = computed(() =>
  Boolean(
    mode.value === "ai" &&
      !workspaceVisible.value &&
      (submittingGeneration.value ||
        (pending.value && pending.value.kind !== "INTERPRET") ||
        (recoveryTaskId.value &&
          (recovering.value || taskReconnecting.value) &&
          task.value?.type !== "INTERPRET") ||
        (task.value?.type === "GENERATE" &&
          ["QUEUED", "RUNNING", "FAILED"].includes(task.value.status))),
  ),
);
const draftRevision = computed(
  () => task.value?.generation?.draftRevision ?? 0,
);
const draftIssues = computed(() => task.value?.generation?.issues ?? []);
const draftSaveBlocked = computed(
  () =>
    task.value?.generation?.saveBlocked === true ||
    draftIssues.value.length > 0,
);
const taskPhaseLabel = computed(() => {
  const phase = task.value?.phase;
  if (phase === "RETRIEVING") return "正在检索旅行参考资料";
  if (phase === "NORMALIZING") return "正在整理模型草稿";
  if (phase === "VALIDATING") return "正在校验时间、费用与需求";
  if (phase === "MODEL")
    return task.value?.type === "INTERPRET"
      ? "正在智能理解需求"
      : "正在生成草稿";
  return task.value?.type === "INTERPRET" ? "正在智能理解需求" : "正在生成草稿";
});
const draftDirty = computed(() =>
  Boolean(
    editableDraft.value &&
      JSON.stringify({ ...editableDraft.value, title: previewTitle.value }) !==
        draftBaseline.value,
  ),
);
const draftTotal = computed(() => {
  if (!preview.value) return null;
  let total = 0;
  for (const day of preview.value.days)
    for (const item of day.items) {
      if (
        item.costEstimate == null ||
        item.costEstimate === "" ||
        !Number.isFinite(Number(item.costEstimate))
      )
        return null;
      total += Number(item.costEstimate);
    }
  return total;
});
function setEditableDraft(value: Trip) {
  const draft = JSON.parse(JSON.stringify(value)) as EditableTrip;
  // Incomplete required text remains editable, rather than throwing before the server can report it.
  draft.title = value.title ?? "";
  draft.destination = value.destination ?? "";
  for (const day of draft.days) {
    day.dayTheme ??= "";
    for (const item of day.items) {
      item.itemName ??= "";
      if (item.costEstimate == null) item.costEstimate = "";
      if (item.costTimeHours == null) item.costTimeHours = "";
    }
  }
  editableDraft.value = draft;
  previewTitle.value = draft.title;
  draftBaseline.value = JSON.stringify({
    ...editableDraft.value,
    title: previewTitle.value,
  });
  draftConflict.value = false;
  draftNotice.value = "";
}
function clearEditableDraft() {
  editableDraft.value = undefined;
  draftBaseline.value = "";
  draftConflict.value = false;
  draftNotice.value = "";
}
function draftPayload() {
  const draft = editableDraft.value;
  if (!draft) throw new Error("没有可编辑的草稿。");
  const title = previewTitle.value.trim();
  if (!title || title.length > 100)
    throw new Error("请填写 1–100 字的路书标题。");
  const numberOrNull = (value: number | "" | null | undefined) =>
    value == null || value === "" || !Number.isFinite(Number(value))
      ? null
      : Number(value);
  return {
    ...draft,
    title,
    startDate: draft.startDate || null,
    totalBudget: draftTotal.value,
    days: draft.days.map((day) => ({
      ...day,
      items: day.items.map((item) => ({
        ...item,
        itemName: item.itemName.trim(),
        startTime: item.startTime || null,
        costTimeHours: numberOrNull(item.costTimeHours),
        costEstimate: numberOrNull(item.costEstimate),
      })),
    })),
  };
}
function beforeDraftUnload(event: BeforeUnloadEvent) {
  if (draftDirty.value) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onBeforeRouteLeave(
  () =>
    !draftDirty.value ||
    window.confirm("草稿修改尚未保存，离开会丢失本页修改。确定离开吗？"),
);
function amount(value: number, currency = requirement.value.currency) {
  if (!currency)
    return (
      new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(
        value,
      ) + "（币种待确认）"
    );
  try {
    return new Intl.NumberFormat("zh-CN", {
      style: "currency",
      currency,
      maximumFractionDigits: 2,
    }).format(value);
  } catch {
    return value + " " + currency;
  }
}
const listFields = [
  {
    key: "budgetIncludes",
    label: "预算包含",
    placeholder: "例如 往返交通、住宿、餐饮",
  },
  { key: "interests", label: "兴趣偏好", placeholder: "例如 摄影、自然、人文" },
  {
    key: "mustVisit",
    label: "必须去 / 必须体验",
    placeholder: "多个地点或项目用顿号分隔",
  },
  {
    key: "avoid",
    label: "不接受的地点或安排",
    placeholder: "例如 夜间赶路、高强度徒步",
  },
] as const;
const extraListFields = listFields.filter((field) => field.key !== "interests");
const interestOptions = [
  "自然风光",
  "城市探索",
  "美食体验",
  "历史文化",
  "休闲放松",
  "摄影打卡",
];
const interestIconPaths: Record<string, string> = {
  自然风光: "m2 19 6-12 5 9 3-6 6 9H2ZM7 9l2 4 2-2",
  城市探索:
    "M4 21V8h7v13m0-18h9v18M7 11v1m0 3v1m7-9v1m3-1v1m-3 3v1m3-1v1m-3 3v1m3-1v1M2 21h20",
  美食体验: "M5 3v6m3-6v6M2 3v6a3 3 0 0 0 6 0m-3 3v9M17 3v18m0-18c-4 3-4 8 0 8",
  历史文化: "m3 8 9-5 9 5H3Zm1 12h16M6 10v8m6-8v8m6-8v8M2 22h20",
  休闲放松:
    "M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  摄影打卡: "M8 5l2-2h4l2 2h5v15H3V5h5Zm8 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
};
function toggleInterest(interest: string) {
  if (creating.value) return;
  markEdited("interests");
  const current = requirement.value.interests;
  requirement.value.interests = current.includes(interest)
    ? current.filter((value) => value !== interest)
    : [...current, interest];
}
const flowStage = computed<"input" | "confirm" | "generating" | "preview">(
  () =>
    workspaceVisible.value
      ? "preview"
      : progressVisible.value
        ? "generating"
        : (mode.value === "ai" && aiStage.value === "confirm") ||
            (mode.value === "manual" && step.value === 3)
          ? "confirm"
          : "input",
);
const journeyGlance = computed(() => {
  const ai = mode.value === "ai",
    r = requirement.value;
  const destination = ai ? r.destinations.join("、") : form.destination;
  const days = ai ? r.days : form.totalDays;
  const date = ai ? r.startDate || r.timeDescription : form.startDate;
  const budget =
    ai && (r.budget != null || r.budgetMax != null)
      ? (r.budgetScope === "PER_PERSON"
          ? "人均 · "
          : r.budgetScope === "TOTAL"
            ? "整体 · "
            : "口径待确认 · ") +
        (r.budget != null ? amount(r.budget) : "") +
        (r.budgetMax != null ? " — " + amount(r.budgetMax) : "")
      : "";
  return [
    { key: "destination", label: "目的地", icon: "pin", value: destination },
    {
      key: "time",
      label: "旅行时间",
      icon: "clock",
      value: [days ? days + " 天" : "", date].filter(Boolean).join(" · "),
    },
    { key: "budget", label: "预算范围", icon: "budget", value: budget },
    {
      key: "style",
      label: "旅行偏好",
      icon: "route",
      value: ai ? r.interests.slice(0, 2).join(" · ") : form.tripStyle,
    },
  ];
});
function freezeModeForm(element: Element) {
  if (!(element instanceof HTMLElement)) return;
  element.inert = true;
  element.setAttribute("aria-hidden", "true");
}
function activateModeForm(element: Element) {
  if (!(element instanceof HTMLElement)) return;
  element.inert = false;
  element.removeAttribute("aria-hidden");
}
function focusModeForm(element: Element) {
  const target = Array.from(
    element.querySelectorAll<HTMLElement>("h2, textarea"),
  ).find(
    (node) => node.getClientRects().length > 0 && !node.closest("[inert]"),
  );
  target?.focus({ preventScroll: true });
}
function setList(
  key: "destinations" | (typeof listFields)[number]["key"],
  value: string,
) {
  markEdited(key);
  requirement.value[key] = value
    .split(/[、，,;；\n]+/)
    .map((x) => x.trim())
    .filter(Boolean);
}
function markEdited(key: keyof TravelRequirement) {
  if (creating.value) return;
  if (!editedFields.value.includes(key)) editedFields.value.push(key);
  review.value = undefined;
  conflictsAccepted.value = false;
  error.value = "";
  if (task.value && !running.value) {
    stopFollowing();
    removeStored("TASK");
    task.value = undefined;
    recoveryTaskId.value = "";
  }
}
function requirementEdited(event: Event) {
  if (creating.value) return;
  const key = (event.target as HTMLElement).dataset.requirement as
    | keyof TravelRequirement
    | undefined;
  if (key) markEdited(key);
  if (
    (key === "budget" || key === "budgetMax") &&
    (event.target as HTMLInputElement).value.trim() !== ""
  ) {
    markEdited("currency");
    requirement.value.currency = "CNY";
  }
}
function useRenminbi() {
  markEdited("currency");
  requirement.value.currency = "CNY";
}
function confirmRenminbi() {
  if (creating.value) return;
  const previousReview = review.value;
  useRenminbi();
  review.value = previousReview;
  void reviewRequirements();
}
function fieldValue(event: Event) {
  return (event.target as HTMLInputElement).value;
}
const requirementProblems = computed(() => {
  const r = requirement.value,
    problems: string[] = [];
  if (!r.destinations.length) problems.push("请确认至少一个目的地。");
  if (!Number.isInteger(r.days) || !r.days || r.days < 1 || r.days > 30)
    problems.push("请确认 1–30 天的旅行天数。");
  if ((r.budget != null || r.budgetMax != null) && r.budgetScope === "UNKNOWN")
    problems.push("请确认预算是人均还是整体预算。");
  if (
    (r.budget != null || r.budgetMax != null) &&
    r.budgetScope === "PER_PERSON" &&
    !r.travelers
  )
    problems.push("人均预算需要确认出行人数，才能对比路书整体费用。");
  if ((r.budget != null || r.budgetMax != null) && r.currency !== "CNY")
    problems.push("请确认预算币种；当前仅支持人民币，外币需先自行换算。");
  return problems;
});
let stream = new AbortController(),
  timer: ReturnType<typeof setTimeout> | undefined,
  expiryTimer: ReturnType<typeof setInterval> | undefined,
  epoch = 0;
function stopFollowing() {
  epoch++;
  clearTimeout(timer);
  stream.abort();
  stream = new AbortController();
  reconnectAttempts = 0;
  taskReconnecting.value = false;
}
function storageKey(
  kind: "TASK" | "GENERATION_REQUEST" | "TASK_CONTEXT",
  owner = s.profile?.id,
) {
  return "SMARTTRIP_" + kind + "_" + owner;
}
function removeStored(
  kind: "TASK" | "GENERATION_REQUEST",
  owner = s.profile?.id,
) {
  try {
    localStorage.removeItem(storageKey(kind, owner));
    if (kind === "TASK")
      localStorage.removeItem(storageKey("TASK_CONTEXT", owner));
  } catch {}
}
function snapshot(): TravelRequirement {
  const r = JSON.parse(JSON.stringify(requirement.value)) as TravelRequirement;
  for (const key of ["days", "travelers", "budget", "budgetMax"] as const) {
    const value = r[key];
    r[key] = value === null || String(value) === "" ? null : Number(value);
  }
  r.startDate ||= null;
  return r;
}
function current(token: string, version: number) {
  return token === s.token && version === epoch;
}
function applyReview(value: RequirementReview) {
  requirement.value = hydrateRequirement(value.requirement);
  reviewedOnce.value = true;
  review.value = { ...value, clarifications: value.clarifications.slice(0, 3) };
  conflictsAccepted.value = false;
  aiStage.value = "confirm";
}
function acceptTask(t: Task, replaceEditedDraft = false) {
  const existing = task.value;
  if (existing?.id === t.id) {
    if (existing.status === "APPLIED" && t.status !== "APPLIED") return;
    if (
      existing.type === "GENERATE" &&
      t.type === "GENERATE" &&
      (t.generation?.draftRevision ?? 0) <
        (existing.generation?.draftRevision ?? 0)
    )
      return;
  }
  if (!replaceEditedDraft && task.value?.id === t.id && draftDirty.value) {
    if (
      t.status !== "PREVIEW" ||
      (t.generation?.draftRevision ?? 0) !== draftRevision.value
    )
      draftConflict.value = true;
    return;
  }
  if (t.type !== "GENERATE" && t.type !== "INTERPRET")
    throw new Error("恢复的任务不是规划或需求理解任务。");
  if (
    task.value?.id === t.id &&
    !["QUEUED", "RUNNING"].includes(task.value.status) &&
    ["QUEUED", "RUNNING"].includes(t.status)
  )
    return;
  if (!["QUEUED", "RUNNING"].includes(t.status)) clearTimeout(timer);
  recoveryTaskId.value = t.id;
  task.value = t;
  pending.value = undefined;
  if (t.type === "INTERPRET" && t.interpretationContext) {
    requirement.value = hydrateRequirement(t.interpretationContext.requirement);
    editedFields.value = [...t.interpretationContext.editedFields];
  }
  if (t.type === "INTERPRET") {
    if (t.status === "SUCCEEDED") {
      if (!t.interpretation)
        throw new Error("需求理解结果缺失，请重新整理需求。");
      applyReview(t.interpretation);
    } else aiStage.value = "input";
  } else {
    if (t.generation?.requirement) {
      requirement.value = hydrateRequirement(t.generation.requirement);
      reviewedOnce.value = true;
    }
    if (["PREVIEW", "APPLIED"].includes(t.status)) {
      aiStage.value = "preview";
      if (t.result) setEditableDraft(t.result);
    } else if (["QUEUED", "RUNNING"].includes(t.status))
      aiStage.value = "confirm";
    else if (t.status === "SUCCEEDED" && t.tripId) {
      removeStored("TASK");
      removeStored("GENERATION_REQUEST");
      router.push("/trip/edit/" + t.tripId);
      return;
    }
  }
  try {
    localStorage.setItem(storageKey("TASK"), t.id);
    localStorage.setItem(
      storageKey("TASK_CONTEXT"),
      JSON.stringify({
        id: t.id,
        requirement: snapshot(),
        editedFields: editedFields.value,
        reviewedOnce: reviewedOnce.value,
      }),
    );
    removeStored("GENERATION_REQUEST");
  } catch {
    error.value =
      "已收到任务结果，但本机记录无法写入。请保留当前页面继续操作；刷新后可重试同一请求恢复。";
  }
  if (t.status === "FAILED")
    error.value =
      t.errorMessage ||
      (t.type === "INTERPRET"
        ? "智能理解失败，已保留输入；可以使用规则整理或重试。"
        : "生成失败，请修改需求或重试。");
  if (t.status === "FAILED") void refreshAiCapabilities();
}
async function poll(id: string, version = epoch, token = s.token) {
  clearTimeout(timer);
  const signal = stream.signal;
  try {
    const t = await api<Task>(
      "/ai/tasks/" + encodeURIComponent(id),
      "GET",
      undefined,
      signal,
    );
    if (!current(token, version) || signal.aborted) return;
    if (taskReconnecting.value) error.value = "";
    acceptTask(t);
    reconnectAttempts = 0;
    taskReconnecting.value = false;
    if (task.value?.id === id && running.value)
      timer = setTimeout(() => poll(id, version, token), 1500);
  } catch (e) {
    if (!current(token, version) || signal.aborted) return;
    error.value = e instanceof Error ? e.message : "任务恢复失败，请重试。";
    if (e instanceof ApiError && [401, 403, 404, 410].includes(e.status)) {
      removeStored("TASK");
      recoveryTaskId.value = "";
      taskReconnecting.value = false;
    } else if (
      (running.value || (recoveryTaskId.value === id && !task.value)) &&
      reconnectAttempts < 6 &&
      e instanceof ApiError &&
      (e.status === 0 ||
        e.status === 408 ||
        e.status === 429 ||
        e.status >= 500)
    ) {
      taskReconnecting.value = true;
      reconnectAttempts++;
      timer = setTimeout(
        () => poll(id, version, token),
        Math.min(10000, 1000 * 2 ** reconnectAttempts),
      );
    }
  }
}
function follow(id: string) {
  const version = epoch,
    token = s.token,
    signal = stream.signal;
  followTask(
    id,
    (t) => {
      if (!current(token, version) || signal.aborted) return;
      if (taskReconnecting.value) error.value = "";
      taskReconnecting.value = false;
      reconnectAttempts = 0;
      acceptTask(t);
      if (task.value?.id === id && !running.value) {
        stream.abort();
        stream = new AbortController();
      }
    },
    signal,
  )
    .then(() => {
      if (!signal.aborted && current(token, version) && running.value) {
        taskReconnecting.value = true;
        poll(id, version, token);
      }
    })
    .catch(() => {
      if (!signal.aborted && current(token, version)) {
        taskReconnecting.value = true;
        poll(id, version, token);
      }
    });
}
async function restore() {
  const token = s.token,
    version = epoch;
  recovering.value = true;
  try {
    const id = localStorage.getItem(storageKey("TASK"));
    const value = localStorage.getItem(storageKey("GENERATION_REQUEST"));
    const context = localStorage.getItem(storageKey("TASK_CONTEXT"));
    if (id && context) {
      try {
        const saved = JSON.parse(context);
        if (saved.id === id && saved.requirement) {
          requirement.value = hydrateRequirement(saved.requirement);
          editedFields.value = Array.isArray(saved.editedFields)
            ? saved.editedFields
            : [];
          reviewedOnce.value = !!saved.reviewedOnce;
        }
      } catch {
        /* Old task records remain recoverable without local context. */
      }
    }
    if (value) {
      try {
        const saved = JSON.parse(value) as PendingGeneration;
        if (!saved.requestKey || !saved.requirement) throw new Error();
        pending.value = saved;
        submissionStartedAt.value = saved.submittedAt;
        requirement.value = hydrateRequirement(saved.requirement);
        editedFields.value = saved.editedFields || [];
        reviewedOnce.value = saved.reviewedOnce ?? saved.kind !== "INTERPRET";
        mode.value = "ai";
        aiStage.value = saved.kind === "INTERPRET" ? "input" : "confirm";
      } catch {
        removeStored("GENERATION_REQUEST");
      }
    }
    if (id && !pending.value) {
      mode.value = "ai";
      recoveryTaskId.value = id;
      await poll(id, version, token);
      if (current(token, version) && running.value) {
        clearTimeout(timer);
        follow(id);
      }
    }
  } finally {
    if (current(token, version)) recovering.value = false;
  }
}
watch(
  () => [s.token, s.profile?.id] as const,
  ([token, id], previous) => {
    stopFollowing();
    task.value = undefined;
    pending.value = undefined;
    recovering.value = false;
    recoveryTaskId.value = "";
    review.value = undefined;
    requirement.value = emptyRequirement();
    reviewedOnce.value = false;
    editedFields.value = [];
    previewTitle.value = "";
    clearEditableDraft();
    aiStage.value = "input";
    conflictsAccepted.value = false;
    expiredRemotely.value = false;
    submissionPhase.value = "";
    error.value = "";
    if (
      token &&
      id &&
      (!previous || token === previous[0] || id !== previous[1])
    )
      restore().catch(() => {
        error.value = "本机任务记录读取失败，请刷新重试。";
      });
  },
  { immediate: true, flush: "sync" },
);
async function reviewRequirements() {
  if (creating.value) return;
  run(async () => {
    const token = s.token;
    if (!token || !s.profile?.id) throw new Error("请先登录后规划旅行。");
    if (
      !requirement.value.description.trim() &&
      !requirement.value.destinations.length
    )
      throw new Error("先描述旅行想法，或补充目的地。");
    stopFollowing();
    removeStored("TASK");
    task.value = undefined;
    recoveryTaskId.value = "";
    const payload: Record<string, unknown> = { ...snapshot() };
    if (!reviewedOnce.value) {
      for (const key of [
        "destinations",
        "interests",
        "budgetIncludes",
        "mustVisit",
        "avoid",
      ] as const)
        if (!requirement.value[key].length && !editedFields.value.includes(key))
          payload[key] = null;
      if (
        payload.budgetScope === "UNKNOWN" &&
        !editedFields.value.includes("budgetScope")
      )
        payload.budgetScope = null;
      if (!payload.currency) payload.currency = null;
    }
    const version = epoch;
    const result = await api<RequirementReview>(
      "/ai/tasks/requirements",
      "POST",
      {
        requirement: payload,
        extract: !reviewedOnce.value,
        editedFields: [...editedFields.value],
      },
      stream.signal,
    );
    if (!current(token, version)) return;
    applyReview(result);
  });
}
async function sendPending(capabilitiesChecked = false) {
  const submission = pending.value;
  if (!submission) return;
  const token = s.token,
    version = epoch;
  if (submission.kind !== "INTERPRET")
    submissionStartedAt.value = submission.submittedAt;
  if (!capabilitiesChecked) await requireAi();
  if (!current(token, version) || pending.value !== submission) return;
  submissionPhase.value =
    submission.kind === "INTERPRET"
      ? "正在提交需求理解任务…"
      : "正在提交路书生成任务…";
  const controller = new AbortController(),
    parentSignal = stream.signal;
  let timedOut = false;
  const abortRequest = () => controller.abort();
  parentSignal.addEventListener("abort", abortRequest, { once: true });
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 20000);
  try {
    const t = await api<Task>(
      "/ai/tasks",
      "POST",
      {
        requestKey: submission.requestKey,
        requirement: submission.requirement,
        type: submission.kind || "GENERATE",
        ...(submission.kind === "INTERPRET"
          ? { editedFields: submission.editedFields || [] }
          : { confirmed: true }),
      },
      controller.signal,
    );
    if (!current(token, version)) return;
    acceptTask(t);
    if (["QUEUED", "RUNNING"].includes(t.status)) follow(t.id);
  } catch (e) {
    if (timedOut && current(token, version))
      throw new Error(
        "提交结果尚未确认。请使用原请求查询或重试，不要重复生成；也可以保留需求，停止等待。",
      );
    if (
      current(token, version) &&
      e instanceof ApiError &&
      [400, 403, 409, 422, 429, 503].includes(e.status)
    ) {
      removeStored("GENERATION_REQUEST");
      pending.value = undefined;
    }
    if (current(token, version)) void refreshAiCapabilities();
    throw e;
  } finally {
    clearTimeout(timeout);
    parentSignal.removeEventListener("abort", abortRequest);
    if (current(token, version)) submissionPhase.value = "";
  }
}
function generate() {
  if (creating.value || !review.value) return;
  submittingGeneration.value = true;
  submissionStartedAt.value = Date.now();
  run(async () => {
    if (currencyNeedsReview.value)
      throw new Error("币种更新尚未完成核对，请先重新核对需求。");
    if (requirementProblems.value.length)
      throw new Error(requirementProblems.value.join(" "));
    if (review.value!.conflicts.length && !conflictsAccepted.value)
      throw new Error("请先确认冲突处以已补充的关键条件为准。");
    const token = s.token,
      version = epoch;
    await requireAi();
    if (!current(token, version)) return;
    stopFollowing();
    removeStored("TASK");
    recoveryTaskId.value = "";
    task.value = undefined;
    clearEditableDraft();
    previewTitle.value = "";
    expiredRemotely.value = false;
    const submission: PendingGeneration = {
      requestKey: requestKey(),
      requirement: snapshot(),
      kind: "GENERATE",
      submittedAt: submissionStartedAt.value,
      editedFields: [...editedFields.value],
      reviewedOnce: reviewedOnce.value,
    };
    localStorage.setItem(
      storageKey("GENERATION_REQUEST"),
      JSON.stringify(submission),
    );
    pending.value = submission;
    await sendPending(true);
  }).finally(() => {
    submittingGeneration.value = false;
  });
}
function interpretRequirements() {
  if (creating.value) return;
  run(async () => {
    if (!s.token || !s.profile?.id) throw new Error("请先登录后理解旅行需求。");
    if (!requirement.value.description.trim())
      throw new Error(
        "智能理解需要一段旅行描述；也可以只填写关键条件后使用规则整理。",
      );
    const token = s.token,
      version = epoch;
    await requireAi();
    if (!current(token, version)) return;
    stopFollowing();
    removeStored("TASK");
    task.value = undefined;
    recoveryTaskId.value = "";
    review.value = undefined;
    const submission: PendingGeneration = {
      requestKey: requestKey(),
      kind: "INTERPRET",
      requirement: snapshot(),
      editedFields: [...editedFields.value],
      reviewedOnce: reviewedOnce.value,
    };
    localStorage.setItem(
      storageKey("GENERATION_REQUEST"),
      JSON.stringify(submission),
    );
    pending.value = submission;
    await sendPending(true);
  });
}
function stopWaiting() {
  if (busy.value || !pending.value) return;
  stopFollowing();
  removeStored("GENERATION_REQUEST");
  removeStored("TASK");
  pending.value = undefined;
  task.value = undefined;
  recoveryTaskId.value = "";
  review.value = undefined;
  conflictsAccepted.value = false;
  aiStage.value = "input";
  error.value = "";
}
function editRequirements() {
  if (creating.value) return;
  if (
    draftDirty.value &&
    !window.confirm("返回修改需求会丢失尚未保存的草稿修改。确定返回吗？")
  )
    return;
  clearEditableDraft();
  stopFollowing();
  removeStored("TASK");
  task.value = undefined;
  recoveryTaskId.value = "";
  review.value = undefined;
  aiStage.value = "input";
  error.value = "";
  expiredRemotely.value = false;
  conflictsAccepted.value = false;
}
function saveDraftChanges() {
  if (
    creating.value ||
    expired.value ||
    task.value?.status !== "PREVIEW" ||
    !draftDirty.value ||
    draftConflict.value
  )
    return;
  run(async () => {
    const id = task.value!.id,
      token = s.token,
      version = epoch;
    const draft = draftPayload();
    try {
      const value = await api<Task>(
        "/ai/tasks/" + encodeURIComponent(id) + "/draft",
        "PUT",
        { draft, draftRevision: draftRevision.value },
        stream.signal,
      );
      if (!current(token, version) || task.value?.id !== id) return;
      acceptTask(value, true);
      draftNotice.value = draftSaveBlocked.value
        ? "草稿修改已暂存，仍有问题需要处理。修正后保存草稿并重新校验，无需再次调用模型。"
        : "草稿修改已保存并通过校验，确认后才会创建正式路书。";
    } catch (e) {
      if (current(token, version) && e instanceof ApiError && e.status === 409)
        draftConflict.value = true;
      if (current(token, version) && e instanceof ApiError && e.status === 410)
        expiredRemotely.value = true;
      throw e;
    }
  });
}
function raiseBudget(payload: { budgetMax: number; requestKey: string }) {
  if (creating.value || expired.value || task.value?.status !== "PREVIEW")
    return;
  if (draftDirty.value || draftConflict.value) {
    error.value = "请先保存草稿修改并重新校验，再确认提高预算。";
    return;
  }
  run(async () => {
    const id = task.value!.id,
      token = s.token,
      version = epoch;
    const controller = new AbortController(),
      parentSignal = stream.signal;
    const abort = () => controller.abort();
    parentSignal.addEventListener("abort", abort, { once: true });
    const timeout = setTimeout(abort, 20000);
    try {
      const updated = await api<Task>(
        "/ai/tasks/" + encodeURIComponent(id) + "/budget",
        "PUT",
        { ...payload, confirmed: true, draftRevision: draftRevision.value },
        controller.signal,
      );
      if (!current(token, version) || task.value?.id !== id) return;
      acceptTask(updated, true);
      draftNotice.value = "已按你的确认提高预算并重新校验，正式路书尚未创建。";
    } catch (e) {
      if (current(token, version) && e instanceof ApiError && e.status === 409)
        draftConflict.value = true;
      if (current(token, version) && e instanceof ApiError && e.status === 410)
        expiredRemotely.value = true;
      if (controller.signal.aborted && !parentSignal.aborted)
        throw new Error(
          "预算修改结果尚未确认，请先读取服务器草稿核对；无需再次生成。",
        );
      throw e;
    } finally {
      clearTimeout(timeout);
      parentSignal.removeEventListener("abort", abort);
    }
  });
}
function openSavedTrip() {
  if (task.value?.status === "APPLIED" && task.value.tripId)
    void router.push("/trip/edit/" + task.value.tripId);
}
function recoverDraft() {
  if (
    creating.value ||
    task.value?.status !== "FAILED" ||
    !task.value.recoverable
  )
    return;
  run(async () => {
    stopFollowing();
    const id = task.value!.id,
      token = s.token,
      version = epoch;
    const controller = new AbortController(),
      parentSignal = stream.signal;
    const abortRequest = () => controller.abort();
    parentSignal.addEventListener("abort", abortRequest, { once: true });
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const value = await api<Task>(
        "/ai/tasks/" + encodeURIComponent(id) + "/recover",
        "POST",
        undefined,
        controller.signal,
      );
      if (!current(token, version) || task.value?.id !== id) return;
      acceptTask(value);
      error.value = "";
      if (["QUEUED", "RUNNING"].includes(value.status)) follow(id);
      else if (value.status === "FAILED")
        error.value =
          value.errorMessage || "此前响应仍无法恢复，请保留需求后重新生成。";
      else
        draftNotice.value =
          "已利用此前模型响应恢复草稿，未重新调用模型或扣减额度。请先检查待处理问题。";
    } catch (e) {
      if (!current(token, version)) return;
      if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500)
        throw new Error(
          "恢复结果尚未确认，请先点击“重新查询任务”查看当前结果。恢复不会再次调用模型。",
        );
      throw e;
    } finally {
      clearTimeout(timeout);
      parentSignal.removeEventListener("abort", abortRequest);
    }
  });
}
function reloadSavedDraft() {
  if (creating.value || !task.value) return;
  run(async () => {
    const id = task.value!.id,
      token = s.token,
      version = epoch;
    const value = await api<Task>(
      "/ai/tasks/" + encodeURIComponent(id),
      "GET",
      undefined,
      stream.signal,
    );
    if (!current(token, version) || task.value?.id !== id) return;
    acceptTask(value, true);
    draftNotice.value = "已读取服务器保存的草稿。";
  });
}
function saveDraft() {
  if (creating.value || expired.value || task.value?.status !== "PREVIEW")
    return;
  if (draftDirty.value || draftConflict.value) {
    error.value = "请先保存草稿修改，或读取已存草稿，再确认保存正式路书。";
    return;
  }
  if (draftSaveBlocked.value) {
    error.value =
      "草稿仍有未解决的问题，请按提示修正并保存草稿，通过校验后再创建正式路书。";
    return;
  }
  run(async () => {
    const title = previewTitle.value.trim(),
      token = s.token,
      version = epoch,
      id = task.value!.id,
      owner = s.profile?.id;
    if (!title || title.length > 100)
      throw new Error("请填写 1–100 字的路书标题。");
    try {
      const trip = await api<Trip>(
        "/ai/tasks/" + encodeURIComponent(id) + "/apply",
        "POST",
        { title, draftRevision: draftRevision.value },
        stream.signal,
      );
      if (!current(token, version)) return;
      task.value = {
        ...task.value!,
        status: "APPLIED",
        tripId: trip.id,
        result: trip,
      };
      setEditableDraft(trip);
      const failure = await router.push("/trip/edit/" + trip.id);
      if (failure)
        throw new Error(
          "路书已保存，但跳转未完成，请点击“打开已保存路书”重试。",
        );
      removeStored("TASK", owner);
    } catch (e) {
      if (e instanceof ApiError && e.status === 410)
        expiredRemotely.value = true;
      if (e instanceof ApiError && e.status === 409) draftConflict.value = true;
      throw e;
    }
  });
}
onMounted(async () => {
  window.addEventListener("pageshow", replayEntrance);
  window.addEventListener("beforeunload", beforeDraftUnload);
  window.addEventListener("focus", refreshAiCapabilities);
  document.addEventListener("visibilitychange", refreshOnVisibility);
  expiryTimer = setInterval(() => {
    now.value = Date.now();
  }, 30000);
  await refreshAiCapabilities();
  if (s.token && !s.profile) await s.refresh().catch(() => {});
});
onBeforeUnmount(() => {
  window.removeEventListener("pageshow", replayEntrance);
  window.removeEventListener("beforeunload", beforeDraftUnload);
  window.removeEventListener("focus", refreshAiCapabilities);
  document.removeEventListener("visibilitychange", refreshOnVisibility);
  clearInterval(expiryTimer);
  stopFollowing();
});
</script>
<template>
  <div
    class="generate-v3 planning-scene flowing-journey"
    :class="{
      'draft-workspace-mode': workspaceVisible,
      'generation-progress-mode': progressVisible,
    }"
  >
    <a
      class="skip-link"
      :href="
        workspaceVisible
          ? '#draft-workspace'
          : progressVisible
            ? '#generation-progress'
            : '#planner'
      "
      >跳到旅行规划</a
    >
    <JourneyFlowBackground
      :mode="mode === 'manual' ? 'manual' : 'ai'"
      :stage="flowStage"
    />
    <header class="glass-nav">
      <RouterLink class="brand" to="/" aria-label="SmartTrip 智游伴路首页"
        ><HomeIcon /><span>SmartTrip<small>智游伴路</small></span></RouterLink
      >
      <nav class="main-nav" aria-label="主导航">
        <RouterLink to="/square">旅行灵感</RouterLink
        ><RouterLink to="/ai-generate">规划旅行</RouterLink
        ><RouterLink to="/my-trips">我的路书</RouterLink>
      </nav>
      <div v-if="s.token" class="planning-account">
        <RouterLink
          to="/profile"
          aria-label="个人中心"
          :title="s.profile?.nickname || '个人中心'"
        >
          <img
            v-if="s.profile?.avatar && !avatarFailed"
            :src="s.profile.avatar"
            alt="我的头像"
            @error="avatarFailed = true"
          />
          <span v-else class="account-letter">{{
            s.profile?.nickname?.slice(0, 1) || "我"
          }}</span>
          <span class="planning-account-name">{{
            s.profile?.nickname || "个人中心"
          }}</span>
        </RouterLink>
        <button type="button" @click="logout">退出</button>
      </div>
      <RouterLink
        v-else
        class="login-link"
        :to="{ path: '/login', query: { redirect: route.fullPath } }"
        >登录 / 注册</RouterLink
      >
    </header>
    <Transition
      name="motion-fade"
      mode="out-in"
      @after-enter="focusPlanningScene"
    >
      <div
        v-if="!workspaceVisible && !progressVisible"
        key="requirements"
        class="planning-main"
      >
        <div :key="entranceKey" class="planning-invitation">
          <svg
            class="paper-plane"
            viewBox="0 0 150 100"
            fill="none"
            aria-hidden="true"
          >
            <path
              class="plane-trail"
              d="M3 90c55-2 64-34 45-32-20 2-2 24 20 7l33-30"
            />
            <path
              d="m87 25 58-19-24 48-10-24-24-5Zm24 5L145 6m-34 24-9 17 2-20"
            />
          </svg>
          <h1 class="hero-title">
            <span>从一个想法，</span
            ><span
              >到一段难忘的<em
                >旅程<svg
                  viewBox="0 0 190 24"
                  preserveAspectRatio="none"
                  aria-hidden="true"
                >
                  <path d="M3 20C57 2 126 3 185 4" /></svg></em
              >。</span
            >
          </h1>
          <p class="hero-copy">
            告诉我们你想去的地方，<br />剩下的，交给
            <span lang="en">SmartTrip</span>。
          </p>
          <dl class="journey-glance" aria-label="当前填写的旅行摘要">
            <div
              v-for="item in journeyGlance"
              :key="item.key"
              class="journey-summary-card"
              :class="'summary-' + item.key"
            >
              <span class="summary-icon" aria-hidden="true"
                ><span v-if="item.icon === 'budget'">¥</span
                ><HomeIcon v-else :name="item.icon"
              /></span>
              <div>
                <dt>{{ item.label }}</dt>
                <dd v-if="item.value" :title="item.value">{{ item.value }}</dd>
              </div>
            </div>
          </dl>
        </div>
        <section
          class="planner-panel builder"
          id="planner"
          aria-label="构建一段旅程"
          tabindex="-1"
        >
          <div
            class="creation-modes"
            role="group"
            aria-label="创建方式"
            :style="{ '--mode-index': mode === 'ai' ? 1 : 0 }"
          >
            <span class="mode-selection" aria-hidden="true"></span>
            <button
              type="button"
              :aria-pressed="mode === 'manual'"
              :disabled="creating"
              @click="mode = 'manual'"
            >
              <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14Zm0 0 1-6m-1 6 5-1M3 4h4M5 2v4"
                /></svg
              >手动创建路书
            </button>
            <button
              type="button"
              :aria-pressed="mode === 'ai'"
              :disabled="creating"
              :aria-describedby="
                mode === 'ai' && !s.capabilities.ai
                  ? 'ai-unavailable'
                  : undefined
              "
              @click="mode = 'ai'"
            >
              <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="m12 3 2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3ZM20 2v4m-2-2h4"
                /></svg
              >AI 生成草稿
            </button>
          </div>
          <p class="planner-intro">
            {{
              mode === "manual"
                ? "从一个目的地开始，填写旅行偏好，把想法慢慢变成行程。"
                : "写下旅行想法，补充关键条件，再一起确认出发的方向。"
            }}
          </p>
          <details
            v-if="
              mode === 'ai' &&
              (!s.capabilities.ai ||
                s.capabilitiesError ||
                (s.capabilities.status?.ai &&
                  s.capabilities.status.ai !== 'READY'))
            "
            class="builder-notice ai-capability-details"
            id="ai-unavailable"
            role="status"
          >
            <summary>
              AI 服务说明
              <span>{{ s.capabilitiesError || aiUnavailableReason() }}</span>
            </summary>
            <p>
              {{ s.capabilitiesError || aiUnavailableReason() }}
              {{ aiAction() }}
            </p>
            <ul v-if="s.capabilities.aiConfigurationProblems?.length">
              <li
                v-for="problem in s.capabilities.aiConfigurationProblems"
                :key="problem"
              >
                {{ problem }}
              </li>
            </ul>
            <p v-if="!s.capabilities.ai">
              智能理解和生成草稿暂不可用；仍可填写条件、使用本地规则整理或手动创建。
            </p>
            <button
              type="button"
              class="back-button"
              :disabled="s.capabilitiesLoading"
              @click="refreshAiCapabilities"
            >
              {{ s.capabilitiesLoading ? "正在检查配置…" : "重新检查 AI 配置" }}
            </button>
          </details>
          <Transition
            name="planning-mode"
            mode="out-in"
            @before-leave="freezeModeForm"
            @before-enter="activateModeForm"
            @after-enter="focusModeForm"
          >
            <form
              v-if="mode === 'manual'"
              key="manual-mode"
              ref="builderForm"
              class="builder-form"
              id="planning-form"
              :aria-busy="creating"
              novalidate
              @submit.prevent="advance"
            >
              <nav
                class="planning-steps"
                :style="{ '--step-progress': (step - 1) * 50 + '%' }"
                aria-label="旅程创建步骤"
              >
                <template
                  v-for="(label, i) in [
                    '选择目的地',
                    '安排旅行节奏',
                    '确认并创建',
                  ]"
                  :key="label"
                >
                  <i v-if="i" aria-hidden="true">›</i>
                  <button
                    type="button"
                    :aria-current="step === i + 1 ? 'step' : undefined"
                    :class="{ complete: i + 1 < step }"
                    :disabled="i + 1 > step || creating"
                    @click="step = i + 1"
                  >
                    <span>{{ i + 1 }}</span
                    ><b>{{ label }}</b>
                  </button>
                </template>
              </nav>

              <fieldset :disabled="creating">
                <legend class="sr-only">构建旅行</legend>
                <div class="manual-stage-stack">
                  <Transition :name="manualDirection">
                    <section
                      v-show="step === 1"
                      class="planning-stage"
                      :inert="step !== 1"
                      :aria-hidden="step !== 1"
                      data-stage="1"
                    >
                      <section class="form-section manual-basics">
                        <div class="form-section-heading">
                          <HomeIcon name="pin" />
                          <h2 tabindex="-1">想去哪里？</h2>
                        </div>
                        <p class="form-section-caption">
                          目的地与基本信息 · 先确定方向，再给旅行取个名字。
                        </p>
                        <div class="stage-fields pace-fields form-section-body">
                          <label
                            >目的地<span class="input-wrap"
                              ><HomeIcon name="pin" /><input
                                ref="destinationInput"
                                v-model="form.destination"
                                maxlength="100"
                                required
                                placeholder="例如 成都、东京、京都…" /></span
                          ></label>
                          <label
                            >路书标题（可选）<span class="input-wrap"
                              ><svg
                                class="icon"
                                viewBox="0 0 24 24"
                                aria-hidden="true"
                              >
                                <path
                                  d="M14 3H5v18h14V8Zm0 0v5h5M8 12h8m-8 4h8M8 7h2"
                                /></svg
                              ><input
                                v-model="form.title"
                                maxlength="100"
                                placeholder="给这次旅行取个名字"
                              /><span class="field-count" aria-hidden="true"
                                >{{ form.title.length }}/100</span
                              ></span
                            ></label
                          >
                        </div>
                      </section>
                      <p class="section-label manual-next-note">
                        <HomeIcon
                          name="clock"
                        />接下来安排出发时间与同行方式，再核对并创建。
                      </p>
                    </section>
                  </Transition>
                  <Transition :name="manualDirection">
                    <section
                      v-show="step === 2"
                      class="planning-stage"
                      :inert="step !== 2"
                      :aria-hidden="step !== 2"
                      data-stage="2"
                    >
                      <h2 tabindex="-1">按自己的节奏出发</h2>
                      <p class="stage-description">
                        决定停留的时间，以及和谁一起走。
                      </p>
                      <section class="form-section manual-schedule">
                        <div class="form-section-heading">
                          <HomeIcon name="clock" />
                          <h3>行程安排</h3>
                        </div>
                        <p class="form-section-caption">
                          日期可以待定，旅行天数为 1–30 天。
                        </p>
                        <div class="stage-fields pace-fields form-section-body">
                          <label
                            >出发日期<input
                              v-model="form.startDate"
                              type="date"
                          /></label>
                          <label
                            >旅行天数<input
                              ref="daysInput"
                              v-model.number="form.totalDays"
                              type="number"
                              min="1"
                              max="30"
                              required
                          /></label>
                        </div>
                      </section>
                      <section class="form-section manual-preferences">
                        <div class="form-section-heading">
                          <HomeIcon name="user" />
                          <h3>旅行方式与同行人群</h3>
                        </div>
                        <p class="form-section-caption">
                          保留适合自己的旅行节奏。
                        </p>
                        <div class="stage-fields pace-fields form-section-body">
                          <label
                            >旅行风格<select v-model="form.tripStyle">
                              <option>城市漫步</option>
                              <option>自然山野</option>
                              <option>美食探索</option>
                              <option>亲子休闲</option>
                              <option>文化博物馆</option>
                            </select></label
                          >
                          <label
                            >同行人群<select v-model="form.travelGroup">
                              <option>朋友同行</option>
                              <option>独自旅行</option>
                              <option>情侣出游</option>
                              <option>家庭亲子</option>
                              <option>带长辈出行</option>
                            </select></label
                          >
                        </div>
                      </section>
                    </section>
                  </Transition>
                  <Transition :name="manualDirection">
                    <section
                      v-show="step === 3"
                      class="planning-stage"
                      :inert="step !== 3"
                      :aria-hidden="step !== 3"
                      data-stage="3"
                    >
                      <section class="form-section manual-notes">
                        <div class="form-section-heading">
                          <HomeIcon name="route" />
                          <h2 tabindex="-1">为旅程留一点期待</h2>
                        </div>
                        <p class="form-section-caption">
                          创建私密路书后，再逐天填写地点、时间和费用。
                        </p>
                        <div class="stage-fields pace-fields form-section-body">
                          <label
                            >这次旅行的小期待<textarea
                              v-model="form.requirements"
                              :maxlength="mode === 'manual' ? 500 : 1500"
                              rows="4"
                              placeholder="想去的地点、预算、节奏，或需要避开的安排"
                            ></textarea>
                          </label>
                        </div>
                      </section>
                      <section class="form-section manual-review">
                        <div class="form-section-heading">
                          <HomeIcon name="pin" />
                          <h3>创建前核对</h3>
                        </div>
                        <dl class="builder-review planning-review">
                          <div>
                            <dt>目的地</dt>
                            <dd>{{ form.destination }}</dd>
                          </div>
                          <div>
                            <dt>路书标题</dt>
                            <dd>
                              {{ form.title || form.destination + "的旅行" }}
                            </dd>
                          </div>
                          <div>
                            <dt>出发日期</dt>
                            <dd>{{ form.startDate || "日期待定" }}</dd>
                          </div>
                          <div>
                            <dt>旅行安排</dt>
                            <dd>
                              {{ form.totalDays }} 天 · {{ form.tripStyle }} ·
                              {{ form.travelGroup }}
                            </dd>
                          </div>
                        </dl>
                      </section>
                    </section>
                  </Transition>
                </div>
              </fieldset>
              <Transition name="motion-fade"
                ><p v-if="error" class="form-error" role="alert">
                  {{ error }}
                </p></Transition
              >
              <div class="builder-controls planning-controls">
                <button
                  v-if="step > 1"
                  type="button"
                  class="back-button"
                  :disabled="creating"
                  @click="step--"
                >
                  上一步
                </button>
                <button
                  type="submit"
                  class="continue-button"
                  :disabled="creating"
                >
                  {{
                    creating
                      ? "请稍候…"
                      : step < 3
                        ? "继续"
                        : mode === "manual"
                          ? "创建私密路书"
                          : "生成 AI 草稿"
                  }}<HomeIcon name="arrow" />
                </button>
              </div>
            </form>

            <div
              v-else
              key="ai-mode"
              ref="aiRegion"
              class="ai-planner"
              :aria-busy="busy || running || recovering"
            >
              <p v-if="recovering" class="builder-notice" role="status">
                正在恢复上次任务…
              </p>
              <p v-if="submissionPhase" class="builder-notice" role="status">
                {{ submissionPhase }} 请稍候，不要重复提交。
              </p>
              <ol
                class="ai-progress"
                :style="{
                  '--ai-step-progress':
                    aiStage === 'input'
                      ? '0%'
                      : aiStage === 'confirm'
                        ? '50%'
                        : '100%',
                }"
                aria-label="AI 规划步骤"
              >
                <li
                  :aria-current="aiStage === 'input' ? 'step' : undefined"
                  :class="{ complete: aiStage !== 'input' }"
                >
                  <span aria-hidden="true">1</span><b>描述想法</b>
                </li>
                <li
                  :aria-current="aiStage === 'confirm' ? 'step' : undefined"
                  :class="{ complete: aiStage === 'preview' }"
                >
                  <span aria-hidden="true">2</span><b>确认需求</b>
                </li>
                <li :aria-current="aiStage === 'preview' ? 'step' : undefined">
                  <span aria-hidden="true">3</span><b>预览并保存</b>
                </li>
              </ol>
              <Transition
                name="motion-panel"
                mode="out-in"
                @after-enter="focusAiStage"
              >
                <form
                  v-if="aiStage === 'input'"
                  key="requirement-input"
                  class="builder-form"
                  @submit.prevent="reviewRequirements"
                  @input="requirementEdited"
                  @change="requirementEdited"
                >
                  <fieldset :disabled="creating">
                    <legend class="sr-only">旅行需求</legend>
                    <section class="planning-stage">
                      <h2 tabindex="-1">先说说，你想怎样旅行？</h2>
                      <p class="stage-description">
                        从一段想法开始，关键条件可以稍后补充。规则整理不调用模型；智能理解需你主动发起。
                      </p>
                      <section class="form-section ai-idea">
                        <div class="form-section-heading">
                          <HomeIcon name="route" />
                          <h3>旅行想法</h3>
                          <span class="form-section-caption">从一句话开始</span>
                        </div>
                        <div class="stage-fields form-section-body">
                          <label
                            ><span class="sr-only">旅行想法</span
                            ><textarea
                              data-requirement="description"
                              v-model="requirement.description"
                              maxlength="3000"
                              rows="4"
                              placeholder="例如：从苏州出发，秋天去大兴安岭看金黄的山林，玩五天，预算五千元，主要拍照，不想赶路。"
                            ></textarea>
                          </label>
                        </div>
                      </section>
                      <details
                        class="requirement-details form-section requirement-core ai-destination"
                        open
                      >
                        <summary class="form-section-heading">
                          <HomeIcon name="pin" /><span
                            class="form-section-title"
                            >目的地与基本信息</span
                          ><span class="form-section-caption"
                            >可以填写多个目的地，已在描述中说明的可先留空。</span
                          >
                        </summary>
                        <div
                          class="stage-fields pace-fields requirement-fields form-section-body"
                        >
                          <label
                            >目的地<input
                              :value="requirement.destinations.join('、')"
                              data-requirement="destinations"
                              @change="
                                setList('destinations', fieldValue($event))
                              "
                              maxlength="300"
                              placeholder="多个目的地用顿号分隔"
                          /></label>
                        </div>
                      </details>
                      <details
                        class="requirement-details form-section ai-schedule"
                        open
                      >
                        <summary class="form-section-heading">
                          <HomeIcon name="clock" /><span
                            class="form-section-title"
                            >行程安排</span
                          ><span class="form-section-caption"
                            >明确出发方向、旅行时间与往返交通范围。</span
                          >
                        </summary>
                        <div
                          class="stage-fields pace-fields requirement-fields form-section-body"
                        >
                          <label
                            >出发地<input
                              data-requirement="origin"
                              v-model="requirement.origin"
                              maxlength="100"
                              placeholder="可待定"
                          /></label>
                          <label
                            >旅行天数<input
                              data-requirement="days"
                              v-model.number="requirement.days"
                              type="number"
                              min="1"
                              max="30"
                              placeholder="1–30 天"
                          /></label>
                          <label
                            >具体出发日期<input
                              data-requirement="startDate"
                              v-model="requirement.startDate"
                              type="date"
                          /></label>
                          <label
                            >或大致时间<input
                              data-requirement="timeDescription"
                              v-model="requirement.timeDescription"
                              maxlength="100"
                              placeholder="例如 秋季 / 日期待定"
                          /></label>
                          <label
                            >包含往返交通日吗？<select
                              data-requirement="includesTravelDays"
                              v-model="requirement.includesTravelDays"
                            >
                              <option :value="null">待定</option>
                              <option :value="true">包含</option>
                              <option :value="false">不包含</option>
                            </select></label
                          >
                        </div>
                      </details>
                      <details
                        class="requirement-details form-section requirement-budget"
                        open
                      >
                        <summary class="form-section-heading">
                          <HomeIcon name="user" /><span
                            class="form-section-title"
                            >出行与预算</span
                          ><span class="form-section-caption"
                            >人均与总额分开确认，范围预算可以填写上下限。</span
                          >
                        </summary>
                        <div
                          class="stage-fields pace-fields requirement-fields form-section-body"
                        >
                          <label
                            >出行人数<input
                              data-requirement="travelers"
                              v-model.number="requirement.travelers"
                              type="number"
                              min="1"
                              max="100"
                              placeholder="可待定"
                          /></label>
                          <label
                            >预算口径<select
                              data-requirement="budgetScope"
                              v-model="requirement.budgetScope"
                            >
                              <option value="UNKNOWN">待确认</option>
                              <option value="PER_PERSON">人均预算</option>
                              <option value="TOTAL">整体预算</option>
                            </select></label
                          >
                          <label
                            >预算金额 / 下限（手动输入按人民币）<input
                              data-requirement="budget"
                              v-model.number="requirement.budget"
                              type="number"
                              min="0"
                              max="10000000"
                              placeholder="可不设预算"
                          /></label>
                          <label
                            >预算上限（手动输入按人民币）<input
                              data-requirement="budgetMax"
                              v-model.number="requirement.budgetMax"
                              type="number"
                              min="0"
                              max="10000000"
                              placeholder="范围预算时填写"
                          /></label>
                          <p class="section-label currency-status full-width">
                            预算币种：<strong>{{
                              requirement.currency === "CNY"
                                ? "人民币（CNY）"
                                : requirement.currency || "待确认"
                            }}</strong
                            >。手动输入金额按人民币记录，不自动进行外币换算。
                          </p>
                        </div>
                        <p
                          v-if="
                            (requirement.budget != null ||
                              requirement.budgetMax != null) &&
                            requirement.currency !== 'CNY'
                          "
                          class="builder-notice"
                        >
                          当前预算币种：{{
                            requirement.currency || "待确认"
                          }}。费用只支持人民币，不自动换算。请先将上面的金额改为人民币，再确认。
                          <button
                            type="button"
                            class="back-button"
                            @click="useRenminbi"
                          >
                            金额已按人民币填写，确认使用 CNY
                          </button>
                        </p>
                      </details>
                      <section
                        class="travel-preferences form-section"
                        aria-labelledby="travel-preferences-title"
                      >
                        <div class="form-section-heading">
                          <HomeIcon name="route" />
                          <h3 id="travel-preferences-title">旅行主题与偏好</h3>
                        </div>
                        <p class="stage-description">
                          可以多选，自由填写的偏好也会保留。
                        </p>
                        <div
                          class="preference-chips"
                          role="group"
                          aria-label="旅行偏好"
                        >
                          <button
                            v-for="interest in interestOptions"
                            :key="interest"
                            type="button"
                            :aria-pressed="
                              requirement.interests.includes(interest)
                            "
                            :disabled="creating"
                            @click="toggleInterest(interest)"
                          >
                            <svg
                              class="icon"
                              viewBox="0 0 24 24"
                              aria-hidden="true"
                            >
                              <path :d="interestIconPaths[interest]" /></svg
                            >{{ interest }}
                          </button>
                        </div>
                        <label class="custom-interests"
                          >兴趣偏好<input
                            :value="requirement.interests.join('、')"
                            data-requirement="interests"
                            @change="setList('interests', fieldValue($event))"
                            maxlength="500"
                            placeholder="例如 摄影、建筑，多个偏好用顿号分隔"
                        /></label>
                      </section>
                      <details
                        class="requirement-details form-section requirement-advanced"
                      >
                        <summary class="form-section-heading">
                          <HomeIcon name="route" /><span
                            class="form-section-title"
                            >更多偏好与限制</span
                          ><span class="form-section-caption"
                            >选填 · 交通、住宿与特殊要求</span
                          >
                        </summary>
                        <div class="stage-fields pace-fields form-section-body">
                          <label
                            >同行特点<input
                              data-requirement="companions"
                              v-model="requirement.companions"
                              maxlength="200"
                              placeholder="例如 带长辈 / 儿童 / 行动限制"
                          /></label>
                          <p class="builder-notice">
                            正式路书费用以人民币计；未确认的原始预算不会自动视为人民币，外币需先自行换算。
                          </p>
                          <label
                            v-for="field in extraListFields"
                            :key="field.key"
                            >{{ field.label
                            }}<input
                              :value="requirement[field.key].join('、')"
                              :data-requirement="field.key"
                              @change="setList(field.key, fieldValue($event))"
                              maxlength="500"
                              :placeholder="field.placeholder"
                          /></label>
                          <label
                            >旅行节奏<select
                              data-requirement="pace"
                              v-model="requirement.pace"
                            >
                              <option value="">待定</option>
                              <option value="RELAXED">轻松</option>
                              <option value="BALANCED">均衡</option>
                              <option value="PACKED">紧凑</option>
                            </select></label
                          >
                          <label
                            >体力强度<input
                              data-requirement="exertion"
                              v-model="requirement.exertion"
                              maxlength="200"
                              placeholder="例如 只接受轻量步行"
                          /></label>
                          <label
                            >交通偏好<input
                              data-requirement="transport"
                              v-model="requirement.transport"
                              maxlength="200"
                              placeholder="例如 公共交通优先"
                          /></label>
                          <label
                            >住宿偏好<input
                              data-requirement="accommodation"
                              v-model="requirement.accommodation"
                              maxlength="200"
                              placeholder="例如 安静、靠近公共交通"
                          /></label>
                          <label class="full-width"
                            >特殊时间限制<input
                              data-requirement="timeConstraints"
                              v-model="requirement.timeConstraints"
                              maxlength="500"
                              placeholder="例如 每天 18 点前返回住处"
                          /></label>
                          <label class="full-width"
                            >其他补充<textarea
                              data-requirement="notes"
                              v-model="requirement.notes"
                              maxlength="1000"
                              rows="3"
                            ></textarea>
                          </label>
                        </div>
                      </details>
                    </section>
                  </fieldset>
                  <p class="builder-notice">
                    规则整理不消耗额度。智能理解需求单独使用一次 AI
                    额度；生成草稿另外使用一次，失败均返还。
                  </p>
                  <div class="planning-controls">
                    <button
                      type="button"
                      class="back-button"
                      :disabled="creating || !s.capabilities.ai"
                      @click="interpretRequirements"
                    >
                      {{
                        busy && submissionPhase
                          ? submissionPhase
                          : "智能理解需求"
                      }}
                    </button>
                    <button
                      type="submit"
                      class="continue-button"
                      :disabled="creating"
                    >
                      {{
                        busy && !submissionPhase
                          ? "正在整理需求…"
                          : "规则整理并确认"
                      }}<HomeIcon name="arrow" />
                    </button>
                  </div>
                </form>
                <section
                  v-else-if="aiStage === 'confirm'"
                  key="requirement-confirmation"
                  class="planning-stage ai-confirm"
                >
                  <h2 tabindex="-1">
                    {{ running ? "正在构建你的旅程" : "出发前，确认这份需求" }}
                  </h2>
                  <p v-if="review" class="builder-notice" role="status">
                    需求来源：{{
                      review.source === "AI"
                        ? "AI 智能理解"
                        : "本地规则整理（未调用模型）"
                    }}。修改后需重新整理或理解，再确认生成。
                  </p>
                  <p class="stage-description">
                    未确定的日期不会被编造。成功生成草稿消耗一次 AI
                    额度；失败返还额度，确认保存不再扣额度。
                  </p>
                  <dl class="planning-review">
                    <div>
                      <dt>目的地</dt>
                      <dd>
                        {{ requirement.destinations.join("、") || "待确认" }}
                      </dd>
                    </div>
                    <div>
                      <dt>出发地</dt>
                      <dd>{{ requirement.origin || "待定" }}</dd>
                    </div>
                    <div>
                      <dt>旅行时间</dt>
                      <dd>
                        {{
                          requirement.startDate ||
                          requirement.timeDescription ||
                          "日期待定"
                        }}
                        · {{ requirement.days || "待确认" }} 天
                      </dd>
                    </div>
                    <div>
                      <dt>往返交通日</dt>
                      <dd>
                        {{
                          requirement.includesTravelDays === null
                            ? "待定"
                            : requirement.includesTravelDays
                              ? "包含在旅行天数内"
                              : "不包含在旅行天数内"
                        }}
                      </dd>
                    </div>
                    <div>
                      <dt>同行</dt>
                      <dd>
                        {{
                          requirement.travelers
                            ? requirement.travelers + " 人"
                            : "人数待定"
                        }}{{
                          requirement.companions
                            ? " · " + requirement.companions
                            : ""
                        }}
                      </dd>
                    </div>
                    <div>
                      <dt>预算</dt>
                      <dd>
                        {{
                          requirement.budget == null &&
                          requirement.budgetMax == null
                            ? "未设预算"
                            : (requirement.budgetScope === "TOTAL"
                                ? "整体"
                                : requirement.budgetScope === "PER_PERSON"
                                  ? "人均"
                                  : "口径待确认") +
                              " · " +
                              (requirement.budget == null
                                ? ""
                                : amount(requirement.budget)) +
                              (requirement.budgetMax == null
                                ? ""
                                : " 上限 " + amount(requirement.budgetMax))
                        }}{{
                          requirement.budgetIncludes.length
                            ? " · 含" + requirement.budgetIncludes.join("、")
                            : ""
                        }}
                      </dd>
                    </div>
                  </dl>
                  <div
                    v-if="
                      (requirement.budget != null ||
                        requirement.budgetMax != null) &&
                      requirement.currency !== 'CNY'
                    "
                    class="builder-notice"
                    role="status"
                  >
                    <p>
                      当前预算币种：{{
                        requirement.currency || "待确认"
                      }}。若以上数值已经是人民币，可在这里确认；外币请先自行换算并返回修改金额。
                    </p>
                    <button
                      type="button"
                      class="back-button"
                      :disabled="creating"
                      @click="confirmRenminbi"
                    >
                      确认以上数值按人民币填写（CNY）
                    </button>
                  </div>
                  <p
                    v-if="currencyNeedsReview"
                    class="builder-notice"
                    role="status"
                  >
                    币种已更新，需求尚未重新核对。原摘要仍保留；核对成功后才能生成。
                  </p>
                  <div v-if="review" class="requirement-summary">
                    <section
                      v-for="group in [
                        {
                          title: '已确认 · 硬性条件',
                          items: review.hardConstraints,
                        },
                        {
                          title: '旅行偏好 · 参考方向',
                          items: review.preferences,
                        },
                        { title: '需要补充', items: review.unresolved },
                        { title: '系统假设', items: review.assumptions },
                      ]"
                      :key="group.title"
                    >
                      <h3>{{ group.title }}</h3>
                      <ul v-if="group.items.length">
                        <li v-for="item in group.items" :key="item">
                          {{ item }}
                        </li>
                      </ul>
                      <p v-else>未添加</p>
                    </section>
                    <div
                      v-if="review.clarifications.length"
                      class="builder-notice"
                    >
                      <strong>建议补充</strong>
                      <ul>
                        <li
                          v-for="question in review.clarifications.slice(0, 3)"
                          :key="question"
                        >
                          {{ question }}
                        </li>
                      </ul>
                    </div>
                    <div
                      v-if="review.conflicts.length"
                      class="requirement-conflict"
                    >
                      <strong>描述与关键条件存在不同</strong>
                      <ul>
                        <li
                          v-for="conflict in review.conflicts"
                          :key="conflict"
                        >
                          {{ conflict }}
                        </li>
                      </ul>
                      <label
                        ><input
                          v-model="conflictsAccepted"
                          type="checkbox"
                          :disabled="creating"
                        />我确认以当前关键条件为准</label
                      >
                    </div>
                  </div>
                  <ul
                    v-if="requirementProblems.length && !running"
                    class="ai-validation"
                  >
                    <li v-for="problem in requirementProblems" :key="problem">
                      {{ problem }}
                    </li>
                  </ul>
                  <div class="planning-controls">
                    <button
                      type="button"
                      class="back-button"
                      :disabled="creating"
                      @click="editRequirements"
                    >
                      返回修改
                    </button>
                    <button
                      v-if="
                        (!review || currencyNeedsReview) && !running && !pending
                      "
                      type="button"
                      class="back-button"
                      :disabled="creating"
                      @click="reviewRequirements"
                    >
                      重新核对需求</button
                    ><button
                      v-if="review && !pending"
                      type="button"
                      class="continue-button"
                      :disabled="
                        creating ||
                        !!requirementProblems.length ||
                        currencyNeedsReview ||
                        (!!review.conflicts.length && !conflictsAccepted) ||
                        !s.capabilities.ai
                      "
                      @click="generate"
                    >
                      {{
                        running
                          ? "正在生成…"
                          : busy
                            ? submissionPhase || "正在核对需求…"
                            : "确认需求并生成"
                      }}<HomeIcon name="arrow" />
                    </button>
                  </div>
                </section>
              </Transition>
              <div v-if="pending && !busy" class="builder-notice" role="status">
                上次{{
                  pending.kind === "INTERPRET" ? "需求理解" : "生成"
                }}提交结果尚未确认。重试使用同一请求编号，不会另建任务。<button
                  type="button"
                  class="back-button"
                  :disabled="!s.capabilities.ai"
                  @click="run(sendPending)"
                >
                  重试获取本次任务
                </button>
                <p>
                  停止等待后，先前任务若已受理仍可能继续完成并使用额度，但不会自动保存正式路书。
                </p>
                <button type="button" class="back-button" @click="stopWaiting">
                  保留需求，停止等待
                </button>
              </div>
              <div
                v-if="
                  task && ['QUEUED', 'RUNNING', 'FAILED'].includes(task.status)
                "
                class="builder-task"
                role="status"
              >
                <HomeIcon name="clock" />
                <div>
                  <strong>{{
                    task.status === "QUEUED"
                      ? "任务已排队"
                      : task.status === "RUNNING"
                        ? taskPhaseLabel
                        : task.type === "INTERPRET"
                          ? "需求理解未完成"
                          : "生成未完成"
                  }}</strong>
                  <p>
                    {{
                      userText(task.errorMessage) ||
                      "请稍候。刷新页面后可以继续查看本次任务。"
                    }}
                  </p>
                  <p v-if="task.status === 'FAILED' && task.recoverable">
                    此任务保留了可恢复的模型响应，可在不调用模型、不再次扣减额度的情况下尝试恢复草稿。
                  </p>
                  <button
                    v-if="task.status === 'FAILED' && task.recoverable"
                    type="button"
                    class="back-button"
                    :disabled="creating"
                    @click="recoverDraft"
                  >
                    {{ busy ? "正在恢复…" : "从已有响应恢复草稿" }}
                  </button>
                  <button
                    v-if="task.status === 'FAILED'"
                    type="button"
                    class="back-button"
                    :disabled="creating"
                    @click="editRequirements"
                  >
                    保留需求并返回修改
                  </button>
                </div>
              </div>
              <Transition name="motion-fade"
                ><p v-if="error" class="form-error" role="alert">
                  {{ error }}
                </p></Transition
              >
              <button
                v-if="error && recoveryTaskId && !pending"
                type="button"
                class="back-button"
                :disabled="busy"
                @click="poll(recoveryTaskId)"
              >
                重新查询任务
              </button>
            </div>
          </Transition>
          <p class="privacy-note">
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path
                d="M8 17c0-4-3-4-3-9a7 7 0 0 1 14 0c0 5-3 5-3 9ZM8 20h8m-7 3h6M1 9h1m20 0h1M3 2l2 2m14 0 2-2"
              /></svg
            ><span
              >新建路书默认私密，仅你和受邀成员可查看。费用为预估，实际花费由记账记录。</span
            >
          </p>
        </section>
      </div>
      <main
        v-else-if="workspaceVisible && preview && task"
        key="workspace"
        id="draft-workspace"
        class="workspace-host"
        tabindex="-1"
      >
        <TripDraftWorkspace
          :draft="preview"
          v-model:title="previewTitle"
          :task="task"
          :busy="busy"
          :expired="expired"
          :dirty="draftDirty"
          :conflict="draftConflict"
          :notice="draftNotice"
          :error="userText(error)"
          @save-draft="saveDraftChanges"
          @apply="saveDraft"
          @reload="reloadSavedDraft"
          @budget-change="raiseBudget"
          @open-saved="openSavedTrip"
          @restart="editRequirements"
        />
      </main>
      <main
        v-else-if="progressVisible"
        key="generation-progress"
        id="generation-progress"
        class="generation-feedback"
        tabindex="-1"
      >
        <AiGenerationProgress
          :task="task"
          :submitting="busy"
          :submission-phase="submissionPhase"
          :submitted-at="submissionStartedAt"
          :reconnecting="taskReconnecting"
          :error="userText(error)"
          @retry="recoveryTaskId && poll(recoveryTaskId)"
        />
        <div class="generation-recovery-actions">
          <button
            v-if="!task && recoveryTaskId"
            type="button"
            :disabled="busy"
            @click="poll(recoveryTaskId)"
          >
            重新查询上次任务
          </button>
          <template v-if="pending && !busy">
            <p>
              提交结果尚未确认。重试使用同一请求编号，不会另建任务或重复调用模型。
            </p>
            <button type="button" :disabled="busy" @click="run(sendPending)">
              查询本次提交结果
            </button>
            <button type="button" :disabled="busy" @click="stopWaiting">
              保留需求，停止等待
            </button>
          </template>
          <template v-if="task?.status === 'FAILED'">
            <p v-if="task.recoverable">
              已保留可恢复的模型响应，可先恢复并编辑草稿。
            </p>
            <button
              v-if="task.recoverable"
              type="button"
              :disabled="busy"
              @click="recoverDraft"
            >
              从已有响应恢复草稿
            </button>
            <button type="button" :disabled="busy" @click="editRequirements">
              保留需求并返回修改
            </button>
          </template>
        </div>
      </main>
    </Transition>
    <footer
      v-if="!workspaceVisible && !progressVisible"
      class="planning-footer"
    >
      <span>把一个想法，变成自己的旅程。</span
      ><RouterLink to="/my-trips"
        >打开旅行库<HomeIcon name="arrow"
      /></RouterLink>
    </footer>
  </div>
</template>
