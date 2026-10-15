<script setup lang="ts">
withDefaults(defineProps<{ name?: string }>(), { name: "route" });

const paths: Record<string, string> = {
  route: "M6 5a2 2 0 1 0 0 .1M18 19a2 2 0 1 0 0 .1M6 7v5h12v5",
  arrow: "M5 12h14m-5-5 5 5-5 5",
  plus: "M12 4v16M4 12h16",
  pin: "M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
  search: "M16.5 10a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0ZM15 15l6 6",
  heart:
    "M20.8 4.6c-2-2.2-5.6-2.1-8.8 1.3C8.8 2.5 5.2 2.4 3.2 4.6c-2.3 2.5-1.4 6 1 8.3L12 21l7.8-8.1c2.4-2.3 3.3-5.8 1-8.3Z",
  bookmark: "M6 3h12v18l-6-4-6 4Z",
  calendar:
    "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2ZM7 2v6m10-6v6M3 11h18m-13 4h3m3 0h3",
  clock: "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM12 7v5l3 2",
  grid: "M3 3h6v6H3ZM15 3h6v6h-6ZM3 15h6v6H3ZM15 15h6v6h-6Z",
  list: "M8 5h13M8 12h13M8 19h13M3 5h1m-1 7h1m-1 7h1",
  close: "m6 6 12 12M6 18 18 6",
  users:
    "M12 7a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM3 21v-3a6 6 0 0 1 12 0v3M16 4a3 3 0 0 1 0 6m2 4a5 5 0 0 1 3 4v3",
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
    focusable="false"
  >
    <path :d="paths[name] || paths.route" />
  </svg>
</template>
