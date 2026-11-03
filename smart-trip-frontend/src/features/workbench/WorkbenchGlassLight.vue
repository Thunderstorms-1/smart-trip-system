<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
const props = defineProps<{ suspended: boolean }>();
const canvas = ref<HTMLCanvasElement>();
const renderer = ref<"css" | "webgl">("css");
const media = window.matchMedia("(prefers-reduced-motion: reduce)");
const fine = window.matchMedia("(min-width: 1101px) and (pointer: fine)");
let root: HTMLElement | undefined, observer: ResizeObserver | undefined;
let gl: WebGLRenderingContext | null = null,
  program: WebGLProgram | null = null,
  buffer: WebGLBuffer | null = null;
let vertex: WebGLShader | null = null,
  fragment: WebGLShader | null = null;
let frame = 0,
  disposed = false,
  pointer: [number, number] | undefined;
let locations: Array<WebGLUniformLocation | null> = [];
const vertexSource = `attribute vec2 aPosition; varying vec2 vUv;
void main(){vUv=aPosition*.5+.5;gl_Position=vec4(aPosition,0.,1.);}`;
// No time uniform or animation loop: this renders only on a real pointer/layout change.
const fragmentSource = `precision mediump float;
varying vec2 vUv; uniform vec2 uSize; uniform vec2 uPointer;
uniform vec4 uPanel0; uniform vec4 uPanel1; uniform vec4 uPanel2;
float rim(vec2 p,vec4 rect){
  if(rect.z<2.||rect.w<2.)return 0.;
  float radius=min(23.,min(rect.z,rect.w)*.2);
  vec2 q=abs(p-rect.xy-rect.zw*.5)-(rect.zw*.5-vec2(radius));
  float d=length(max(q,0.))+min(max(q.x,q.y),0.)-radius;
  float edge=1.-smoothstep(.65,2.4,abs(d));
  float topLight=.45+.55*(1.-clamp((p.y-rect.y)/rect.w,0.,1.));
  return edge*topLight;
}
void main(){
  vec2 p=vec2(vUv.x,1.-vUv.y)*uSize;
  float edge=max(rim(p,uPanel0),max(rim(p,uPanel1),rim(p,uPanel2)));
  float reflection=exp(-length((p-uPointer)/vec2(230.,180.)));
  vec3 color=mix(vec3(.62,.85,1.),vec3(1.),.48+.4*reflection);
  // The browser composites this context as premultiplied alpha. RGB must be
  // zero outside the narrow edge; straight RGB would brighten the entire canvas.
  float alpha=edge*(.025+.095*reflection);
  gl_FragColor=vec4(color*alpha,alpha);
}`;
function capable() {
  const device = navigator as Navigator & { deviceMemory?: number };
  return (
    !media.matches &&
    fine.matches &&
    (!device.deviceMemory || device.deviceMemory >= 4) &&
    (!navigator.hardwareConcurrency || navigator.hardwareConcurrency >= 4)
  );
}
function release() {
  renderer.value = "css";
  if (frame) cancelAnimationFrame(frame);
  frame = 0;
  if (gl && !gl.isContextLost()) {
    if (buffer) gl.deleteBuffer(buffer);
    if (program) gl.deleteProgram(program);
    if (vertex) gl.deleteShader(vertex);
    if (fragment) gl.deleteShader(fragment);
  }
  program = null;
  buffer = null;
  vertex = null;
  fragment = null;
  locations = [];
}
function compile(source: string, type: number) {
  const shader = gl!.createShader(type);
  if (!shader) throw new Error("Glass shader unavailable");
  gl!.shaderSource(shader, source);
  gl!.compileShader(shader);
  if (!gl!.getShaderParameter(shader, gl!.COMPILE_STATUS)) {
    gl!.deleteShader(shader);
    throw new Error("Glass shader unsupported");
  }
  return shader;
}
function initialize() {
  if (disposed || !canvas.value || !capable()) {
    release();
    return;
  }
  if (program) {
    schedule();
    return;
  }
  try {
    gl = canvas.value.getContext("webgl", {
      alpha: true,
      premultipliedAlpha: true,
      antialias: false,
      depth: false,
      stencil: false,
      powerPreference: "low-power",
    });
    if (!gl || gl.isContextLost()) return;
    vertex = compile(vertexSource, gl.VERTEX_SHADER);
    fragment = compile(fragmentSource, gl.FRAGMENT_SHADER);
    program = gl.createProgram();
    if (!program) throw new Error("Glass program unavailable");
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS))
      throw new Error("Glass program unsupported");
    gl.useProgram(program);
    buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]),
      gl.STATIC_DRAW,
    );
    const position = gl.getAttribLocation(program, "aPosition");
    gl.enableVertexAttribArray(position);
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
    locations = ["uSize", "uPointer", "uPanel0", "uPanel1", "uPanel2"].map(
      (name) => gl!.getUniformLocation(program!, name),
    );
    renderer.value = "webgl";
    schedule();
  } catch {
    release();
  }
}
function render() {
  frame = 0;
  const surface = canvas.value;
  if (
    disposed ||
    !root ||
    !gl ||
    !program ||
    !surface ||
    props.suspended ||
    document.hidden ||
    !capable()
  )
    return;
  const bounds = root.getBoundingClientRect();
  if (bounds.width <= 0 || bounds.height <= 0) return;
  // One small low-power edge buffer; the photographs and glass remain ordinary CSS.
  const ratio = Math.min(1, 1000 / bounds.width, 650 / bounds.height);
  const width = Math.max(1, Math.round(bounds.width * ratio)),
    height = Math.max(1, Math.round(bounds.height * ratio));
  if (surface.width !== width || surface.height !== height) {
    surface.width = width;
    surface.height = height;
  }
  gl.viewport(0, 0, width, height);
  gl.useProgram(program);
  gl.uniform2f(locations[0]!, bounds.width, bounds.height);
  gl.uniform2f(
    locations[1]!,
    pointer?.[0] ?? bounds.width * 0.45,
    pointer?.[1] ?? 0,
  );
  const selectors = [
    ".wb-rail-region",
    ".wb-timeline-region",
    ".wb-right-layout",
  ];
  selectors.forEach((selector, index) => {
    const panel = root!.querySelector<HTMLElement>(selector);
    const rect = panel?.getBoundingClientRect();
    if (!rect || !panel?.getClientRects().length)
      gl!.uniform4f(locations[index + 2]!, 0, 0, 0, 0);
    else
      gl!.uniform4f(
        locations[index + 2]!,
        rect.left - bounds.left,
        rect.top - bounds.top,
        rect.width,
        rect.height,
      );
  });
  gl.clearColor(0, 0, 0, 0);
  gl.clear(gl.COLOR_BUFFER_BIT);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
}
function schedule() {
  if (!frame && !disposed && program && !props.suspended && !document.hidden)
    frame = requestAnimationFrame(render);
}
function move(event: PointerEvent) {
  if (!root) return;
  const bounds = root.getBoundingClientRect();
  pointer = [event.clientX - bounds.left, event.clientY - bounds.top];
  schedule();
}
function reset() {
  pointer = undefined;
  schedule();
}
function visible() {
  if (document.hidden && frame) {
    cancelAnimationFrame(frame);
    frame = 0;
  } else schedule();
}
function contextLost(event: Event) {
  event.preventDefault();
  release();
}
watch(
  () => props.suspended,
  async (value) => {
    if (!value) {
      await nextTick();
      schedule();
    }
  },
);
onMounted(() => {
  root = canvas.value?.parentElement ?? undefined;
  observer = new ResizeObserver(schedule);
  if (root) {
    observer.observe(root);
    root
      .querySelectorAll<HTMLElement>(
        ".wb-rail-region,.wb-timeline-region,.wb-right-layout",
      )
      .forEach((el) => observer!.observe(el));
    root.addEventListener("pointermove", move, { passive: true });
    root.addEventListener("pointerleave", reset);
  }
  canvas.value?.addEventListener("webglcontextlost", contextLost);
  media.addEventListener("change", initialize);
  fine.addEventListener("change", initialize);
  document.addEventListener("visibilitychange", visible);
  initialize();
});
onBeforeUnmount(() => {
  disposed = true;
  release();
  observer?.disconnect();
  root?.removeEventListener("pointermove", move);
  root?.removeEventListener("pointerleave", reset);
  media.removeEventListener("change", initialize);
  fine.removeEventListener("change", initialize);
  document.removeEventListener("visibilitychange", visible);
  canvas.value?.removeEventListener("webglcontextlost", contextLost);
  if (gl && !gl.isContextLost())
    gl.getExtension("WEBGL_lose_context")?.loseContext();
  gl = null;
});
</script>
<template>
  <canvas
    ref="canvas"
    class="wb-workspace-light"
    :data-renderer="renderer"
    aria-hidden="true"
  ></canvas>
</template>
