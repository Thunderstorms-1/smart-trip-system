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
import { useMotionPreference } from "../useMotionPreference";

gsap.registerPlugin(Flip);
const props = defineProps<{
  modelValue: string;
  disabled: boolean;
  insightActive?: boolean;
  insightExpanded?: boolean;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: string];
  insight: [];
}>();
const entries = [
  { id: "plan", label: "行程" },
  { id: "tools", label: "记账与行李" },
  { id: "members", label: "同行成员" },
  { id: "read", label: "路书预览" },
];
const activeKey = computed(() => props.modelValue);
const viewport = ref<HTMLElement>();
const track = ref<HTMLElement>();
const indicator = ref<HTMLElement>();
const { reducedMotion } = useMotionPreference();
let flip: gsap.core.Timeline | undefined;
let observer: ResizeObserver | undefined;
let frame = 0;
let mounted = false;
let positioned = false;
let animateNext = false;
let lastTarget:
  | { left: number; top: number; width: number; height: number }
  | undefined;

function activeButton() {
  return Array.from(
    track.value?.querySelectorAll<HTMLButtonElement>("[data-dock-key]") || [],
  ).find((button) => button.dataset.dockKey === activeKey.value);
}
function stop() {
  if (indicator.value) Flip.killFlipsOf(indicator.value, false);
  flip?.kill();
  flip = undefined;
}
function place(animate: boolean) {
  const surface = indicator.value,
    parent = track.value,
    button = activeButton();
  if (!surface || !parent || !button || !mounted) return;
  const parentRect = parent.getBoundingClientRect(),
    target = button.getBoundingClientRect();
  if (!target.width || !target.height) return;
  const destination = {
    left: target.left - parentRect.left,
    top: target.top - parentRect.top,
    width: target.width,
    height: target.height,
  };
  // Scroll/ResizeObserver can repeat the same measurement after a selection;
  // do not interrupt the in-flight Flip unless the actual destination changed.
  if (
    !animate &&
    positioned &&
    lastTarget &&
    Math.abs(destination.left - lastTarget.left) < 0.25 &&
    Math.abs(destination.top - lastTarget.top) < 0.25 &&
    Math.abs(destination.width - lastTarget.width) < 0.25 &&
    Math.abs(destination.height - lastTarget.height) < 0.25
  )
    return;
  lastTarget = destination;
  // Capture the displayed position before interrupting a rapid previous selection.
  const displayed = surface.getBoundingClientRect();
  stop();
  gsap.set(surface, { clearProps: "transform" });
  if (positioned && animate && !reducedMotion.value) {
    gsap.set(surface, {
      left: displayed.left - parentRect.left,
      top: displayed.top - parentRect.top,
      width: displayed.width,
      height: displayed.height,
    });
  }
  const state =
    positioned && animate && !reducedMotion.value
      ? Flip.getState(surface, { simple: true })
      : undefined;
  gsap.set(surface, {
    left: target.left - parentRect.left,
    top: target.top - parentRect.top,
    width: target.width,
    height: target.height,
    visibility: "visible",
  });
  positioned = true;
  if (state) {
    flip = Flip.from(state, {
      duration: 0.36,
      ease: "power3.out",
      scale: false,
      onComplete: () => {
        flip = undefined;
      },
    });
  }
}
function schedule(animate = false) {
  animateNext ||= animate;
  if (frame || !mounted) return;
  frame = requestAnimationFrame(() => {
    frame = 0;
    const shouldAnimate = animateNext;
    animateNext = false;
    place(shouldAnimate);
  });
}
function revealSelected() {
  const view = viewport.value,
    button = activeButton();
  if (!view || !button) return;
  const outer = view.getBoundingClientRect(),
    inner = button.getBoundingClientRect();
  const delta =
    inner.left < outer.left + 6
      ? inner.left - outer.left - 6
      : inner.right > outer.right - 6
        ? inner.right - outer.right + 6
        : 0;
  if (delta) view.scrollLeft += delta;
}
function remeasure() {
  schedule();
}
function select(value: string) {
  if (!props.disabled) emit("update:modelValue", value);
}
function keyboard(event: KeyboardEvent) {
  if (props.disabled || event.altKey || event.ctrlKey || event.metaKey) return;
  const buttons = Array.from(
    track.value?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ||
      [],
  );
  const index = buttons.indexOf(event.target as HTMLButtonElement);
  if (index < 0) return;
  const next =
    event.key === "ArrowRight"
      ? (index + 1) % buttons.length
      : event.key === "ArrowLeft"
        ? (index + buttons.length - 1) % buttons.length
        : event.key === "Home"
          ? 0
          : event.key === "End"
            ? buttons.length - 1
            : -1;
  if (next < 0) return;
  event.preventDefault();
  buttons[next]?.focus({ preventScroll: true });
  buttons[next]?.click();
}
watch(activeKey, async () => {
  await nextTick();
  if (!mounted) return;
  revealSelected();
  schedule(true);
});
watch(reducedMotion, () => {
  stop();
  positioned = false;
  schedule();
});
onMounted(() => {
  mounted = true;
  schedule();
  viewport.value?.addEventListener("scroll", remeasure, { passive: true });
  window.addEventListener("resize", remeasure, { passive: true });
  if (typeof ResizeObserver !== "undefined") {
    observer = new ResizeObserver(remeasure);
    if (track.value) {
      observer.observe(track.value);
      track.value
        .querySelectorAll("button")
        .forEach((button) => observer!.observe(button));
    }
  }
  void document.fonts?.ready.then(() => {
    if (mounted) schedule();
  });
});
onBeforeUnmount(() => {
  mounted = false;
  cancelAnimationFrame(frame);
  observer?.disconnect();
  viewport.value?.removeEventListener("scroll", remeasure);
  window.removeEventListener("resize", remeasure);
  stop();
});
</script>

<template>
  <nav class="wb-dock-v5" aria-label="工作空间" data-testid="workbench-dock">
    <div ref="viewport" class="wb-dock-viewport">
      <div ref="track" class="wb-dock-track" @keydown="keyboard">
        <span
          ref="indicator"
          class="wb-dock-selected"
          aria-hidden="true"
        ></span>
        <button
          v-for="entry in entries"
          :key="entry.id"
          type="button"
          :data-wb-tab="entry.id"
          :data-dock-key="entry.id"
          :class="{ active: activeKey === entry.id }"
          :aria-pressed="activeKey === entry.id"
          :disabled="disabled"
          @click="select(entry.id)"
        >
          {{ entry.label }}
        </button>
      </div>
    </div>
  </nav>
</template>
