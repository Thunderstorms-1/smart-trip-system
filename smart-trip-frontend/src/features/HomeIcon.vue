<script setup lang="ts">
withDefaults(defineProps<{ name?: string }>(), { name: "route" });
const paths: Record<string, string> = {
  route: "M6 5a2 2 0 1 0 0 .1M18 19a2 2 0 1 0 0 .1M6 7v5h12v5",
  pin: "M20 10c0 6-8 11-8 11S4 16 4 10a8 8 0 1 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
  arrow: "M5 12h14m-5-5 5 5-5 5",
  clock: "M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0",
  user: "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-2a8 8 0 0 1 16 0v2",
  food: "M5 3v6m3-6v6M2 3v6a3 3 0 0 0 6 0m-3 3v9M17 3v18m0-18c-4 3-4 8 0 8",
};
</script>
<template>
  <svg
    class="icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.pin" />
  </svg>
</template>
