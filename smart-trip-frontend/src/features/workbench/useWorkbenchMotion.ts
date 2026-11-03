import { onBeforeUnmount, ref } from "vue";

type Speed = "fast" | "base" | "slow";
const fallback = { fast: 160, base: 280, slow: 360 };

/** Owns short-lived effects only; cancellation always restores the authored CSS. */
export function useWorkbenchMotion() {
  const preference = window.matchMedia("(prefers-reduced-motion: reduce)");
  const reduced = ref(preference.matches);
  const running = new Map<HTMLElement, Animation>();
  let disposed = false;
  function cancel(target?: HTMLElement) {
    if (target) {
      running.get(target)?.cancel();
      running.delete(target);
    } else {
      running.forEach((animation) => animation.cancel());
      running.clear();
    }
  }
  function changed(event: MediaQueryListEvent) {
    reduced.value = event.matches;
    if (event.matches) cancel();
  }
  preference.addEventListener("change", changed);
  function play(
    target: HTMLElement | null | undefined,
    frames: Keyframe[],
    speed: Speed = "base",
  ) {
    if (!target || disposed) return Promise.resolve();
    const previous = running.get(target);
    const style = getComputedStyle(target);
    // Retarget from the currently displayed position when users click quickly.
    const first = { ...frames[0] };
    if (previous) {
      if (first.transform != null) first.transform = style.transform;
      if (first.opacity != null) first.opacity = style.opacity;
    }
    cancel(target);
    if (reduced.value || typeof target.animate !== "function")
      return Promise.resolve();
    const raw = style.getPropertyValue("--motion-" + speed).trim();
    const value = parseFloat(raw);
    const duration = Number.isFinite(value)
      ? value * (raw.endsWith("ms") ? 1 : 1000)
      : fallback[speed];
    const easing =
      style.getPropertyValue("--motion-ease").trim() ||
      "cubic-bezier(.22,1,.36,1)";
    const animation = target.animate([first, ...frames.slice(1)], {
      duration,
      easing,
      fill: "both",
    });
    running.set(target, animation);
    return animation.finished
      .catch(() => undefined)
      .then(() => {
        if (running.get(target) === animation) {
          running.delete(target);
          animation.cancel();
        }
      });
  }
  onBeforeUnmount(() => {
    disposed = true;
    preference.removeEventListener("change", changed);
    cancel();
  });
  return { reduced, play, cancel };
}
