<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "./api";
import { useLatestQuery } from "./useLatestQuery";
import type { PublicAuthor, Post, PostPage } from "./types";
import PostCards from "./PostCards.vue";
const route = useRoute(),
  author = ref<PublicAuthor>(),
  posts = ref<Post[]>([]);
const { loading: busy, error, query } = useLatestQuery();
watch(
  () => route.params.id,
  () => {
    const id = String(route.params.id);
    author.value = undefined;
    posts.value = [];
    void query(
      async (signal) =>
        Promise.all([
          api<PublicAuthor>("/user/public/" + id, "GET", undefined, signal),
          api<PostPage>(
            "/community/posts?author=" + id,
            "GET",
            undefined,
            signal,
          ),
        ]),
      ([a, p]) => {
        author.value = a;
        posts.value = p.items;
      },
    );
  },
  { immediate: true },
);
</script>
<template>
  <p v-if="busy" class="empty">加载旅行者主页…</p>
  <p v-else-if="error" class="empty">{{ error }}</p>
  <template v-else-if="author"
    ><section class="author-heading">
      <img
        v-if="author.avatar"
        class="profile-avatar"
        :src="author.avatar"
        alt="作者头像"
      /><span v-else class="profile-avatar avatar-letter">{{
        author.nickname.slice(0, 1)
      }}</span>
      <div>
        <p class="eyebrow">TRAVELER'S JOURNAL</p>
        <h1>{{ author.nickname }}</h1>
        <p>
          {{ author.homeCity || "在路上" }} ·
          {{ author.bio || "每一次出发，都有新的故事。" }}
        </p>
      </div>
    </section>
    <div class="section-heading"><h2>公开旅行故事</h2></div>
    <PostCards :posts="posts" />
    <div v-if="!posts.length" class="empty paper">
      这位旅行者还没有发布路书。
    </div></template
  >
</template>
