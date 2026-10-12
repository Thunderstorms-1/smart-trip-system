<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "./api";
import { useLatestQuery } from "./useLatestQuery";
import type { PublicAuthor, Post, PostPage } from "./types";
import ExploreFeed from "./ExploreFeed.vue";
import HomeIcon from "./HomeIcon.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
const route = useRoute(),
  author = ref<PublicAuthor>(),
  posts = ref<Post[]>([]),
  cursor = ref<string>(),
  hasMore = ref(false);
const { loading: busy, error, query } = useLatestQuery();
const {
  loading: moreBusy,
  error: moreError,
  query: queryMore,
  cancel: cancelMore,
} = useLatestQuery();
function load() {
  const id = String(route.params.id);
  cancelMore();
  moreError.value = "";
  author.value = undefined;
  posts.value = [];
  cursor.value = undefined;
  hasMore.value = false;
  void query(
    (signal) =>
      Promise.all([
        api<PublicAuthor>(
          "/user/public/" + encodeURIComponent(id),
          "GET",
          undefined,
          signal,
        ),
        api<PostPage>(
          "/community/posts?author=" + encodeURIComponent(id),
          "GET",
          undefined,
          signal,
        ),
      ]),
    ([a, p]) => {
      author.value = a;
      posts.value = p.items;
      cursor.value = p.nextCursor;
      hasMore.value = p.hasMore;
    },
  );
}
function loadMore() {
  if (busy.value || moreBusy.value || !hasMore.value || !cursor.value) return;
  const id = String(route.params.id),
    requestCursor = cursor.value;
  void queryMore(
    (signal) =>
      api<PostPage>(
        "/community/posts?author=" +
          encodeURIComponent(id) +
          "&cursor=" +
          encodeURIComponent(requestCursor),
        "GET",
        undefined,
        signal,
      ),
    (p) => {
      const byId = new Map(posts.value.map((post) => [post.id, post]));
      for (const post of p.items) byId.set(post.id, post);
      posts.value = [...byId.values()];
      cursor.value = p.nextCursor;
      hasMore.value = p.hasMore;
    },
  );
}
watch(() => route.params.id, load, { immediate: true });
</script>
<template>
  <div class="v3-author v3-content">
    <p v-if="busy" class="v3-state" role="status">加载旅行者主页…</p>
    <div v-else-if="error" class="v3-state" role="alert">
      <h2>暂时无法打开旅行者主页</h2>
      <p>{{ error }}</p>
      <button class="button secondary" @click="load">重新加载</button>
      <RouterLink to="/square" class="button secondary"
        >返回旅行广场</RouterLink
      >
    </div>
    <template v-else-if="author">
      <section class="author-scene">
        <JourneyBackdrop />
        <div class="author-scene-copy">
          <h1>{{ author.nickname }} 的旅行世界</h1>
          <p>沿着公开路书，认识路上的彼此。</p>
        </div>
      </section>
      <section class="author-identity">
        <img
          v-if="author.avatar"
          class="author-avatar"
          :src="author.avatar"
          alt="作者头像"
        /><span v-else class="author-avatar author-letter">{{
          author.nickname.slice(0, 1)
        }}</span>
        <div>
          <h2>{{ author.nickname }}</h2>
          <p v-if="author.homeCity" class="author-city">
            <HomeIcon name="pin" />{{ author.homeCity }}
          </p>
          <p class="author-bio">
            {{ author.bio || "这位旅行者还没有填写个人简介。" }}
          </p>
        </div>
      </section>
      <section aria-labelledby="author-posts-title">
        <div class="v3-section-heading">
          <h2 id="author-posts-title">公开旅行故事</h2>
          <RouterLink to="/square" class="text-button"
            >探索广场 <HomeIcon name="arrow"
          /></RouterLink>
        </div>
        <ExploreFeed :posts="posts" featured />
        <p v-if="moreError" class="form-error" role="alert">{{ moreError }}</p>
        <button
          v-if="hasMore"
          class="button secondary v3-load-more"
          :disabled="moreBusy"
          @click="loadMore"
        >
          {{
            moreBusy ? "加载中…" : moreError ? "重试加载更多" : "查看更多作品"
          }}
        </button>
        <div v-if="!posts.length" class="v3-state">
          <h3>故事，留给下一次出发</h3>
          <p>这位旅行者还没有发布路书。</p>
        </div>
      </section>
    </template>
  </div>
</template>
