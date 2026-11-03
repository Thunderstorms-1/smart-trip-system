import { nextTick, onBeforeUnmount, onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import { prefersReducedMotion } from "./motion-tokens";

/** One light underline per existing navigation; no changes to its link structure. */
export function useNavigationIndicator() {
  const route = useRoute();
  let release: (() => void) | undefined;
  let previous: { left: number; width: number } | undefined;
  let epoch = 0;
  function bind() {
    release?.();
    const cleanups: Array<() => void> = [];
    document
      .querySelectorAll<HTMLElement>(
        'nav[aria-label="主导航"], .home-header nav, .site-header nav',
      )
      .forEach((nav) => {
        const active = nav.querySelector<HTMLElement>(
          "a[aria-current], a.router-link-exact-active, a.wb-current-nav",
        );
        if (!active) return;
        nav.classList.add("motion-nav");
        const marker = document.createElement("span");
        marker.className = "motion-nav-indicator";
        marker.setAttribute("aria-hidden", "true");
        nav.append(marker);
        let frame = 0;
        const update = () => {
          const box = nav.getBoundingClientRect();
          const target = active.getBoundingClientRect();
          marker.style.width = target.width + "px";
          marker.style.transform =
            "translateX(" + (target.left - box.left) + "px)";
          previous = { left: target.left, width: target.width };
        };
        if (previous && !prefersReducedMotion()) {
          marker.style.transition = "none";
          marker.style.width = previous.width + "px";
          marker.style.transform =
            "translateX(" +
            (previous.left - nav.getBoundingClientRect().left) +
            "px)";
          frame = requestAnimationFrame(() => {
            marker.style.removeProperty("transition");
            update();
          });
        } else update();
        const resize = new ResizeObserver(update);
        resize.observe(nav);
        cleanups.push(() => {
          cancelAnimationFrame(frame);
          resize.disconnect();
          marker.remove();
          nav.classList.remove("motion-nav");
        });
      });
    release = () => cleanups.forEach((fn) => fn());
  }
  onMounted(bind);
  watch(
    () => route.path,
    async () => {
      const current = ++epoch;
      await nextTick();
      if (current === epoch) bind();
    },
    { flush: "post" },
  );
  onBeforeUnmount(() => {
    epoch++;
    release?.();
  });
}
