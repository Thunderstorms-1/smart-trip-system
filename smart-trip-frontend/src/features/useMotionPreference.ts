import { onMounted, onScopeDispose, ref } from "vue";
import { prefersReducedMotion } from "./motion-tokens";

/** Each mounted consumer releases its own preference listener. */
export function useMotionPreference() {
  const reducedMotion = ref(prefersReducedMotion());
  let release: (() => void) | undefined;
  onMounted(() => {
    const media = window.matchMedia("(prefers-reduced-motion: reduce)");
    const update = () => {
      reducedMotion.value = media.matches;
    };
    update();
    media.addEventListener("change", update);
    release = () => media.removeEventListener("change", update);
  });
  onScopeDispose(() => release?.());
  return { reducedMotion };
}
