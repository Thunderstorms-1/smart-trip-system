<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    mode?: "manual" | "ai";
    stage?: "input" | "confirm" | "generating" | "preview";
  }>(),
  { mode: "manual", stage: "input" },
);
const surface = ref<HTMLDivElement>();
const canvas = ref<HTMLCanvasElement>();
const renderer = ref<"canvas2d" | "css">("css");
const motion = ref<"ambient" | "low" | "reduced" | "paused" | "static">(
  "static",
);

type FlowPoint = {
  x: number;
  y: number;
  nx: number;
  ny: number;
  t: number;
  half: number;
  dx: number;
  dy: number;
  vx: number;
  vy: number;
  drawX: number;
  drawY: number;
};
type Ribbon = {
  points: FlowPoint[];
  phase: number;
  speed: number;
  color: string;
  fill: CanvasGradient;
};
type Ripple = { x: number; y: number; born: number };
let ctx: CanvasRenderingContext2D | null = null;
let width = 1,
  height = 1,
  dpr = 1,
  clock = 0,
  flowClock = 0,
  lastDraw = 0,
  frameCount = 0;
let raf = 0,
  resizeTimer: ReturnType<typeof setTimeout> | undefined;
let alive = false,
  low = false,
  modeBlend = 0,
  stageBlend = 0;
let reduced: MediaQueryList | undefined, coarse: MediaQueryList | undefined;
let observer: ResizeObserver | undefined;
let ribbons: Ribbon[] = [];
let ripples: Ripple[] = [];
const pointer = {
  x: -1000,
  y: -1000,
  targetX: -1000,
  targetY: -1000,
  vx: 0,
  vy: 0,
  presence: 0,
  movedAt: 0,
  active: false,
};
const blocked = [
  "button",
  "input",
  "textarea",
  "select",
  "a",
  "label",
  "summary",
  "form",
  "nav",
  "dialog",
  '[role="button"]',
  '[role="dialog"]',
  '[contenteditable="true"]',
  ".planner-panel",
  ".planning-account",
  ".panel",
  ".paper",
  "[data-journey-interactive]",
].join(",");

function stop() {
  cancelAnimationFrame(raf);
  raf = 0;
  lastDraw = 0;
}
function canAnimate() {
  return alive && !!ctx && !document.hidden && !reduced?.matches;
}
function refreshMotion() {
  motion.value = document.hidden
    ? "paused"
    : !ctx
      ? "static"
      : reduced?.matches
        ? "reduced"
        : low
          ? "low"
          : "ambient";
}
function buildRibbons() {
  if (!ctx) return;
  // Original sparse cubic ribbons: no imported shader, noise table or component implementation.
  const curves: Array<
    [
      number,
      number,
      number,
      number,
      number,
      number,
      number,
      number,
      number,
      string,
    ]
  > = [
    [-0.15, 0.5, 0.2, 0.93, 0.58, 0.22, 1.15, 0.55, 0.085, "124,175,218"],
    [-0.17, 0.96, 0.1, 0.19, 0.5, 1.05, 1.2, 0.39, 0.15, "100,155,207"],
    [-0.18, 0.92, 0.18, 0.45, 0.54, 0.75, 1.18, 0.84, 0.065, "158,166,206"],
    [-0.2, 1.12, 0.14, 0.62, 0.5, 0.93, 1.18, 0.72, 0.1, "221,204,176"],
  ];
  const count = low ? 36 : 62;
  ribbons = curves.map((c, index) => {
    const points: FlowPoint[] = [];
    for (let n = 0; n <= count; n++) {
      const t = n / count,
        u = 1 - t;
      const x =
        (u * u * u * c[0]! +
          3 * u * u * t * c[2]! +
          3 * u * t * t * c[4]! +
          t * t * t * c[6]!) *
        width;
      const y =
        (u * u * u * c[1]! +
          3 * u * u * t * c[3]! +
          3 * u * t * t * c[5]! +
          t * t * t * c[7]!) *
        height;
      const tx =
        (3 * u * u * (c[2]! - c[0]!) +
          6 * u * t * (c[4]! - c[2]!) +
          3 * t * t * (c[6]! - c[4]!)) *
        width;
      const ty =
        (3 * u * u * (c[3]! - c[1]!) +
          6 * u * t * (c[5]! - c[3]!) +
          3 * t * t * (c[7]! - c[5]!)) *
        height;
      const length = Math.hypot(tx, ty) || 1;
      points.push({
        x,
        y,
        nx: -ty / length,
        ny: tx / length,
        t,
        half: height * c[8]! * (0.15 + 0.85 * Math.sin(Math.PI * t)),
        dx: 0,
        dy: 0,
        vx: 0,
        vy: 0,
        drawX: x,
        drawY: y,
      });
    }
    const color = String(c[9]);
    // Broad shaded faces carry the silk; only a few fine seams remain on top.
    const fill = ctx!.createLinearGradient(
      width * 0.08,
      height * 0.32,
      width * 0.34,
      height * 0.96,
    );
    if (index === curves.length - 1) {
      fill.addColorStop(0, "rgba(255,250,240,0.08)");
      fill.addColorStop(0.26, "rgba(235,222,202,0.25)");
      fill.addColorStop(0.48, "rgba(255,252,243,0.82)");
      fill.addColorStop(0.59, "rgba(255,255,253,0.88)");
      fill.addColorStop(0.79, "rgba(247,236,216,0.40)");
      fill.addColorStop(1, "rgba(237,221,194,0.08)");
    } else {
      fill.addColorStop(0, "rgba(" + color + ",0.05)");
      fill.addColorStop(0.24, "rgba(" + color + ",0.28)");
      fill.addColorStop(0.43, "rgba(" + color + ",0.43)");
      fill.addColorStop(0.56, "rgba(229,246,255,0.65)");
      fill.addColorStop(0.67, "rgba(" + color + ",0.30)");
      fill.addColorStop(1, "rgba(" + color + ",0.055)");
    }
    return {
      points,
      color,
      fill,
      phase: index * 1.37,
      speed: 0.13 + index * 0.035,
    };
  });
}
function draw(delta: number) {
  if (!ctx || !surface.value) return;
  const context = ctx,
    scale = Math.min(2, delta * 30);
  const moving = !reduced?.matches;
  const blend = moving ? 0.045 * scale : 1;
  modeBlend += ((props.mode === "ai" ? 1 : 0) - modeBlend) * blend;
  stageBlend +=
    ((props.stage === "confirm" || props.stage === "generating" ? 1 : 0) -
      stageBlend) *
    blend;
  const present =
    moving && pointer.active && performance.now() - pointer.movedAt < 220
      ? 1
      : 0;
  pointer.presence += (present - pointer.presence) * Math.min(1, 0.16 * scale);
  pointer.x += (pointer.targetX - pointer.x) * Math.min(1, 0.17 * scale);
  pointer.y += (pointer.targetY - pointer.y) * Math.min(1, 0.17 * scale);
  pointer.vx *= Math.pow(0.79, scale);
  pointer.vy *= Math.pow(0.79, scale);
  ripples = ripples.filter((ripple) => clock - ripple.born < 1.9);
  context.setTransform(dpr, 0, 0, dpr, 0, 0);
  context.clearRect(0, 0, width, height);
  const reach = Math.min(240, Math.max(145, width * 0.14));
  for (const ribbon of ribbons) {
    for (const point of ribbon.points) {
      const distance2 = (point.x - pointer.x) ** 2 + (point.y - pointer.y) ** 2;
      const force = pointer.presence * Math.exp(-distance2 / (reach * reach));
      point.vx +=
        ((pointer.x - point.x) * 0.0025 + pointer.vx * 0.04) * force * scale;
      point.vy +=
        ((pointer.y - point.y) * 0.0025 + pointer.vy * 0.04) * force * scale;
      point.vx = (point.vx - point.dx * 0.07 * scale) * Math.pow(0.8, scale);
      point.vy = (point.vy - point.dy * 0.07 * scale) * Math.pow(0.8, scale);
      point.dx = Math.max(-32, Math.min(32, point.dx + point.vx * scale));
      point.dy = Math.max(-32, Math.min(32, point.dy + point.vy * scale));
      let echo = 0;
      for (const ripple of ripples) {
        const age = (clock - ripple.born) / 1.9,
          radius = 18 + age * 280;
        const distance = Math.hypot(point.x - ripple.x, point.y - ripple.y);
        echo += Math.exp(-(((distance - radius) / 26) ** 2)) * (1 - age) * 4;
      }
      const wave =
        Math.sin(
          flowClock * ribbon.speed +
            modeBlend * 0.18 +
            point.t * 5.7 +
            ribbon.phase,
        ) *
        9 *
        Math.sin(Math.PI * point.t);
      const lift =
        wave + echo - stageBlend * height * 0.018 * Math.sin(Math.PI * point.t);
      point.drawX = point.x + point.nx * lift + point.dx;
      point.drawY = point.y + point.ny * lift + point.dy;
    }
    const points = ribbon.points;
    // Shade across each curved normal, so both sides dissolve softly instead
    // of reading as a uniformly filled polygon. Strips share exact boundaries.
    const strips = 8;
    for (let strip = 0; strip < strips; strip++) {
      const edgeA = -1 + (strip / strips) * 2;
      const edgeB = -1 + ((strip + 1) / strips) * 2;
      const cross = (strip + 0.5) / strips;
      context.globalAlpha = Math.sin(Math.PI * cross) ** 1.65;
      context.beginPath();
      points.forEach((point, index) => {
        const half = point.half * (1 - stageBlend * 0.1);
        const x = point.drawX + point.nx * half * edgeA;
        const y = point.drawY + point.ny * half * edgeA;
        if (!index) context.moveTo(x, y);
        else context.lineTo(x, y);
      });
      for (let i = points.length - 1; i >= 0; i--) {
        const point = points[i]!;
        const half = point.half * (1 - stageBlend * 0.1);
        context.lineTo(
          point.drawX + point.nx * half * edgeB,
          point.drawY + point.ny * half * edgeB,
        );
      }
      context.closePath();
      context.fillStyle = ribbon.fill;
      context.fill();
    }
    context.globalAlpha = 1;
    // A fine offset seam suggests a turned silk edge without blur or glow.
    context.beginPath();
    points.forEach((point, index) => {
      const edge = point.half * 0.62 * (1 - stageBlend * 0.1);
      const x = point.drawX + point.nx * edge;
      const y = point.drawY + point.ny * edge;
      if (!index) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    context.strokeStyle = "rgba(255,255,251,.28)";
    context.lineWidth = 0.7;
    context.stroke();
    const threads = low ? 2 : 4;
    for (let line = 0; line <= threads; line++) {
      const across = (line / threads - 0.5) * 1.82;
      context.beginPath();
      points.forEach((point, index) => {
        const x = point.drawX + point.nx * point.half * across,
          y = point.drawY + point.ny * point.half * across;
        if (!index) context.moveTo(x, y);
        else context.lineTo(x, y);
      });
      context.strokeStyle =
        line === Math.floor(threads * 0.5)
          ? "rgba(255,255,255,.20)"
          : "rgba(" +
            ribbon.color +
            "," +
            (line % 3 === 0 ? 0.075 : 0.045) +
            ")";
      context.lineWidth = line % 3 === 0 ? 0.75 : 0.45;
      context.stroke();
    }
  }
  for (const ripple of ripples) {
    const age = (clock - ripple.born) / 1.9;
    context.beginPath();
    context.arc(ripple.x, ripple.y, 18 + age * 280, 0, Math.PI * 2);
    context.strokeStyle = "rgba(106,155,202," + 0.18 * (1 - age) ** 2 + ")";
    context.lineWidth = 1.2;
    context.stroke();
  }
  const particles = low ? 6 : 12;
  for (let i = 0; i < particles; i++) {
    const seed = (Math.sin(i * 127.1 + 3) * 43758.5453) % 1;
    const x =
      ((i * 0.6180339 + 0.18 + clock * 0.0013 * ((i % 3) + 1)) % 1) * width;
    const y =
      (0.2 + Math.abs(seed) * 0.74) * height + Math.sin(clock * 0.17 + i) * 7;
    const pull =
      pointer.presence *
      Math.exp(
        -((x - pointer.x) ** 2 + (y - pointer.y) ** 2) / (reach * reach),
      );
    context.beginPath();
    context.arc(
      x + (pointer.x - x) * pull * 0.035,
      y + (pointer.y - y) * pull * 0.035,
      i % 4 === 0 ? 1.6 : 0.85,
      0,
      Math.PI * 2,
    );
    context.fillStyle =
      i % 5 === 0 ? "rgba(192,165,126,.42)" : "rgba(105,155,202,.32)";
    context.fill();
  }
  surface.value.dataset.frame = String(++frameCount);
  surface.value.dataset.influence = pointer.presence.toFixed(3);
  surface.value.dataset.pointerX = Math.round(pointer.targetX).toString();
  surface.value.dataset.pointerY = Math.round(pointer.targetY).toString();
  surface.value.dataset.ripples = String(ripples.length);
}
function tick(now: number) {
  raf = 0;
  if (!canAnimate()) return;
  const interval = 1000 / (low ? 18 : 30);
  if (!lastDraw || now - lastDraw >= interval - 0.5) {
    const delta = lastDraw ? Math.min(0.08, (now - lastDraw) / 1000) : 1 / 30;
    lastDraw = now;
    clock += delta;
    flowClock += delta * (1 - modeBlend * 1.65);
    draw(delta);
  }
  raf = requestAnimationFrame(tick);
}
function wake() {
  if (canAnimate() && !raf) raf = requestAnimationFrame(tick);
}
function resize(force = false) {
  if (!alive || !canvas.value || !surface.value) return;
  const previousWidth = width,
    previousHeight = height,
    previousDpr = dpr,
    previousLow = low;
  const bounds = surface.value.getBoundingClientRect();
  width = Math.max(1, bounds.width);
  height = Math.max(1, bounds.height);
  const device = navigator as Navigator & { deviceMemory?: number };
  low =
    width <= 760 ||
    !!coarse?.matches ||
    (device.deviceMemory != null && device.deviceMemory <= 4) ||
    (navigator.hardwareConcurrency > 0 && navigator.hardwareConcurrency <= 4);
  dpr = Math.min(
    window.devicePixelRatio || 1,
    1.5,
    Math.sqrt((low ? 900000 : 2200000) / (width * height)),
  );
  if (
    !force &&
    width === previousWidth &&
    height === previousHeight &&
    dpr === previousDpr &&
    low === previousLow
  ) {
    refreshMotion();
    wake();
    return;
  }
  canvas.value.width = Math.max(1, Math.round(width * dpr));
  canvas.value.height = Math.max(1, Math.round(height * dpr));
  surface.value.dataset.density = low ? "low" : "standard";
  surface.value.dataset.maxFps = low ? "18" : "30";
  buildRibbons();
  refreshMotion();
  if (!document.hidden) draw(1 / 30);
  wake();
}
function scheduleResize() {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(resize, 100);
}
function allowed(event: Event) {
  return event.target instanceof Element && !event.target.closest(blocked);
}
function move(event: PointerEvent) {
  if (
    !canAnimate() ||
    event.pointerType === "touch" ||
    !allowed(event) ||
    !surface.value
  ) {
    pointer.active = false;
    return;
  }
  const rect = surface.value.getBoundingClientRect(),
    x = event.clientX - rect.left,
    y = event.clientY - rect.top;
  if (x < 0 || x > width || y < 0 || y > height) {
    pointer.active = false;
    return;
  }
  if (!pointer.active) {
    pointer.x = x;
    pointer.y = y;
    pointer.targetX = x;
    pointer.targetY = y;
  }
  pointer.vx = Math.max(-45, Math.min(45, x - pointer.targetX));
  pointer.vy = Math.max(-45, Math.min(45, y - pointer.targetY));
  pointer.targetX = x;
  pointer.targetY = y;
  pointer.active = true;
  pointer.movedAt = performance.now();
}
function click(event: MouseEvent) {
  if (!canAnimate() || event.button !== 0 || !allowed(event) || !surface.value)
    return;
  const rect = surface.value.getBoundingClientRect(),
    x = event.clientX - rect.left,
    y = event.clientY - rect.top;
  if (x < 0 || x > width || y < 0 || y > height) return;
  ripples.push({ x, y, born: clock });
  if (ripples.length > 3) ripples.shift();
}
function leave() {
  pointer.active = false;
}
function visibility() {
  stop();
  refreshMotion();
  if (!document.hidden) resize(true);
}
function preference() {
  stop();
  pointer.active = false;
  pointer.presence = 0;
  ripples = [];
  if (reduced?.matches)
    for (const ribbon of ribbons)
      for (const point of ribbon.points)
        point.dx = point.dy = point.vx = point.vy = 0;
  refreshMotion();
  if (!document.hidden) draw(1 / 30);
  wake();
}
function lost() {
  stop();
  ctx = null;
  renderer.value = "css";
  refreshMotion();
}
function restored() {
  try {
    ctx = canvas.value?.getContext("2d") || null;
  } catch {
    ctx = null;
  }
  renderer.value = ctx ? "canvas2d" : "css";
  resize(true);
}
watch(
  () => [props.mode, props.stage],
  () => {
    if (!alive || !ctx || document.hidden) return;
    if (reduced?.matches) draw(1 / 30);
    else wake();
  },
);
onMounted(() => {
  alive = true;
  reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
  coarse = window.matchMedia("(pointer: coarse)");
  restored();
  if (typeof ResizeObserver !== "undefined" && surface.value) {
    observer = new ResizeObserver(scheduleResize);
    observer.observe(surface.value);
  }
  window.addEventListener("resize", scheduleResize, { passive: true });
  window.addEventListener("pointermove", move, { passive: true });
  window.addEventListener("click", click, { passive: true });
  document.documentElement.addEventListener("pointerleave", leave, {
    passive: true,
  });
  document.addEventListener("visibilitychange", visibility);
  reduced.addEventListener("change", preference);
  coarse.addEventListener("change", scheduleResize);
  canvas.value?.addEventListener("contextlost", lost);
  canvas.value?.addEventListener("contextrestored", restored);
});
onBeforeUnmount(() => {
  alive = false;
  stop();
  clearTimeout(resizeTimer);
  observer?.disconnect();
  window.removeEventListener("resize", scheduleResize);
  window.removeEventListener("pointermove", move);
  window.removeEventListener("click", click);
  document.documentElement.removeEventListener("pointerleave", leave);
  document.removeEventListener("visibilitychange", visibility);
  reduced?.removeEventListener("change", preference);
  coarse?.removeEventListener("change", scheduleResize);
  canvas.value?.removeEventListener("contextlost", lost);
  canvas.value?.removeEventListener("contextrestored", restored);
  ribbons = [];
  ripples = [];
  ctx = null;
  if (canvas.value) {
    canvas.value.width = 1;
    canvas.value.height = 1;
  }
});
</script>

<template>
  <div
    ref="surface"
    class="journey-flow-background"
    aria-hidden="true"
    :data-renderer="renderer"
    :data-motion="motion"
    :data-mode="mode"
    :data-stage="stage"
  >
    <canvas ref="canvas" class="journey-flow-canvas"></canvas>
  </div>
</template>

<style scoped>
.journey-flow-background {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
  background:
    radial-gradient(ellipse at 83% 29%, #f9fcffb8, transparent 65%),
    radial-gradient(ellipse at 12% 72%, #9fc7ec94, transparent 64%),
    radial-gradient(ellipse at 45% 61%, #d4def370, transparent 52%),
    radial-gradient(ellipse at 34% 108%, #fff0d8b8, transparent 53%),
    linear-gradient(156deg, #f4f9ff 4%, #e4eff9 38%, #c7dced 74%, #f8f4eb);
}
.journey-flow-canvas {
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.journey-flow-background[data-renderer="css"] .journey-flow-canvas {
  visibility: hidden;
}
@media (prefers-reduced-motion: reduce) {
  .journey-flow-background {
    scroll-behavior: auto;
  }
}
</style>
