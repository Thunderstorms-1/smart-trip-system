<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { Post } from "./types";
import { money } from "./api";
import LibraryIcon from "./LibraryIcon.vue";

const props = defineProps<{ post: Post }>();
const avatarFailed = ref(false);
const authorInitial = computed(
  () => props.post.authorName?.trim().slice(0, 1) || "旅",
);
watch(
  () => [props.post.id, props.post.authorAvatar],
  () => {
    avatarFailed.value = false;
  },
);
</script>

<template>
  <RouterLink
    :to="'/posts/' + post.id"
    class="story-card explore-entry"
    :aria-label="'查看路书：' + post.title"
  >
    <div
      class="card-media"
      :data-place="post.destination"
      aria-hidden="true"
    ></div>
    <div class="saved-copy">
      <span class="card-place"
        ><LibraryIcon name="pin" />{{ post.destination }} ·
        {{ post.totalDays != null ? post.totalDays + " 天" : "天数待定" }}</span
      >
      <h2>{{ post.title }}</h2>
      <p v-if="post.summary" class="saved-summary">{{ post.summary }}</p>
      <p class="saved-budget">
        {{
          post.totalBudget != null
            ? "预估 " + money(post.totalBudget)
            : "预算待定"
        }}
      </p>
      <div class="saved-facts">
        <span class="story-author">
          <img
            v-if="post.authorAvatar && !avatarFailed"
            class="author-avatar"
            :src="post.authorAvatar"
            alt=""
            loading="lazy"
            @error="avatarFailed = true"
          />
          <span v-else class="author-letter" aria-hidden="true">{{
            authorInitial
          }}</span>
          {{ post.authorName }}
        </span>
        <span class="story-likes"
          ><LibraryIcon name="heart" />点赞 {{ post.likeCount }}</span
        >
        <span class="story-favorites"
          ><LibraryIcon name="bookmark" />收藏 {{ post.favoriteCount }}</span
        >
      </div>
    </div>
  </RouterLink>
</template>
