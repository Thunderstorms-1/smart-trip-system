<script setup lang="ts">
import { nextTick, onBeforeUnmount, watch } from "vue";
import { useRoute } from "vue-router";
import { useMotionPreference } from "./useMotionPreference";
import { motion } from "./motion-tokens";
import { routeMotion } from "./route-motion";
const route = useRoute();
const { reducedMotion } = useMotionPreference();
let animation: Animation | undefined;
let epoch = 0;
function cancel() {
  animation?.cancel();
  animation = undefined;
}
watch(
  () => route.path,
  async () => {
    const current = ++epoch;
    cancel();
    await nextTick();
    if (current !== epoch || reducedMotion.value || routeMotion.native) return;
    const main = document.getElementById("main-content");
    if (!main?.animate) return;
    // Animate the existing main, so fragment-root pages need no layout wrapper.
    // Opacity preserves the containing block of fixed backgrounds and map controls.
    const own = main.animate([{ opacity: 0.78 }, { opacity: 1 }], {
      duration: motion.base,
      easing: motion.ease,
    });
    animation = own;
    void own.finished
      .catch(() => {})
      .then(() => {
        if (animation === own) cancel();
      });
  },
  { flush: "post" },
);
watch(reducedMotion, (value) => {
  if (value) cancel();
});
onBeforeUnmount(() => {
  epoch++;
  cancel();
});
</script>
<template>
  <RouterView v-slot="{ Component, route: displayedRoute }">
    <component :is="Component" :key="displayedRoute.path" />
  </RouterView>
</template>
