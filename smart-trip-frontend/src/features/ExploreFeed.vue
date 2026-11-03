<script setup lang="ts">
import type { Post } from "./types";
import { money } from "./api";
import HomeIcon from "./HomeIcon.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
withDefaults(defineProps<{ posts: Post[]; featured?: boolean }>(), {
  featured: false,
});
</script>
<template>
  <TransitionGroup
    tag="div"
    name="motion-list"
    class="explore-feed"
    :class="{ 'explore-featured': featured }"
  >
    <RouterLink
      v-for="(post, i) in posts"
      :key="post.id"
      :to="'/posts/' + post.id"
      class="explore-entry"
      :class="{ 'explore-lead': featured && i === 0 }"
    >
      <JourneyBackdrop v-if="featured && i === 0" />
      <div class="explore-destination">
        <HomeIcon name="pin" /><span>{{ post.destination }}</span
        ><small>{{ post.totalDays }} 天</small>
      </div>
      <div class="explore-copy">
        <h2>{{ post.title }}</h2>
        <p>{{ post.summary || "打开路书，看看沿途安排。" }}</p>
        <div class="explore-meta">
          <span>{{ post.authorName }}</span
          ><span>预估 {{ money(post.totalBudget) }}</span
          ><span
            >点赞 {{ post.likeCount }} · 收藏 {{ post.favoriteCount }}</span
          >
        </div>
      </div>
      <span class="explore-open">查看路书 <HomeIcon name="arrow" /></span>
    </RouterLink>
  </TransitionGroup>
</template>
