<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, nextTick } from "vue";
import { gsap } from "gsap";
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
import PreviewWorkspace from "./workbench/PreviewWorkspace.vue";
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
import WorkbenchDock from "./workbench/WorkbenchDock.vue";
import WorkbenchBudget from "./workbench/WorkbenchBudget.vue";
import { useWorkbenchQuality } from "./workbench/useWorkbenchQuality";
import { lockEditorScroll } from "./workbench/scrollLock";
import HomeIcon from "./HomeIcon.vue";
import { useWorkbenchMotion } from "./workbench/useWorkbenchMotion";
// One ordered entry keeps legacy structure before the final V5 materials.
import "./workbench-v3.css";
import "./workbench-motion.css";
import "./workbench-v4.css";
import "./workbench-cards-v4.css";
import "./workbench-inspector-v4.css";
import "./workbench-dock-v5.css";
import "./workbench-cards-v5.css";
import "./workbench-v5.css";
const motion = useWorkbenchMotion();
let editorDisposed = false;
type Workspace = "plan" | "tools" | "members" | "read";
type Assistant = "insight" | "map" | "analysis";
function workspaceQuery(value: unknown): Workspace {
  return value === "tools" || value === "members" || value === "read"
    ? value
    : "plan";
}
function assistantQuery(value: unknown): Assistant {
  return value === "map" || value === "analysis" ? value : "insight";
}
const selectedNode = ref("");
const timelineExpanded = ref(true);
const insightOpen = ref(false);
const rightPanelVisible = ref(false);
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
function releaseModal(dialog = modalDialog.value) {
  dialog?.close();
  releaseScroll?.();
  releaseScroll = undefined;
  const collapsedMenu = modalTrigger?.closest("details:not([open])");
  const target =
    collapsedMenu?.querySelector<HTMLElement>("summary") || modalTrigger;
  if (target?.isConnected) target.focus({ preventScroll: true });
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
let menuEpoch = 0;
let menuClosing = false;
async function hideMoreMenu(menu: HTMLDetailsElement) {
  const current = ++menuEpoch;
  menuClosing = true;
  await motion.play(
    menu.querySelector<HTMLElement>(".wb-more-content"),
    [
      { transform: "translate3d(0,0,0)", opacity: 1 },
      { transform: "translate3d(0,-6px,0)", opacity: 0 },
    ],
    "fast",
  );
  if (current !== menuEpoch || !menu.isConnected) return;
  const returnFocus = menu.contains(document.activeElement);
  menu.open = false;
  menuClosing = false;
  if (returnFocus && !modal.value)
    menu.querySelector<HTMLElement>("summary")?.focus({ preventScroll: true });
}
function toggleMoreMenu(event: MouseEvent) {
  const menu = (event.currentTarget as HTMLElement).closest("details");
  if (!menu) return;
  if (menu.open && !menuClosing) {
    void hideMoreMenu(menu);
    return;
  }
  menuEpoch++;
  menuClosing = false;
  menu.open = true;
  void motion.play(
    menu.querySelector<HTMLElement>(".wb-more-content"),
    [
      { transform: "translate3d(0,-6px,0)", opacity: 0.5 },
      { transform: "translate3d(0,0,0)", opacity: 1 },
    ],
    "fast",
  );
}
function closeMoreMenu(event: MouseEvent | KeyboardEvent) {
  if (
    event.type === "click" &&
    !(event.target instanceof Element && event.target.closest("button"))
  )
    return;
  void hideMoreMenu(event.currentTarget as HTMLDetailsElement);
}
function enterModal(element: Element, done: () => void) {
  void motion
    .play(element as HTMLElement, [
      { transform: "translate3d(0,12px,0) scale(.985)", opacity: 0.5 },
      { transform: "translate3d(0,0,0) scale(1)", opacity: 1 },
    ])
    .then(done);
}
function leaveModal(element: Element, done: () => void) {
  void motion
    .play(
      element as HTMLElement,
      [
        { transform: "translate3d(0,0,0) scale(1)", opacity: 1 },
        { transform: "translate3d(0,8px,0) scale(.99)", opacity: 0 },
      ],
      "fast",
    )
    .then(done);
}
function afterModalLeave(element: Element) {
  (element as HTMLDialogElement).close();
  // A rapid reopen retains the new modal's scroll lock and original return target.
  if (!modal.value) releaseModal(element as HTMLDialogElement);
}
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
  workspaceView = ref<Workspace>(workspaceQuery(route.query.workspace)),
  assistantView = ref<Assistant>(assistantQuery(route.query.assistant)),
  previewWorkspace = ref<InstanceType<typeof PreviewWorkspace>>(),
  tools = ref<InstanceType<typeof TravelTools>>(),
  modal = ref(""),
  revisions = ref<Revision[]>([]),
  shares = ref<Share[]>([]),
  shareUrl = ref(""),
  search = ref(""),
  selected = ref<Item>();
const visitedWorkspaces = ref<Record<Workspace, boolean>>({
  plan: true,
  tools: false,
  members: false,
  read: false,
});
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
const saveState = ref<"idle" | "saving" | "saved" | "error">("idle");
const saveFailure = ref("");
const saveGlyph = ref<SVGSVGElement>();
let saveVisual: gsap.core.Tween | undefined;
watch(
  [saveState, saveGlyph, motion.reduced],
  () => {
    saveVisual?.kill();
    saveVisual = undefined;
    const glyph = saveGlyph.value;
    if (!glyph) return;
    const idle = glyph.querySelector<SVGPathElement>(".wb-save-idle");
    const spinner = glyph.querySelector<SVGCircleElement>(".wb-save-spinner");
    const check = glyph.querySelector<SVGPathElement>(".wb-save-check");
    gsap.set(glyph.querySelectorAll("path,circle"), {
      opacity: 0,
      clearProps: "transform",
    });
    if (saveState.value === "saving" && spinner) {
      gsap.set(spinner, { opacity: 1 });
      if (!motion.reduced.value)
        saveVisual = gsap.to(spinner, {
          rotation: 360,
          transformOrigin: "50% 50%",
          duration: 0.9,
          ease: "none",
          repeat: -1,
        });
    } else if (saveState.value === "saved" && check) {
      const length = check.getTotalLength();
      gsap.set(check, {
        opacity: 1,
        strokeDasharray: length,
        strokeDashoffset: motion.reduced.value ? 0 : length,
      });
      if (!motion.reduced.value)
        saveVisual = gsap.to(check, {
          strokeDashoffset: 0,
          duration: 0.36,
          ease: "power2.out",
        });
    } else if (idle) gsap.set(idle, { opacity: 1 });
  },
  { flush: "post" },
);
onBeforeUnmount(() => saveVisual?.kill());
let saveFeedbackTimer: ReturnType<typeof setTimeout> | undefined;
const saveAnnouncement = computed(() =>
  saveState.value === "saving"
    ? "正在保存路书…"
    : saveState.value === "saved"
      ? "路书已保存"
      : saveState.value === "error"
        ? "保存失败：" + saveFailure.value
        : "",
);
function clearSaveFeedback() {
  clearTimeout(saveFeedbackTimer);
  saveState.value = "idle";
  saveFailure.value = "";
}
watch(dirty, (value) => {
  if (value && saveState.value !== "saving") clearSaveFeedback();
});
watch(() => route.params.tripId, clearSaveFeedback);
onBeforeUnmount(() => clearTimeout(saveFeedbackTimer));
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
      releaseScroll ||= lockEditorScroll();
    }
  },
  { flush: "sync" },
);
onBeforeUnmount(() => {
  editorDisposed = true;
  menuEpoch++;
  releaseModal();
});
const { busy: actionBusy, error, run } = useAction();
const analysisBusy = ref(false);
const membersBusy = ref(false);
const busy = computed(
  () => actionBusy.value || analysisBusy.value || membersBusy.value,
);
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
const headerEstimate = computed(() => {
  let sum = 0,
    unknown = 0;
  for (const item of trip.value?.days.flatMap((day) => day.items) || []) {
    const cost = item.costEstimate;
    if (typeof cost === "number" && Number.isFinite(cost) && cost >= 0)
      sum += cost;
    else unknown++;
  }
  return { sum, unknown };
});
const permissionsOpen = ref(false);
const permissionsDialog = ref<HTMLDialogElement>();
const accessLabel = computed(() =>
  owner.value
    ? "所有者"
    : role.value === "EDITOR"
      ? "可编辑同行成员"
      : "只读同行成员",
);
let permissionsTrigger: HTMLElement | undefined;
let releasePermissionsScroll: (() => void) | undefined;
let permissionAction:
  | { type: "shares" | "members"; routeId: string }
  | undefined;
function openPermissions() {
  permissionAction = undefined;
  permissionsTrigger =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : undefined;
  releasePermissionsScroll ||= lockEditorScroll();
  permissionsOpen.value = true;
}
function closePermissions() {
  permissionAction = undefined;
  permissionsOpen.value = false;
}
function releasePermissions(
  dialog = permissionsDialog.value,
  restoreFocus = true,
) {
  dialog?.close();
  releasePermissionsScroll?.();
  releasePermissionsScroll = undefined;
  if (restoreFocus && !editorDisposed && permissionsTrigger?.isConnected)
    permissionsTrigger.focus({ preventScroll: true });
  permissionsTrigger = undefined;
}
watch(
  permissionsDialog,
  (dialog) => {
    if (!dialog || !permissionsOpen.value) return;
    if (!dialog.open) dialog.showModal();
    dialog
      .querySelector<HTMLButtonElement>("[data-permissions-close]")
      ?.focus();
  },
  { flush: "post" },
);
function onPermissionsBackdrop(event: MouseEvent) {
  const dialog = permissionsDialog.value;
  if (event.target !== dialog || !dialog) return;
  const rect = dialog.getBoundingClientRect();
  if (
    event.clientX < rect.left ||
    event.clientX > rect.right ||
    event.clientY < rect.top ||
    event.clientY > rect.bottom
  )
    closePermissions();
}
function launchPermissionAction(type: "shares" | "members") {
  if (
    busy.value ||
    loading.value ||
    (type === "shares" && (!owner.value || dirty.value))
  )
    return;
  permissionAction = { type, routeId: String(route.params.tripId) };
  permissionsOpen.value = false;
}
function afterPermissionsLeave(element: Element) {
  (element as HTMLDialogElement).close();
  if (permissionsOpen.value) return;
  const action = permissionAction;
  permissionAction = undefined;
  releasePermissions(element as HTMLDialogElement);
  if (
    editorDisposed ||
    !action ||
    action.routeId !== String(route.params.tripId) ||
    busy.value ||
    loading.value
  )
    return;
  if (action.type === "shares") {
    if (owner.value && !dirty.value) showShares();
  } else void selectWorkspace("members");
}
onBeforeUnmount(() => {
  permissionAction = undefined;
  releasePermissions(undefined, false);
});
watch(
  () => route.params.tripId,
  () => {
    resetTask();
    reset();
    dayIndex.value = 0;
    modal.value = "";
    permissionAction = undefined;
    permissionsOpen.value = false;
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
let tabFocusEpoch = 0;
// Workspace queries change only the view; the trip loader watches tripId alone.
watch(
  () => [route.query.workspace, route.query.assistant],
  ([view, assistant]) => {
    workspaceView.value = workspaceQuery(view);
    assistantView.value = assistantQuery(assistant);
  },
  { immediate: true },
);
watch(
  workspaceView,
  async (view) => {
    visitedWorkspaces.value[view] = true;
    const current = ++tabFocusEpoch;
    await nextTick();
    if (
      editorDisposed ||
      current !== tabFocusEpoch ||
      workspaceView.value !== view
    )
      return;
    const active = document.activeElement;
    if (active instanceof HTMLElement && active.closest("[inert]")) {
      const heading = document.querySelector<HTMLElement>(
        '[data-workspace="' +
          view +
          '"] h1, [data-workspace="' +
          view +
          '"] h2',
      );
      heading?.setAttribute("tabindex", "-1");
      heading?.focus({ preventScroll: true });
    }
  },
  { immediate: true },
);
async function updateWorkspaceQuery() {
  const query = {
    ...route.query,
    workspace: workspaceView.value === "plan" ? undefined : workspaceView.value,
    assistant:
      assistantView.value === "insight" ? undefined : assistantView.value,
  };
  await router.push({ path: route.path, query });
}
async function selectWorkspace(value: string) {
  if (analysisBusy.value || membersBusy.value) return;
  const next = workspaceQuery(value);
  if (next === workspaceView.value) return;
  workspaceView.value = next;
  visitedWorkspaces.value[next] = true;
  await updateWorkspaceQuery();
}
function selectAssistant(value: Assistant) {
  if (analysisBusy.value) return;
  workspaceView.value = "plan";
  assistantView.value = value;
  insightOpen.value = value === "insight";
  if (value !== "insight") selectedNode.value = "";
  void updateWorkspaceQuery();
}
async function closeTool() {
  if (analysisBusy.value) return;
  selectAssistant("insight");
  await nextTick();
  if (!editorDisposed)
    document
      .querySelector<HTMLElement>('[aria-label="工具：洞察"]')
      ?.focus({ preventScroll: true });
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
  if (editorDisposed || !insightOpen.value || assistantView.value !== "insight")
    return;
  document
    .querySelector<HTMLButtonElement>("#workbench-insight .wb-insight-close")
    ?.focus();
});
function closePanels() {
  if (analysisBusy.value) return;
  selectedNode.value = "";
  insightOpen.value = false;
  assistantView.value = "insight";
  void updateWorkspaceQuery();
}
function openContext() {
  if (analysisBusy.value) return;
  selectedNode.value = "";
  selectAssistant("insight");
}
function openAiAdjustment() {
  if (
    !editable.value ||
    busy.value ||
    loading.value ||
    dirty.value ||
    !s.capabilities.ai
  )
    return;
  rememberModalTrigger();
  aiAdjust();
}
let qualityFocusEpoch = 0;
let qualityHighlight: HTMLElement | undefined;
let highlightTimer: ReturnType<typeof setTimeout> | undefined;
function clearQualityFocus() {
  qualityFocusEpoch++;
  clearTimeout(highlightTimer);
  qualityHighlight?.classList.remove("wb-problem-target");
  qualityHighlight = undefined;
}
watch(
  [dayIndex, workspaceView, assistantView, selectedNode, insightOpen],
  clearQualityFocus,
  {
    flush: "sync",
  },
);
onBeforeUnmount(() => {
  tabFocusEpoch++;
  clearQualityFocus();
});
async function focusQuality(dayId: number, itemId?: number) {
  if (dirty.value || busy.value || loading.value || !trip.value) return;
  const index = trip.value.days.findIndex((d) => d.id === dayId);
  if (index < 0) return;
  insightOpen.value = false;
  dayIndex.value = index;
  workspaceView.value = "plan";
  assistantView.value = "insight";
  void updateWorkspaceQuery();
  selectedNode.value = itemId ? "item-" + itemId : "";
  timelineExpanded.value = true;
  const current = ++qualityFocusEpoch;
  await nextTick();
  if (
    current !== qualityFocusEpoch ||
    workspaceView.value !== "plan" ||
    assistantView.value !== "insight" ||
    dayIndex.value !== index
  )
    return;
  const target = itemId
    ? document.getElementById("plan-item-" + itemId)
    : document.querySelector(".editor-panel");
  target?.scrollIntoView({
    behavior: motion.reduced.value ? "instant" : "smooth",
    block: "nearest",
  });
  if (target instanceof HTMLElement) {
    qualityHighlight = target;
    target.classList.add("wb-problem-target");
    highlightTimer = setTimeout(() => {
      if (current === qualityFocusEpoch) {
        target.classList.remove("wb-problem-target");
        qualityHighlight = undefined;
      }
    }, 1400);
    const editorInput = itemId
      ? document.querySelector<HTMLInputElement>(
          ".wb-inspector-dialog[open] .wb-node-fields input",
        )
      : undefined;
    (editorInput || target).focus({ preventScroll: true });
  }
}
async function save() {
  if (
    busy.value ||
    loading.value ||
    !dirty.value ||
    !editable.value ||
    !trip.value
  )
    return;
  clearTimeout(saveFeedbackTimer);
  const submittedTree = trip.value;
  const tripId = submittedTree.id;
  const routeId = String(route.params.tripId);
  const submittedDay = dayIndex.value;
  const submittedKey = selectedNode.value;
  const submittedIndex =
    submittedTree.days[submittedDay]?.items.findIndex(
      (item) => nodeKey(item) === submittedKey,
    ) ?? -1;
  const submittedItem = submittedTree.days[submittedDay]?.items[submittedIndex];
  let selectionChanged = false;
  let selectionAfterReplacement = submittedKey;
  // A user selection/close while the request is pending wins over automatic rebinding.
  const stopSelection = watch(
    [selectedNode, dayIndex, workspaceView, assistantView, insightOpen],
    () => {
      if (
        trip.value === submittedTree &&
        (selectedNode.value !== submittedKey ||
          dayIndex.value !== submittedDay ||
          workspaceView.value !== "plan" ||
          assistantView.value !== "insight" ||
          insightOpen.value)
      )
        selectionChanged = true;
    },
    { flush: "sync" },
  );
  const stopReplacement = watch(
    trip,
    (value) => {
      if (value !== submittedTree)
        selectionAfterReplacement = selectedNode.value;
    },
    { flush: "sync" },
  );
  saveState.value = "saving";
  saveFailure.value = "";
  let completed: boolean | undefined;
  try {
    completed = await run(async () => {
      await saveDraft();
      return true;
    }, "路书已保存");
  } finally {
    stopSelection();
    stopReplacement();
  }
  if (
    editorDisposed ||
    String(route.params.tripId) !== routeId ||
    trip.value?.id !== tripId
  )
    return;
  if (completed && !dirty.value) {
    let reboundKey = "";
    if (
      submittedKey &&
      !selectionChanged &&
      trip.value !== submittedTree &&
      dayIndex.value === submittedDay &&
      workspaceView.value === "plan" &&
      assistantView.value === "insight" &&
      !insightOpen.value &&
      selectedNode.value === selectionAfterReplacement
    ) {
      const items = trip.value.days[submittedDay]?.items;
      const rebound =
        submittedItem?.id != null
          ? items?.find((item) => item.id === submittedItem.id)
          : submittedIndex >= 0
            ? items?.[submittedIndex]
            : undefined;
      reboundKey = rebound ? nodeKey(rebound) : "";
      selectedNode.value = reboundKey;
    }
    saveState.value = "saved";
    saveFeedbackTimer = setTimeout(() => {
      if (saveState.value === "saved") saveState.value = "idle";
    }, 2400);
    if (reboundKey && submittedItem?.id == null) {
      await nextTick();
      if (
        !editorDisposed &&
        String(route.params.tripId) === routeId &&
        trip.value?.id === tripId &&
        dayIndex.value === submittedDay &&
        selectedNode.value === reboundKey &&
        workspaceView.value === "plan" &&
        assistantView.value === "insight" &&
        !insightOpen.value
      )
        document
          .querySelector<HTMLInputElement>(
            ".wb-inspector-dialog[open] .wb-node-fields input",
          )
          ?.focus({ preventScroll: true });
    }
  } else if (error.value) {
    saveFailure.value = error.value;
    saveState.value = "error";
  } else saveState.value = "idle";
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
onBeforeRouteUpdate((to, from) =>
  to.params.tripId === from.params.tripId
    ? !analysisBusy.value && !membersBusy.value
    : leave(),
);
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
  await selectWorkspace("tools");
  await nextTick();
  if (workspaceView.value === "tools") tools.value?.record(item.id);
}
function exportImage() {
  run(async () => {
    if (!(await guardSaved())) return;
    await selectWorkspace("read");
    await nextTick();
    try {
      await previewWorkspace.value?.prepareOutput();
      const element = document.querySelector(
        ".trip-read",
      ) as HTMLElement | null;
      if (!element) throw new Error("路书预览尚未准备好，请稍后重试");
      const { default: html2canvas } = await import("html2canvas");
      const canvas = await html2canvas(element, {
        backgroundColor: "#f7f7f0",
        scale: 1.5,
      });
      const link = document.createElement("a");
      link.download = "旅行路书.png";
      link.href = canvas.toDataURL("image/png");
      link.click();
    } finally {
      await previewWorkspace.value?.finishOutput();
    }
  });
}
function print() {
  void run(async () => {
    if (!(await guardSaved())) return;
    await selectWorkspace("read");
    await nextTick();
    try {
      await previewWorkspace.value?.prepareOutput();
      window.print();
    } finally {
      await previewWorkspace.value?.finishOutput();
    }
  });
}
function membershipLeft() {
  selectedNode.value = "";
  role.value = "VIEWER";
  void router.replace("/my-trips");
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
    :workspace-view="workspaceView"
    :tool-open="workspaceView === 'plan' && assistantView === 'analysis'"
    :insight-open="workspaceView === 'plan' && insightOpen"
    :inspector-open="
      workspaceView === 'plan' &&
      Boolean(selectedNode) &&
      !insightOpen &&
      assistantView === 'insight'
    "
    :geo-open="workspaceView === 'plan' && assistantView === 'map'"
    :panel-busy="analysisBusy"
    @close-panels="closePanels"
    @open-context="openContext"
    @panel-visible="rightPanelVisible = $event"
  >
    <template #header>
      <div class="wb-workspace-header">
        <header class="wb-tripbar">
          <div class="wb-trip-heading">
            <RouterLink to="/my-trips" class="wb-back" aria-label="返回我的路书"
              ><HomeIcon name="arrow"
            /></RouterLink>
            <div class="wb-title-art">
              <input
                v-model="trip.title"
                :readonly="!editable || busy || loading"
                maxlength="100"
                aria-label="路书标题"
              />
              <p>
                {{ trip.destination }} · {{ trip.days.length }} 天 · 已填预估
                {{ money(headerEstimate.sum)
                }}<template v-if="headerEstimate.unknown">
                  · {{ headerEstimate.unknown }}项费用待补</template
                ><span
                  >版本 {{ trip.version }} ·
                  {{ dirty ? "有未保存修改" : "修改已保存" }}</span
                >
              </p>
            </div>
            <button
              type="button"
              class="wb-permission-trigger"
              aria-label="路书权限与分享"
              aria-haspopup="dialog"
              :aria-expanded="permissionsOpen"
              aria-controls="workbench-permissions"
              @click="openPermissions"
            >
              <svg
                viewBox="0 0 20 20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                aria-hidden="true"
              >
                <rect x="4" y="9" width="12" height="8" rx="2" />
                <path d="M6.5 9V6a3.5 3.5 0 0 1 7 0v3M10 12v2" /></svg
              >{{ trip.isPublic ? "已发布快照" : "私密路书" }}
            </button>
          </div>
          <div class="wb-toolbar">
            <span
              v-if="saveState !== 'idle'"
              class="wb-save-feedback"
              :data-state="saveState"
              role="status"
              aria-live="polite"
              aria-atomic="true"
              :title="saveAnnouncement"
            >
              <span v-if="saveState === 'saved'" aria-hidden="true">✓</span
              >{{
                saveState === "error"
                  ? "保存失败，请查看提示"
                  : saveAnnouncement
              }}
            </span>
            <button
              v-if="editable"
              class="button wb-save-button"
              :disabled="busy || !dirty"
              :aria-busy="saveState === 'saving'"
              @click="save"
            >
              <svg
                ref="saveGlyph"
                class="wb-save-glyph"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path
                  class="wb-save-idle"
                  d="M5 3h12l4 4v14H3V3h2Zm2 0v6h10V3M7 21v-8h10v8"
                />
                <circle
                  class="wb-save-spinner"
                  cx="12"
                  cy="12"
                  r="8"
                  stroke-dasharray="30 20"
                />
                <path class="wb-save-check" d="m5 12 4.5 4.5L19 7" /></svg
              >{{
                saveState === "saving"
                  ? "保存中…"
                  : saveState === "saved" && !dirty
                    ? "已保存"
                    : "保存路书"
              }}</button
            ><button class="button secondary" :disabled="busy" @click="history">
              历史版本
            </button>
            <details
              class="wb-more-actions"
              @click="closeMoreMenu"
              @keydown.esc.stop.prevent="closeMoreMenu"
            >
              <summary @click.prevent.stop="toggleMoreMenu">更多操作</summary>
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
        <div v-if="!editable" class="notice">
          你以只读成员身份查看这份路书。
        </div>
        <WorkbenchDock
          :model-value="workspaceView"
          :disabled="busy || loading"
          @update:model-value="selectWorkspace"
        />
      </div>
    </template>
    <template #canvas="{ sceneVisible }"
      ><GeoCanvas
        :inline-map="!sceneVisible"
        :trip="trip"
        :day="day"
        :dirty="dirty"
        :active="workspaceView === 'plan'"
        :controls-open="workspaceView === 'plan' && assistantView === 'map'"
        :selected-node="selectedNode"
        :node-key="nodeKey"
        @close="closeTool"
        @adjust="selectAssistant('analysis')"
        @select="
          (item) => {
            selectedNode = nodeKey(item);
            assistantView = 'insight';
            insightOpen = false;
            void updateWorkspaceQuery();
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
            if (value && assistantView === 'map') assistantView = 'insight';
            insightOpen = false;
            void updateWorkspaceQuery();
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
        :active="
          workspaceView === 'plan' &&
          assistantView === 'insight' &&
          !insightOpen
        "
        :error="error"
        @save="save"
        @remove-day="removeDay"
        @poi="selectPoi"
        @record="record"
    /></template>
    <template #tool-navigation>
      <nav class="wb-tool-shortcuts" aria-label="右侧工具快捷入口">
        <button
          type="button"
          aria-label="工具：洞察"
          :aria-pressed="
            rightPanelVisible && assistantView === 'insight' && !selectedNode
          "
          :disabled="analysisBusy"
          @click="openContext"
        >
          洞察
        </button>
        <button
          type="button"
          aria-label="工具：AI 调整"
          :aria-pressed="modal === 'ai'"
          :disabled="
            !editable || busy || loading || dirty || !s.capabilities.ai
          "
          @click="openAiAdjustment"
        >
          AI 调整
        </button>
        <button
          type="button"
          aria-label="工具：路线天气"
          :aria-pressed="assistantView === 'map'"
          :disabled="analysisBusy"
          @click="selectAssistant('map')"
        >
          路线天气
        </button>
        <button
          type="button"
          aria-label="工具：诊断"
          :aria-pressed="assistantView === 'analysis'"
          :disabled="busy || loading || dirty"
          @click="selectAssistant('analysis')"
        >
          诊断
        </button>
      </nav>
    </template>
    <template #context="{ close }"
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
        @close="close"
      />
      <WorkbenchBudget :trip="trip" />
    </template>
    <template #tools-workspace>
      <TravelTools
        v-if="visitedWorkspaces.tools"
        ref="tools"
        :trip="trip"
        :readonly="!editable || busy || loading"
        :workspace="true"
      />
    </template>
    <template #members-workspace>
      <Members
        v-if="visitedWorkspaces.members"
        :trip-id="trip.id!"
        :owner="owner"
        :workspace="true"
        :leave-blocked="dirty || busy || loading"
        @busy="membersBusy = $event"
        @left="membershipLeft"
      />
    </template>
    <template #read-workspace>
      <PreviewWorkspace
        v-if="visitedWorkspaces.read"
        ref="previewWorkspace"
        v-model:day-index="dayIndex"
        :trip="trip"
        :active="workspaceView === 'read'"
        :busy="busy"
        :dirty="dirty"
        :can-share="owner"
        :can-print="true"
        :can-export="true"
        @share="showShares"
        @print="print"
        @export="exportImage"
      />
    </template>
    <template #tools>
      <Teleport defer to="#workbench-tools-host">
        <section
          v-show="assistantView === 'analysis'"
          class="wb-tool-panel"
          tabindex="-1"
          aria-label="行程诊断面板"
          @keydown.esc.stop="closeTool"
        >
          <header class="wb-tool-heading">
            <h2>行程诊断</h2>
            <button
              type="button"
              class="wb-text-button"
              :disabled="analysisBusy"
              @click="closeTool"
            >
              返回行程洞察
            </button>
          </header>
          <div id="workbench-analysis-host"></div>
        </section>
      </Teleport>
      <Teleport
        defer
        :to="
          assistantView === 'analysis'
            ? '#workbench-analysis-host'
            : '#workbench-adjustment-host'
        "
      >
        <ItineraryAnalysis
          :insight="assistantView !== 'analysis'"
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
  </WorkbenchShell>
  <Teleport to="body">
    <Transition
      :css="false"
      @enter="enterModal"
      @leave="leaveModal"
      @after-leave="afterPermissionsLeave"
    >
      <dialog
        v-if="permissionsOpen && trip"
        id="workbench-permissions"
        ref="permissionsDialog"
        class="wb-permissions-v5"
        aria-labelledby="workbench-permissions-title"
        @cancel.prevent="closePermissions"
        @click="onPermissionsBackdrop"
      >
        <header class="wb-permissions-heading">
          <div>
            <small>ACCESS &amp; SHARING</small>
            <h2 id="workbench-permissions-title">路书权限与分享</h2>
          </div>
          <button
            type="button"
            data-permissions-close
            @click="closePermissions"
          >
            关闭
          </button>
        </header>
        <section class="wb-permission-row">
          <h3>当前访问权限 · {{ accessLabel }}</h3>
          <p>
            这份路书的编辑原稿仅向所有者和已加入的同行成员开放。{{
              owner
                ? "你可以管理公开快照、分享链接与同行成员。"
                : editable
                  ? "你可以编辑原稿；发布和分享由所有者管理。"
                  : "你可以阅读原稿，无法修改或管理发布与分享。"
            }}
          </p>
        </section>
        <section class="wb-permission-row">
          <h3>旅行广场 · {{ trip.isPublic ? "已发布快照" : "尚未公开" }}</h3>
          <p>
            公开的是发布时的路书快照，之后的原稿修改不会自动同步。下架后，已有独立分享链接仍然有效。
          </p>
          <div v-if="owner" class="wb-permission-actions">
            <button
              type="button"
              class="wb-permission-action wb-permission-primary"
              :disabled="busy || loading || dirty"
              @click="publish"
            >
              {{ trip.isPublic ? "更新公开快照" : "发布到旅行广场" }}
            </button>
            <button
              v-if="trip.isPublic"
              type="button"
              class="wb-permission-action"
              :disabled="busy || loading || dirty"
              @click="unpublish"
            >
              从广场下架
            </button>
          </div>
          <p v-else>发布与下架仅限路书所有者操作。</p>
        </section>
        <section class="wb-permission-row">
          <h3>独立分享链接</h3>
          <p>
            链接分享的是创建时的快照。链接持有者可阅读快照，不会因此获得原稿编辑权限；所有者可随时撤销。
          </p>
          <button
            v-if="owner"
            type="button"
            class="wb-permission-action"
            :disabled="busy || loading || dirty"
            @click="launchPermissionAction('shares')"
          >
            管理分享链接
          </button>
          <p v-else>分享链接由路书所有者管理。</p>
        </section>
        <section class="wb-permission-row">
          <h3>同行成员</h3>
          <p>
            {{
              owner
                ? "在成员面板邀请可编辑或只读同行成员，并管理实际加入的成员。"
                : "查看实际同行成员与各自权限；你的权限由路书所有者决定。"
            }}
          </p>
          <button
            type="button"
            class="wb-permission-action"
            :disabled="busy || loading"
            @click="launchPermissionAction('members')"
          >
            {{ owner ? "管理同行成员" : "查看同行成员" }}
          </button>
        </section>
        <p v-if="owner && dirty" class="wb-permission-notice">
          原稿有未保存修改，请先保存，再发布或管理分享。
        </p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </dialog>
    </Transition>
  </Teleport>
  <Teleport to="body"
    ><Transition
      :css="false"
      @enter="enterModal"
      @leave="leaveModal"
      @after-leave="afterModalLeave"
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
      </dialog></Transition
    ></Teleport
  >
</template>
