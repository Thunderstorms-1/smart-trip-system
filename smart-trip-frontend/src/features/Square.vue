<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api } from "./api";
import type { Post, PostPage } from "./types";
import { useLatestQuery } from "./useLatestQuery";
import ExploreFeed from "./ExploreFeed.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
import HomeIcon from "./HomeIcon.vue";
const q = ref(""),
  posts = ref<Post[]>([]),
  cursor = ref<string>(),
  hasMore = ref(false);
const { loading: busy, error, query } = useLatestQuery();
const activeSearch = ref("");
function search(reset = true) {
  if (reset) {
    activeSearch.value = q.value.trim();
    posts.value = [];
    cursor.value = undefined;
    hasMore.value = false;
  }
  const requestCursor = reset ? undefined : cursor.value;
  void query(
    (signal) =>
      api<PostPage>(
        "/community/posts?q=" +
          encodeURIComponent(activeSearch.value) +
          (requestCursor ? "&cursor=" + encodeURIComponent(requestCursor) : ""),
        "GET",
        undefined,
        signal,
      ),
    (result) => {
      const byId = new Map((reset ? [] : posts.value).map((p) => [p.id, p]));
      for (const post of result.items) byId.set(post.id, post);
      posts.value = [...byId.values()];
      cursor.value = result.nextCursor;
      hasMore.value = result.hasMore;
    },
  );
}
onMounted(() => search());
</script>
<template>
  <div class="v3-explore">
    <section class="explore-intro" aria-labelledby="explore-title">
      <JourneyBackdrop />
      <div class="explore-intro-copy">
        <h1 id="explore-title">下一站，<br />从心动开始。</h1>
        <p>探索旅行灵感广场，沿着真实路书找到自己的方向。</p>
      </div>
      <form class="v3-search" @submit.prevent="search()">
        <HomeIcon name="pin" /><input
          v-model="q"
          maxlength="100"
          aria-label="搜索城市或路书"
          placeholder="想去哪里？搜索城市或路书"
        /><button class="button">搜索</button>
      </form>
    </section>
    <section class="v3-content" aria-label="公开路书">
      <div class="v3-section-heading">
        <h2>{{ activeSearch ? "探索结果" : "沿着路书，发现更多" }}</h2>
        <span>旅行者分享的公开行程</span>
      </div>
      <div v-if="error" class="v3-state" role="alert">
        <p>{{ error }}</p>
        <button class="button secondary" :disabled="busy" @click="search()">
          重新加载
        </button>
      </div>
      <p v-if="busy && !posts.length" class="v3-state" role="status">
        正在加载路书…
      </p>
      <ExploreFeed :posts="posts" featured />
      <div v-if="!error && !busy && !posts.length" class="v3-state">
        <h3>还没有找到这段旅程</h3>
        <p>暂无匹配的路书，换个城市试试。</p>
      </div>
      <button
        v-if="hasMore"
        class="button secondary v3-load-more"
        :disabled="busy"
        @click="search(false)"
      >
        {{ busy ? "加载中…" : "查看更多" }}
      </button>
    </section>
  </div>
</template>
