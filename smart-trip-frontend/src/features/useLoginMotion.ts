import { onBeforeUnmount, onMounted, type Ref } from "vue";
import type { gsap } from "gsap";

/** Login-only choreography. Authentication never waits for the canvas. */
export function useLoginMotion(root: Ref<HTMLElement | null>) {
  let disposed = false;
  let release: (() => void) | undefined;

  onMounted(() => {
    const container = root.value;
    if (!container) return;

    const panel = container.querySelector<HTMLElement>(".auth-panel");
    const canvas = container.querySelector<HTMLElement>("[data-login-canvas]");
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
    const finePointer = window.matchMedia("(hover: hover) and (pointer: fine)");
    const mountedAt = performance.now();
    let context: gsap.Context | undefined;
    let timeline: gsap.core.Timeline | undefined;
    let stopPointers: (() => void) | undefined;
    let inView = true;
    let formUsed = !!panel?.contains(document.activeElement);

    function finishForInteraction() {
      formUsed = true;
      // The form remains usable throughout; interacting also settles its position.
      timeline?.progress(1).pause();
    }

    function syncPlayback() {
      if (!timeline || timeline.progress() >= 1 || formUsed) return;
      if (document.hidden || !inView) timeline.pause();
      else timeline.play();
    }

    function bindPointerSurfaces() {
      stopPointers?.();
      stopPointers = undefined;
      if (reduced.matches || !finePointer.matches || disposed) return;

      const cleanups: Array<() => void> = [];

      function bindSurface(
        surface: HTMLElement | null,
        properties: readonly [string, string],
        defaults: readonly [string, string],
        project: (x: number, y: number) => readonly [string, string],
      ) {
        if (!surface) return;
        const element = surface;
        const original = properties.map((property) => ({
          value: element.style.getPropertyValue(property),
          priority: element.style.getPropertyPriority(property),
        }));
        let bounds: DOMRect | undefined;
        let frame = 0;
        let point: readonly [number, number] | undefined;
        const set = (values: readonly [string, string]) => {
          properties.forEach((property, index) =>
            element.style.setProperty(property, values[index]!),
          );
        };
        const cancel = () => {
          if (frame) window.cancelAnimationFrame(frame);
          frame = 0;
          point = undefined;
        };
        const invalidate = () => {
          bounds = undefined;
        };
        const enter = () => {
          bounds = element.getBoundingClientRect();
        };
        const leave = () => {
          cancel();
          set(defaults);
        };
        const move = (event: PointerEvent) => {
          if (event.pointerType === "touch") return;
          point = [event.clientX, event.clientY];
          if (frame) return;
          frame = window.requestAnimationFrame(() => {
            frame = 0;
            if (!point || disposed) return;
            bounds ??= element.getBoundingClientRect();
            if (!bounds.width || !bounds.height) return;
            const x = Math.max(
              0,
              Math.min(1, (point[0] - bounds.left) / bounds.width),
            );
            const y = Math.max(
              0,
              Math.min(1, (point[1] - bounds.top) / bounds.height),
            );
            set(project(x, y));
          });
        };

        element.addEventListener("pointerenter", enter, { passive: true });
        element.addEventListener("pointermove", move, { passive: true });
        element.addEventListener("pointerleave", leave, { passive: true });
        window.addEventListener("resize", invalidate, { passive: true });
        window.addEventListener("scroll", invalidate, { passive: true });
        const resize =
          typeof ResizeObserver === "undefined"
            ? undefined
            : new ResizeObserver(invalidate);
        resize?.observe(element);
        cleanups.push(() => {
          cancel();
          resize?.disconnect();
          element.removeEventListener("pointerenter", enter);
          element.removeEventListener("pointermove", move);
          element.removeEventListener("pointerleave", leave);
          window.removeEventListener("resize", invalidate);
          window.removeEventListener("scroll", invalidate);
          properties.forEach((property, index) => {
            const previous = original[index]!;
            if (previous.value)
              element.style.setProperty(
                property,
                previous.value,
                previous.priority,
              );
            else element.style.removeProperty(property);
          });
        });
      }

      bindSurface(panel, ["--glass-x", "--glass-y"], ["50%", "25%"], (x, y) => [
        `${(x * 100).toFixed(2)}%`,
        `${(y * 100).toFixed(2)}%`,
      ]);
      bindSurface(
        canvas,
        ["--canvas-x", "--canvas-y"],
        ["0px", "0px"],
        (x, y) => [
          `${((x - 0.5) * 10).toFixed(2)}px`,
          `${((y - 0.5) * 10).toFixed(2)}px`,
        ],
      );
      stopPointers = () => cleanups.forEach((cleanup) => cleanup());
    }

    function syncPreferences() {
      if (reduced.matches) {
        // Revert every GSAP-owned inline style, rather than freezing mid-reveal.
        context?.revert();
        context = undefined;
        timeline = undefined;
      }
      bindPointerSurfaces();
    }

    panel?.addEventListener("focusin", finishForInteraction);
    panel?.addEventListener("pointerdown", finishForInteraction, {
      passive: true,
    });
    reduced.addEventListener("change", syncPreferences);
    finePointer.addEventListener("change", syncPreferences);
    document.addEventListener("visibilitychange", syncPlayback);
    const observer =
      typeof IntersectionObserver === "undefined"
        ? undefined
        : new IntersectionObserver(([entry]) => {
            inView = entry?.isIntersecting ?? true;
            syncPlayback();
          });
    observer?.observe(container);
    bindPointerSurfaces();

    release = () => {
      stopPointers?.();
      observer?.disconnect();
      context?.revert();
      panel?.removeEventListener("focusin", finishForInteraction);
      panel?.removeEventListener("pointerdown", finishForInteraction);
      reduced.removeEventListener("change", syncPreferences);
      finePointer.removeEventListener("change", syncPreferences);
      document.removeEventListener("visibilitychange", syncPlayback);
    };

    if (reduced.matches || formUsed) return;

    void import("gsap")
      .then(({ gsap: engine }) => {
        // Do not interrupt somebody already reading or using a slow-loading page.
        if (
          disposed ||
          reduced.matches ||
          formUsed ||
          performance.now() - mountedAt > 900
        )
          return;
        context = engine.context(() => {}, container);
        context.add(() => {
          const terrain = container.querySelectorAll("[data-login-terrain]");
          const route = container.querySelector<SVGPathElement>(
            "path[data-login-route]",
          );
          const nodes = Array.from(
            container.querySelectorAll("[data-login-node]"),
          );
          const destinations = Array.from(
            container.querySelectorAll("[data-login-destination]"),
          );
          const titles = Array.from(
            container.querySelectorAll("[data-login-title]"),
          );
          const captions = container.querySelectorAll("[data-login-caption]");
          const extras = container.querySelectorAll(
            "[data-login-extra], .entry-footer",
          );
          const sequence = engine.timeline({
            defaults: { ease: "power3.out" },
          });
          timeline = sequence;

          if (terrain.length)
            sequence.fromTo(
              terrain,
              { opacity: 0.18, y: 12 },
              { opacity: 1, y: 0, duration: 0.6, stagger: 0.08 },
              0,
            );

          const routeStart = 0.28;
          const routeDuration = 2.42;
          if (route) {
            // pathLength normalizes SVG dash units; an unnormalized path still works.
            const normalized = Number(route.getAttribute("pathLength"));
            const length = normalized > 0 ? normalized : route.getTotalLength();
            if (length > 0)
              sequence.fromTo(
                route,
                {
                  strokeDasharray: `${length} ${length}`,
                  strokeDashoffset: length,
                },
                { strokeDashoffset: 0, duration: routeDuration, ease: "none" },
                routeStart,
              );
          }

          [0, 0.35925, 1].forEach((progress, index) => {
            const arrival = routeStart + routeDuration * progress;
            const node = nodes[index];
            if (node)
              sequence.fromTo(
                node,
                { opacity: 0, scale: 0.7, transformOrigin: "50% 50%" },
                { opacity: 1, scale: 1, duration: 0.34 },
                arrival,
              );
            const response = node?.querySelector(".node-response");
            if (response)
              sequence.fromTo(
                response,
                { opacity: 0.4, scale: 0.8, transformOrigin: "50% 50%" },
                { opacity: 0, scale: 1.25, duration: 0.45, ease: "power2.out" },
                arrival + 0.12,
              );
            const destination = destinations[index];
            if (!destination) return;
            const entrances = [
              { x: -18, y: 0, rotationY: -8, clipPath: "inset(0% 95% 0% 0%)" },
              { x: 0, y: 16, rotationX: 7, clipPath: "inset(100% 0% 0% 0%)" },
              { x: 18, y: -7, rotationY: 6, clipPath: "inset(0% 0% 100% 0%)" },
            ];
            sequence.fromTo(
              destination,
              { ...entrances[index]!, opacity: 0 },
              {
                x: 0,
                y: 0,
                rotationX: 0,
                rotationY: 0,
                clipPath: "inset(0% 0% 0% 0%)",
                opacity: 1,
                duration: 0.54,
              },
              arrival + 0.08,
            );
          });

          titles.forEach((title, index) =>
            sequence.fromTo(
              title,
              {
                x: index % 2 ? 26 : -32,
                yPercent: 108,
                rotation: index % 2 ? 1 : -1,
              },
              { x: 0, yPercent: 0, rotation: 0, duration: 0.72 },
              1.76 + index * 0.18,
            ),
          );
          if (panel)
            sequence.fromTo(
              panel,
              { x: 14, y: 4, opacity: 0.94 },
              { x: 0, y: 0, opacity: 1, duration: 0.52 },
              2.42,
            );
          if (captions.length)
            sequence.fromTo(
              captions,
              { opacity: 0.3, clipPath: "inset(0% 100% 0% 0%)" },
              { opacity: 1, clipPath: "inset(0% 0% 0% 0%)", duration: 0.64 },
              2.7,
            );
          if (extras.length)
            sequence.fromTo(
              extras,
              { opacity: 0.45 },
              { opacity: 1, duration: 0.45, stagger: 0.06 },
              3.08,
            );
          syncPlayback();
        });
      })
      .catch(() => {
        // The authored SVG and form are fully visible without the motion chunk.
        context?.revert();
        context = undefined;
        timeline = undefined;
      });
  });

  onBeforeUnmount(() => {
    disposed = true;
    release?.();
  });
}
