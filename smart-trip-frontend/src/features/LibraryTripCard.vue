<script setup lang="ts">
import type { Trip } from "./types";
import { money } from "./api";
import LibraryIcon from "./LibraryIcon.vue";

withDefaults(defineProps<{ trip: Trip; featured?: boolean }>(), {
  featured: false,
});
</script>

<template>
  <RouterLink
    :to="'/trip/edit/' + trip.id"
    class="trip-card"
    :class="featured ? 'featured library-recent' : 'small-trip library-row'"
    :aria-label="'打开路书：' + trip.title"
  >
    <div
      class="card-media"
      :data-place="trip.destination"
      aria-hidden="true"
    ></div>
    <div class="trip-copy">
      <span class="card-place"
        ><LibraryIcon name="pin" />{{ trip.destination }}</span
      >
      <h3>{{ trip.title }}</h3>
      <p class="trip-summary">
        {{ trip.summary || "打开路书，继续完善沿途安排。" }}
      </p>
      <div class="trip-facts">
        <span
          ><LibraryIcon name="calendar" />{{
            trip.startDate || "日期待定"
          }}</span
        >
        <span
          ><LibraryIcon name="clock" />{{
            trip.totalDays != null ? trip.totalDays + " 天" : "天数待定"
          }}</span
        >
        <span>{{ trip.isPublic ? "已发布" : "私密路书" }}</span>
        <span>{{
          trip.totalBudget != null
            ? "预估 " + money(trip.totalBudget)
            : "预算待定"
        }}</span>
      </div>
    </div>
    <span v-if="featured" class="card-continue"
      ><LibraryIcon name="arrow"
    /></span>
  </RouterLink>
</template>
