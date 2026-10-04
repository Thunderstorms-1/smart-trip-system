<script setup lang="ts">
import type { Post } from "./types";
import { money } from "./api";
defineProps<{ posts: Post[] }>();
</script>
<template>
  <div class="card-grid">
    <RouterLink
      v-for="(p, n) in posts"
      :key="p.id"
      :to="'/posts/' + p.id"
      class="travel-card"
      ><div class="card-art" :class="'art-' + (n % 3)">
        <span class="art-city">{{ p.destination }}</span
        ><span class="art-days">{{ p.totalDays }} DAYS</span>
        <div class="art-line"></div>
      </div>
      <div class="card-body">
        <div class="eyebrow">
          {{ p.destination }} · {{ p.totalDays }} 天 ·
          {{ money(p.totalBudget) }}
        </div>
        <h3>{{ p.title }}</h3>
        <p>{{ p.summary || "一份值得出发的旅行灵感。" }}</p>
        <footer>
          <span>{{ p.authorName }}</span
          ><span>♡ {{ p.likeCount }} · 收藏 {{ p.favoriteCount }}</span>
        </footer>
      </div></RouterLink
    >
  </div>
</template>
