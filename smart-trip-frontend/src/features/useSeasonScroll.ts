import { onBeforeUnmount, onMounted, ref, type Ref } from "vue";
import type { gsap } from "gsap";
import type { ScrollTrigger } from "gsap/ScrollTrigger";

const transitionStarts = [0.76, 1.96, 3.16] as const;
const transitionDuration = 0.64;
const sceneStops = [0, 1.65, 2.85, 4.02] as const;
const journeyDuration = 4.42;
const fullFrame = "inset(0% 0% 0% 0%)";

/** Owns only the Home scene's motion; leaving Home also releases its pin. */
export function useSeasonScroll(
  root: Ref<HTMLElement | null>,
  stage: Ref<HTMLElement | null>,
  end: Ref<HTMLElement | null>,
) {
  const activeSeason = ref(0);
  const ready = ref(false);
  let disposed = false;
  let trigger: ScrollTrigger | undefined;
  let release: (() => void) | undefined;

  function goToSeason(index: number) {
    if (!Number.isFinite(index)) return;
    const target = Math.max(0, Math.min(3, Math.trunc(index)));
    if (trigger && root.value?.dataset.motion === "scroll") {
      window.scrollTo({
        top:
          trigger.start +
          (sceneStops[target]! / journeyDuration) *
            (trigger.end - trigger.start),
        behavior: "smooth",
      });
      return;
    }
    root.value
      ?.querySelectorAll<HTMLElement>("[data-season-scene]")
      [target]?.scrollIntoView({ behavior: "instant", block: "start" });
    activeSeason.value = target;
  }

  function skipToEnd() {
    const closing = end.value;
    if (!closing) return;
    closing.scrollIntoView({
      behavior: root.value?.dataset.motion === "scroll" ? "smooth" : "instant",
      block: "start",
    });
    // A keyboard skip must also leave the pinned controls, not only move the view.
    closing.focus({ preventScroll: true });
  }

  onMounted(async () => {
    const mountedRoot = root.value;
    const viewport = stage.value;
    if (!mountedRoot || !viewport) return;
    const container: HTMLElement = mountedRoot;
    const scenes = Array.from(
      container.querySelectorAll<HTMLElement>("[data-season-scene]"),
    );
    const originalSceneState = scenes.map((scene) => ({
      inert: scene.inert,
      ariaHidden: scene.getAttribute("aria-hidden"),
    }));
    const originalProgress =
      container.style.getPropertyValue("--season-progress");
    let scope: gsap.Context | undefined;
    let media: gsap.MatchMedia | undefined;
    let frame = 0;
    let stopStaticTracking: (() => void) | undefined;
    let abortImages: AbortController | undefined;

    function exposeAllScenes() {
      scenes.forEach((scene) => {
        scene.inert = false;
        scene.removeAttribute("aria-hidden");
      });
    }

    function trackDocumentScenes() {
      stopStaticTracking?.();
      const update = () => {
        frame = 0;
        if (disposed) return;
        const center = window.innerHeight * 0.5;
        const distances = scenes.map((scene) => {
          const bounds = scene.getBoundingClientRect();
          return Math.abs(
            bounds.top + Math.min(bounds.height / 2, center) - center,
          );
        });
        const nearest = distances.indexOf(Math.min(...distances));
        activeSeason.value = Math.max(0, nearest);
        container.style.setProperty(
          "--season-progress",
          String(Math.max(0, nearest) / 3),
        );
      };
      const schedule = () => {
        if (!frame) frame = window.requestAnimationFrame(update);
      };
      window.addEventListener("scroll", schedule, { passive: true });
      update();
      stopStaticTracking = () => {
        window.removeEventListener("scroll", schedule);
        if (frame) window.cancelAnimationFrame(frame);
        frame = 0;
      };
    }

    function showDocument(mode: "reduced" | "static") {
      container.dataset.motion = mode;
      exposeAllScenes();
      trackDocumentScenes();
    }

    release = () => {
      abortImages?.abort();
      stopStaticTracking?.();
      if (frame) window.cancelAnimationFrame(frame);
      frame = 0;
      media?.revert();
      scope?.revert();
      trigger = undefined;
      scenes.forEach((scene, index) => {
        const original = originalSceneState[index]!;
        scene.inert = original.inert;
        if (original.ariaHidden === null) scene.removeAttribute("aria-hidden");
        else scene.setAttribute("aria-hidden", original.ariaHidden);
      });
      if (originalProgress)
        container.style.setProperty("--season-progress", originalProgress);
      else container.style.removeProperty("--season-progress");
    };

    if (scenes.length !== 4 || !window.CSS?.supports("clip-path", fullFrame)) {
      showDocument("static");
      return;
    }

    try {
      // Motion code stays outside other pages' initial bundles.
      const [{ gsap: engine }, { ScrollTrigger: Scroll }] = await Promise.all([
        import("gsap"),
        import("gsap/ScrollTrigger"),
      ]);
      if (disposed) return;
      engine.registerPlugin(Scroll);
      scope = engine.context(() => {
        media = engine.matchMedia();
        media.add(
          {
            desktop: "(min-width: 768px)",
            mobile: "(max-width: 767px)",
            reduced: "(prefers-reduced-motion: reduce)",
          },
          (context) => {
            stopStaticTracking?.();
            stopStaticTracking = undefined;
            const { mobile, reduced } = context.conditions ?? {};
            if (reduced) {
              showDocument("reduced");
              ready.value = true;
              return () => stopStaticTracking?.();
            }

            container.dataset.motion = "scroll";
            const photoTravel = mobile ? 2.5 : 4;
            const photos = scenes.map((scene) =>
              scene.querySelector<HTMLElement>("[data-season-photo]"),
            );
            const glyphs = scenes.map((scene) =>
              scene.querySelector<HTMLElement>("[data-season-glyph]"),
            );
            const atmospheres = scenes.map((scene) =>
              scene.querySelector<HTMLElement>("[data-season-atmosphere]"),
            );
            const lines = scenes.map((scene) =>
              Array.from(
                scene.querySelectorAll<HTMLElement>("[data-season-line]"),
              ),
            );
            const stories = scenes.map((scene) =>
              scene.querySelector<HTMLElement>(".season-story"),
            );

            engine.set(scenes, { autoAlpha: 0, clipPath: fullFrame });
            engine.set(scenes[0]!, { autoAlpha: 1 });
            const timeline = engine.timeline({ defaults: { ease: "none" } });

            // A damp morning opens by unfolding the lines in opposite directions.
            lines[0]!.forEach((line, lineIndex) => {
              timeline.fromTo(
                line,
                { x: lineIndex ? 22 : -34, y: 7, autoAlpha: 0.82 },
                {
                  x: 0,
                  y: 0,
                  autoAlpha: 1,
                  duration: 0.46,
                  ease: "power2.out",
                },
                lineIndex * 0.05,
              );
            });

            const reveals = [
              "inset(0% 0% 0% 100%)",
              "polygon(0% 0%, 0% 0%, 0% 100%, 0% 100%)",
              "inset(100% 0% 0% 0%)",
            ];
            scenes.forEach((scene, index) => {
              const start = index ? transitionStarts[index - 1]! : 0;
              const finish =
                index < 3
                  ? transitionStarts[index]! + transitionDuration
                  : journeyDuration;
              const photo = photos[index];
              const glyph = glyphs[index];
              const atmosphere = atmospheres[index];
              if (photo) {
                timeline.fromTo(
                  photo,
                  {
                    scale: index === 1 ? 1.12 : 1.1,
                    xPercent: index % 2 ? -photoTravel : photoTravel,
                    yPercent: -1,
                  },
                  {
                    scale: mobile ? 1.04 : 1.055,
                    xPercent: index % 2 ? photoTravel / 2 : -photoTravel / 2,
                    yPercent: 1,
                    duration: finish - start,
                  },
                  start,
                );
              }
              if (glyph) {
                timeline.fromTo(
                  glyph,
                  { x: index % 2 ? -16 : 18, y: index === 3 ? 10 : -6 },
                  {
                    x: index % 2 ? 16 : -18,
                    y: index === 3 ? -10 : 8,
                    duration: finish - start,
                  },
                  start,
                );
              }
              if (atmosphere) {
                timeline.fromTo(
                  atmosphere,
                  {
                    xPercent: index === 1 ? -3 : 2,
                    yPercent: index === 3 ? -4 : 1,
                    opacity: 0.4,
                  },
                  {
                    xPercent: index === 1 ? 3 : -2,
                    yPercent: index === 3 ? 4 : -1,
                    opacity: index === 3 ? 0.72 : 0.58,
                    duration: finish - start,
                  },
                  start,
                );
              }
              if (!index) return;

              // Frames overlap while each reveal has a different spatial direction.
              const isAutumn = index === 2;
              timeline.fromTo(
                scene,
                { autoAlpha: 0, clipPath: reveals[index - 1] },
                {
                  autoAlpha: 1,
                  ...(isAutumn ? {} : { clipPath: fullFrame }),
                  duration: transitionDuration,
                  ease: "power1.inOut",
                },
                start,
              );
              if (isAutumn) {
                // A slanted leading edge belongs to the transition, not the photograph.
                timeline.to(
                  scene,
                  {
                    clipPath: "polygon(0% 0%, 70% 0%, 35% 100%, 0% 100%)",
                    duration: transitionDuration * 0.45,
                    ease: "power1.in",
                  },
                  start,
                );
                timeline.to(
                  scene,
                  {
                    clipPath: "polygon(0% 0%, 100% 0%, 100% 100%, 0% 100%)",
                    duration: transitionDuration * 0.55,
                    ease: "power1.out",
                  },
                  start + transitionDuration * 0.45,
                );
              }
              timeline.to(
                scenes[index - 1]!,
                { autoAlpha: 0, duration: 0.28 },
                start + transitionDuration * 0.65,
              );
              timeline.to(
                lines[index - 1]!,
                { autoAlpha: 0, y: -10, duration: 0.2 },
                start + 0.06,
              );
              const previousStory = stories[index - 1];
              if (previousStory)
                timeline.to(
                  previousStory,
                  { autoAlpha: 0, duration: 0.2 },
                  start + 0.04,
                );

              lines[index]!.forEach((line, lineIndex) => {
                const entrance =
                  index === 1
                    ? { y: 42 + lineIndex * 8, x: 0, autoAlpha: 0 }
                    : index === 2
                      ? { x: lineIndex ? 30 : -30, y: 0, autoAlpha: 0 }
                      : { x: 0, y: 9, autoAlpha: 0 };
                timeline.fromTo(
                  line,
                  entrance,
                  {
                    x: 0,
                    y: 0,
                    autoAlpha: 1,
                    duration: index === 3 ? 0.48 : 0.42,
                    ease: "power2.out",
                  },
                  start + 0.28 + lineIndex * 0.06,
                );
              });
              const story = stories[index];
              if (story)
                timeline.fromTo(
                  story,
                  { autoAlpha: 0, y: index === 3 ? 0 : 8 },
                  { autoAlpha: 1, y: 0, duration: 0.4 },
                  start + 0.4,
                );
            });
            timeline.to({}, { duration: 0.01 }, journeyDuration - 0.01);

            let lastActive = -1;
            const syncScene = () => {
              const time = timeline.time();
              const index = transitionStarts.reduce<number>(
                (current, start, next) =>
                  time >= start + transitionDuration / 2 ? next + 1 : current,
                0,
              );
              container.style.setProperty(
                "--season-progress",
                String(timeline.progress()),
              );
              if (index === lastActive) return;
              lastActive = index;
              activeSeason.value = index;
              scenes.forEach((scene, sceneIndex) => {
                scene.inert = sceneIndex !== index;
                scene.setAttribute("aria-hidden", String(sceneIndex !== index));
              });
            };
            timeline.eventCallback("onUpdate", syncScene);
            trigger = Scroll.create({
              trigger: viewport,
              pin: viewport,
              pinSpacing: true,
              start: "top top",
              end: () =>
                `+=${Math.round(viewport.clientHeight * (mobile ? 3.5 : 4.25))}`,
              animation: timeline,
              scrub: mobile ? 0.3 : 0.5,
              anticipatePin: 1,
              invalidateOnRefresh: true,
            });
            syncScene();
            ready.value = true;

            const ownTrigger = trigger;
            let live = true;
            let refreshFrame = 0;
            const refresh = () => {
              if (disposed || !live || refreshFrame) return;
              refreshFrame = window.requestAnimationFrame(() => {
                refreshFrame = 0;
                if (!disposed && ownTrigger === trigger) ownTrigger.refresh();
              });
            };
            abortImages = new AbortController();
            photos.forEach((photo) =>
              photo?.addEventListener("load", refresh, {
                once: true,
                signal: abortImages!.signal,
              }),
            );
            void document.fonts?.ready.then(refresh);
            refresh();

            return () => {
              live = false;
              abortImages?.abort();
              if (refreshFrame) window.cancelAnimationFrame(refreshFrame);
              if (trigger === ownTrigger) trigger = undefined;
              exposeAllScenes();
            };
          },
        );
      }, container);
    } catch {
      media?.revert();
      scope?.revert();
      trigger = undefined;
      if (!disposed) showDocument("static");
    }
  });

  onBeforeUnmount(() => {
    disposed = true;
    ready.value = false;
    release?.();
  });

  return { activeSeason, ready, goToSeason, skipToEnd };
}
