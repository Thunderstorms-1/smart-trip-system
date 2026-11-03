import { nextTick, reactive } from "vue";
import {
  isNavigationFailure,
  NavigationFailureType,
  type Router,
} from "vue-router";
import { prefersReducedMotion } from "./motion-tokens";

type VisualTransition = {
  skipTransition(): void;
  finished: Promise<void>;
  ready?: Promise<void>;
};
type TransitionDocument = Document & {
  startViewTransition?: (update: () => Promise<void>) => VisualTransition;
};
const scrollWaits = new WeakMap<
  object,
  { done: Promise<void>; finish: () => void }
>();
let activeScrollRoute: object | undefined;
let releasePreviousScroll: (() => void) | undefined;
export const routeMotion = reactive({
  pending: false,
  waiting: false,
  native: false,
  reload: false,
  error: "",
  target: "",
});

/** Hooks observe real navigation. They never wait for an exit animation. */
export function installRouteMotion(router: Router) {
  let epoch = 0;
  const navigations = new WeakMap<object, number>();
  const removeHooks: Array<() => void> = [];
  let waitingTimer: ReturnType<typeof setTimeout> | undefined;
  let visual: VisualTransition | undefined;
  let finishUpdate: (() => void) | undefined;
  let visualTarget = "";
  const settleVisual = () => {
    finishUpdate?.();
    finishUpdate = undefined;
    visual?.skipTransition();
    visual = undefined;
    routeMotion.native = false;
  };
  removeHooks.push(
    router.beforeEach((to, from) => {
      if (to.fullPath === from.fullPath) return;
      epoch++;
      scrollEpoch++;
      navigations.set(to, epoch);
      releasePreviousScroll?.();
      activeScrollRoute = to;
      let finish!: () => void;
      const done = new Promise<void>((resolve) => {
        finish = resolve;
      });
      releasePreviousScroll = finish;
      scrollWaits.set(to, { done, finish });
      clearTimeout(waitingTimer);
      settleVisual();
      routeMotion.pending = true;
      routeMotion.waiting = false;
      routeMotion.target = to.fullPath;
      routeMotion.error = "";
      routeMotion.reload = false;
      waitingTimer = setTimeout(() => {
        routeMotion.waiting = true;
      }, 180);
    }),
  );
  removeHooks.push(
    router.beforeResolve(async (to, from) => {
      const doc = document as TransitionDocument;
      if (
        !from.matched.length ||
        to.path === "/" || // Home builds a scroll pin; keep its live layout for restoration.
        to.path === from.path ||
        prefersReducedMotion() ||
        !doc.startViewTransition ||
        document.visibilityState !== "visible"
      )
        return;
      const current = epoch;
      // Native snapshots avoid cloning live forms or transforming fixed glass canvases.
      let proceed!: () => void;
      const captured = new Promise<void>((resolve) => {
        proceed = resolve;
      });
      const rendered = new Promise<void>((resolve) => {
        finishUpdate = resolve;
      });
      visualTarget = to.fullPath;
      try {
        routeMotion.native = true;
        const own = doc.startViewTransition(async () => {
          proceed();
          await rendered;
        });
        visual = own;
        void own.ready?.catch(() => {});
        void own.finished
          .catch(() => {})
          .finally(() => {
            if (visual === own) {
              visual = undefined;
              routeMotion.native = false;
            }
          });
        // A skipped/unsupported capture must never strand a router guard.
        void own.finished.catch(() => {}).then(proceed);
        await captured;
        if (current !== epoch) own.skipTransition();
      } catch {
        proceed();
        settleVisual();
      }
    }),
  );
  removeHooks.push(
    router.afterEach((to, from, failure) => {
      if (navigations.get(to) !== epoch) return;
      clearTimeout(waitingTimer);
      routeMotion.pending = false;
      routeMotion.waiting = false;
      if (failure) {
        scrollWaits.get(to)?.finish();
        settleVisual();
        if (
          !isNavigationFailure(
            failure,
            NavigationFailureType.cancelled |
              NavigationFailureType.duplicated |
              NavigationFailureType.aborted,
          )
        )
          routeMotion.error = "页面暂时无法打开，请重试。";
        return;
      }
      const current = epoch;
      void nextTick().then(async () => {
        if (current !== epoch) return;
        if (visualTarget === to.fullPath) {
          // Capture the destination only after its actual scroll restoration.
          await scrollWaits.get(to)?.done;
          if (current !== epoch) return;
          // View Transition suppresses rendering while its update is pending.
          // A timer lets Router apply scroll without waiting for a blocked frame.
          setTimeout(() => {
            if (current === epoch) {
              finishUpdate?.();
              finishUpdate = undefined;
            }
          }, 0);
        }
        if (to.path === from.path || !from.matched.length) return;
        const main = document.getElementById("main-content");
        const active = document.activeElement;
        if (
          document.querySelector("dialog[open], [aria-modal=true]") ||
          (main?.contains(active) &&
            active?.matches("input, textarea, select, [contenteditable=true]"))
        )
          return;
        main?.focus({ preventScroll: true });
      });
    }),
  );
  removeHooks.push(
    router.onError((_error, to) => {
      if (to && navigations.get(to) !== epoch) return;
      epoch++;
      releasePreviousScroll?.();
      clearTimeout(waitingTimer);
      settleVisual();
      routeMotion.pending = false;
      routeMotion.waiting = false;
      routeMotion.reload = true;
      routeMotion.error =
        "页面加载失败，请检查网络后重新打开。当前页面仍可使用。";
    }),
  );
  const media = window.matchMedia("(prefers-reduced-motion: reduce)");
  const syncPreference = () => {
    if (media.matches) settleVisual();
  };
  media.addEventListener("change", syncPreference);
  // Router and these hooks have application lifetime; hot replacement releases them.
  if (import.meta.hot)
    import.meta.hot.dispose(() => {
      clearTimeout(waitingTimer);
      settleVisual();
      media.removeEventListener("change", syncPreference);
      removeHooks.forEach((remove) => remove());
    });
}

let scrollEpoch = 0;
export async function routeScroll(
  to: { path: string; hash: string },
  from: { path: string },
  saved: { left: number; top: number } | null,
) {
  const finish = scrollWaits.get(to)?.finish;
  try {
    if (activeScrollRoute && activeScrollRoute !== to) return;
    const current = ++scrollEpoch;
    if (to.path === from.path && !saved && !to.hash) return;
    await nextTick();
    if (current !== scrollEpoch) return;
    if (saved && to.path === "/") {
      // Home creates its pin after a lazy GSAP import. Restore after that layout exists.
      await new Promise<void>((resolve) => {
        const start = performance.now();
        const poll = () => {
          const scene = document.querySelector<HTMLElement>(".season-journey");
          if (
            current !== scrollEpoch ||
            !scene ||
            scene.dataset.motion !== "pending" ||
            performance.now() - start > 800
          )
            resolve();
          else setTimeout(poll, 16);
        };
        poll();
      });
      // With Home kept live, let its initial pin refresh settle before restoring history.
      await new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      );
    }
    if (current !== scrollEpoch) return;
    let hashId = "";
    try {
      hashId = decodeURIComponent(to.hash.slice(1));
    } catch {
      /* Invalid URL escapes fall back to the top. */
    }
    if (hashId && document.getElementById(hashId))
      return { el: to.hash, top: 100, behavior: "instant" as ScrollBehavior };
    return saved
      ? { ...saved, behavior: "instant" as ScrollBehavior }
      : { left: 0, top: 0, behavior: "instant" as ScrollBehavior };
  } finally {
    finish?.();
  }
}
