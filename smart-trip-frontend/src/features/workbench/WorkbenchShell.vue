<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { gsap } from "gsap";
import { Flip } from "gsap/Flip";
import WorkbenchGlassLight from "./WorkbenchGlassLight.vue";
gsap.registerPlugin(Flip);
const props = defineProps<{
  destination: string;
  expanded: boolean;
  toolOpen: boolean;
  insightOpen: boolean;
  inspectorOpen: boolean;
  geoOpen: boolean;
  panelBusy?: boolean;
  workspaceView?: "plan" | "tools" | "members" | "read";
}>();
const emit = defineEmits<{
  "close-panels": [];
  "open-context": [];
  "panel-visible": [visible: boolean];
  "reading-mode": [long: boolean];
}>();
const layoutKey = "SMARTTRIP_WORKBENCH_LAYOUT_V5";
function readLayout() {
  try {
    const value = JSON.parse(localStorage.getItem(layoutKey) || "{}");
    return {
      focus: value?.focus === true,
      collapsed: value?.collapsed === true,
    };
  } catch {
    return { focus: false, collapsed: false };
  }
}
const planActive = computed(() => (props.workspaceView || "plan") === "plan");
const longReading = ref(false);
watch(longReading, (value) => emit("reading-mode", value));
function toggleReading() {
  if (!props.panelBusy) longReading.value = !longReading.value;
}
function retireWorkspace(element: Element) {
  (element as HTMLElement).inert = true;
  element.setAttribute("aria-hidden", "true");
}
function enterWorkspace(element: Element) {
  (element as HTMLElement).inert = false;
  element.removeAttribute("aria-hidden");
}
const saved = readLayout();
const focusMode = ref(saved.focus),
  collapsed = ref(saved.collapsed);
const viewport = ref(window.innerWidth),
  dismissedGeo = ref(false);
const workspace = ref<HTMLElement>(),
  timeline = ref<HTMLElement>(),
  rail = ref<HTMLElement>();
const rightLayout = ref<HTMLElement>(),
  rightPanel = ref<HTMLElement>(),
  panelToggle = ref<HTMLButtonElement>();
const layoutAnimating = ref(false);
const preference = window.matchMedia("(prefers-reduced-motion: reduce)");
const reduced = ref(preference.matches);
const docked = computed(
  () =>
    viewport.value > 1200 &&
    !focusMode.value &&
    !props.inspectorOpen &&
    planActive.value,
);
const explicitPanel = computed(
  () =>
    props.toolOpen || props.inspectorOpen || props.geoOpen || props.insightOpen,
);
const panelVisible = computed(
  () =>
    planActive.value &&
    ((explicitPanel.value && !dismissedGeo.value) ||
      (docked.value && !collapsed.value)),
);
const overlay = computed(() => !docked.value);
const contextActive = computed(
  () => !props.toolOpen && !props.inspectorOpen && !props.geoOpen,
);
const gridStyle = computed(() => ({
  "--wb-columns":
    viewport.value <= 900
      ? "minmax(0,1fr)"
      : docked.value && panelVisible.value
        ? "clamp(200px,14vw,230px) minmax(0,1fr) clamp(340px,25vw,400px)"
        : "clamp(200px,14vw,230px) minmax(0,1fr)",
  "--wb-side-width": "clamp(340px,27vw,400px)",
}));
function persist() {
  try {
    localStorage.setItem(
      layoutKey,
      JSON.stringify({ focus: focusMode.value, collapsed: collapsed.value }),
    );
  } catch {
    /* Layout preference is optional in private browsing. */
  }
}
watch([focusMode, collapsed], persist);
watch(
  () => [props.toolOpen, props.inspectorOpen, props.geoOpen, props.insightOpen],
  (value, before) => {
    if (value.some((on, index) => on && !before?.[index])) {
      dismissedGeo.value = false;
      collapsed.value = false;
    }
  },
);
watch(panelVisible, (visible) => emit("panel-visible", visible), {
  immediate: true,
});
let disposed = false,
  focusEpoch = 0,
  layoutEpoch = 0;
let layoutTween: gsap.core.Timeline | undefined;
let rightTween: gsap.core.Tween | undefined;
let leavingGlass: HTMLElement | undefined;
let leavingTween: gsap.core.Tween | undefined;
const flipTargets = () =>
  [rail.value, timeline.value].filter((value): value is HTMLElement =>
    Boolean(value),
  );
function cancelLayout() {
  const targets = flipTargets();
  if (targets.length) Flip.killFlipsOf(targets, true);
  layoutTween?.kill();
  layoutTween = undefined;
  rightTween?.revert();
  rightTween = undefined;
  leavingTween?.kill();
  leavingTween = undefined;
  leavingGlass?.remove();
  leavingGlass = undefined;
  layoutAnimating.value = false;
}
watch(
  planActive,
  () => {
    layoutEpoch++;
    cancelLayout();
  },
  { flush: "sync" },
);
// FLIP owns only the column wrappers. Vue transitions own the content inside them.
watch(
  [focusMode, panelVisible, docked, longReading],
  async (value, before) => {
    const current = ++layoutEpoch;
    cancelLayout();
    if (
      !workspace.value ||
      !planActive.value ||
      longReading.value ||
      before?.[3] ||
      reduced.value ||
      viewport.value <= 900
    )
      return;
    const targets = flipTargets();
    const state = Flip.getState(targets, { simple: true });
    const oldRight = rightLayout.value?.getBoundingClientRect();
    const hiding = before?.[1] && !value[1];
    if (hiding && oldRight && oldRight.width > 0) {
      const glass = document.createElement("div");
      glass.className = "wb-panel-departure";
      glass.setAttribute("aria-hidden", "true");
      glass.inert = true;
      Object.assign(glass.style, {
        left: oldRight.left + "px",
        top: oldRight.top + "px",
        width: oldRight.width + "px",
        height: oldRight.height + "px",
      });
      workspace.value.closest(".workbench-v5")?.append(glass);
      leavingGlass = glass;
    }
    await nextTick();
    if (disposed || current !== layoutEpoch) return;
    if (reduced.value) {
      cancelLayout();
      return;
    }
    layoutAnimating.value = true;
    layoutTween = Flip.from(state, {
      targets,
      duration: 0.36,
      ease: "power3.inOut",
      scale: true,
      nested: true,
      prune: true,
      onComplete: () => {
        if (current === layoutEpoch) layoutAnimating.value = false;
      },
    });
    if (leavingGlass) {
      const own = leavingGlass;
      leavingTween = gsap.to(own, {
        x: 28,
        scaleX: 0.96,
        opacity: 0,
        duration: 0.24,
        ease: "power2.in",
        onComplete: () => {
          own.remove();
          if (leavingGlass === own) leavingGlass = undefined;
        },
      });
    }
    if (
      value[1] &&
      (!before?.[1] || value[2] !== before?.[2]) &&
      rightLayout.value
    )
      rightTween = gsap.fromTo(
        rightLayout.value,
        { x: 22, opacity: 0.15 },
        {
          x: 0,
          opacity: 1,
          duration: 0.3,
          ease: "power3.out",
          clearProps: "transform,opacity",
        },
      );
  },
  { flush: "pre" },
);
async function toggleFocus() {
  if (props.panelBusy) return;
  focusMode.value = !focusMode.value;
  if (focusMode.value) {
    dismissedGeo.value = false;
    emit("close-panels");
  }
}
async function closePanel() {
  if (props.panelBusy) return;
  const current = ++focusEpoch;
  collapsed.value = true;
  if (props.geoOpen) dismissedGeo.value = true;
  else emit("close-panels");
  await nextTick();
  if (!disposed && current === focusEpoch)
    panelToggle.value?.focus({ preventScroll: true });
}
function togglePanel() {
  if (props.panelBusy) return;
  if (panelVisible.value) {
    void closePanel();
    return;
  }
  collapsed.value = false;
  dismissedGeo.value = false;
  if (!explicitPanel.value) emit("open-context");
}
watch(
  panelVisible,
  async (visible, previous) => {
    if (!visible) return;
    const current = ++focusEpoch;
    if (
      !overlay.value ||
      props.inspectorOpen ||
      (!previous && !explicitPanel.value)
    )
      return;
    await nextTick();
    if (
      !disposed &&
      current === focusEpoch &&
      panelVisible.value &&
      !props.inspectorOpen
    )
      rightPanel.value
        ?.querySelector<HTMLElement>(
          "button:not(:disabled), input:not(:disabled), [tabindex='0']",
        )
        ?.focus({ preventScroll: true });
  },
  { flush: "post" },
);
function trapPanelTab(event: KeyboardEvent) {
  if (
    !overlay.value ||
    !panelVisible.value ||
    props.inspectorOpen ||
    !rightPanel.value
  )
    return;
  const nodes = [
    ...rightPanel.value.querySelectorAll<HTMLElement>(
      "button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex='0']",
    ),
  ].filter(
    (node) => node.getClientRects().length > 0 && !node.closest("[inert]"),
  );
  const first = nodes[0],
    last = nodes.at(-1),
    active = document.activeElement;
  if (!first || !last) return;
  if (
    !rightPanel.value.contains(active) ||
    (event.shiftKey && active === first) ||
    (!event.shiftKey && active === last)
  ) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  }
}
function measure() {
  const width = window.innerWidth;
  if (viewport.value !== width) {
    layoutEpoch++;
    cancelLayout();
    viewport.value = width;
  }
}
function preferenceChanged(event: MediaQueryListEvent) {
  reduced.value = event.matches;
  if (event.matches) {
    layoutEpoch++;
    cancelLayout();
  }
}
onMounted(() => {
  window.addEventListener("resize", measure, { passive: true });
  preference.addEventListener("change", preferenceChanged);
});
onBeforeUnmount(() => {
  disposed = true;
  focusEpoch++;
  layoutEpoch++;
  cancelLayout();
  window.removeEventListener("resize", measure);
  preference.removeEventListener("change", preferenceChanged);
});
</script>
<template>
  <div
    class="workbench-v3 workbench-v4 workbench-v5"
    :class="{
      'timeline-expanded': expanded,
      'tool-open': toolOpen,
      'insight-open': insightOpen,
      'inspector-open': inspectorOpen,
      'geo-open': geoOpen,
      'is-focus': focusMode,
      'is-right-collapsed': !panelVisible,
      'is-overlay-panel': overlay,
      'is-layout-animating': layoutAnimating,
      'is-inspector-panel': inspectorOpen,
      'is-long-reading': longReading && planActive,
    }"
  >
    <div class="wb-shell-header"><slot name="header" /></div>
    <div class="wb-workspace-stage">
      <Transition
        name="wb-workspace-change"
        @before-leave="retireWorkspace"
        @before-enter="enterWorkspace"
      >
        <div
          v-show="planActive"
          ref="workspace"
          class="workbench-workspace"
          :style="gridStyle"
          data-workspace="plan"
        >
          <div ref="rail" class="wb-rail-region"><slot name="rail" /></div>
          <div
            id="workbench-timeline-region"
            ref="timeline"
            class="wb-timeline-region"
          >
            <nav class="wb-editor-toolbar" aria-label="行程视图选项">
              <button
                data-testid="wb-reading-toggle"
                type="button"
                :aria-pressed="longReading"
                :disabled="panelBusy"
                @click="toggleReading"
              >
                {{ longReading ? "全屏编辑" : "长卷阅读" }}
              </button>
              <span class="wb-editor-toolbar-spacer"></span>
              <button
                data-testid="wb-focus-toggle"
                type="button"
                :aria-pressed="focusMode"
                :disabled="panelBusy"
                @click="toggleFocus"
              >
                {{ focusMode ? "退出专注" : "专注编辑" }}
              </button>
              <button
                ref="panelToggle"
                data-testid="wb-panel-toggle"
                type="button"
                :aria-expanded="panelVisible"
                aria-controls="workbench-right-panel"
                :disabled="panelBusy"
                @click="togglePanel"
              >
                {{ panelVisible ? "收起辅助" : "展开辅助"
                }}<span aria-hidden="true">{{ panelVisible ? "→" : "←" }}</span>
              </button>
            </nav>
            <div class="wb-timeline-surface"><slot name="timeline" /></div>
          </div>
          <div class="wb-planning-canvas wb-source-host" aria-hidden="true">
            <slot name="canvas" :scene-visible="false" />
          </div>
          <div ref="rightLayout" v-show="panelVisible" class="wb-right-layout">
            <aside
              id="workbench-right-panel"
              ref="rightPanel"
              class="wb-right-region"
              :class="{
                'is-native-inspector': inspectorOpen && viewport <= 700,
              }"
              :aria-label="inspectorOpen ? '活动编辑面板' : '行程辅助面板'"
              @keydown.tab="trapPanelTab"
              @keydown.esc="!inspectorOpen && closePanel()"
            >
              <header class="wb-right-heading">
                <span>{{ inspectorOpen ? "编辑行程活动" : "行程辅助" }}</span
                ><button
                  type="button"
                  :disabled="panelBusy"
                  aria-label="收起功能面板"
                  @click="closePanel"
                >
                  收起 <span aria-hidden="true">→</span>
                </button>
              </header>
              <div v-show="!inspectorOpen" class="wb-assistant-navigation">
                <slot name="tool-navigation" />
              </div>
              <div class="wb-right-body">
                <Transition name="wb-panel-content"
                  ><aside
                    v-show="contextActive"
                    id="workbench-insight"
                    class="wb-context-region"
                    aria-label="行程洞察面板"
                  >
                    <slot name="context" :close="closePanel" /></aside
                ></Transition>
                <Transition name="wb-panel-content"
                  ><aside
                    v-show="inspectorOpen && !toolOpen"
                    id="workbench-inspector-host"
                    class="wb-inspector-region"
                    aria-label="选中安排编辑面板"
                  ></aside
                ></Transition>
                <Transition name="wb-panel-content"
                  ><div
                    v-show="toolOpen"
                    id="workbench-tools-host"
                    class="wb-tools-region"
                  ></div
                ></Transition>
                <Transition name="wb-panel-content"
                  ><div
                    v-show="geoOpen"
                    id="workbench-geo-host"
                    class="wb-geo-region"
                  ></div
                ></Transition>
              </div>
            </aside>
          </div>
          <WorkbenchGlassLight :suspended="layoutAnimating || !planActive" />
        </div>
      </Transition>
      <Transition
        name="wb-workspace-change"
        @before-leave="retireWorkspace"
        @before-enter="enterWorkspace"
        ><section
          v-show="workspaceView === 'tools'"
          class="wb-full-workspace"
          data-workspace="tools"
          aria-label="记账与行李工作空间"
        >
          <slot name="tools-workspace" /></section
      ></Transition>
      <Transition
        name="wb-workspace-change"
        @before-leave="retireWorkspace"
        @before-enter="enterWorkspace"
        ><section
          v-show="workspaceView === 'members'"
          class="wb-full-workspace"
          data-workspace="members"
          aria-label="同行成员工作空间"
        >
          <slot name="members-workspace" /></section
      ></Transition>
      <Transition
        name="wb-workspace-change"
        @before-leave="retireWorkspace"
        @before-enter="enterWorkspace"
        ><section
          v-show="workspaceView === 'read'"
          class="wb-full-workspace"
          data-workspace="read"
          aria-label="路书预览工作空间"
        >
          <slot name="read-workspace" /></section
      ></Transition>
      <slot name="tools" />
    </div>
  </div>
</template>
