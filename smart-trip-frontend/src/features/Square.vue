<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api } from "./api";
import type { Post, PostPage } from "./types";
import { useLatestQuery } from "./useLatestQuery";
import PostCards from "./PostCards.vue";
const q = ref(""),
  posts = ref<Post[]>([]),
  cursor = ref<string>(),
  hasMore = ref(false);
const { loading: busy, error, query } = useLatestQuery();
let activeSearch = "";
function search(reset = true) {
  if (reset) {
    activeSearch = q.value.trim();
    posts.value = [];
    cursor.value = undefined;
    hasMore.value = false;
  }
  const requestCursor = reset ? undefined : cursor.value;
  void query(
    (signal) =>
      api<PostPage>(
        "/community/posts?q=" +
          encodeURIComponent(activeSearch) +
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
  <section class="page-title">
    <p class="eyebrow">FIND YOUR NEXT CHAPTER</p>
    <h1>旅行灵感广场</h1>
    <p>真实的路书，来自每一个热爱出发的人。</p>
  </section>
  <form class="search-bar" @submit.prevent="search()">
    <input
      v-model="q"
      maxlength="100"
      aria-label="搜索城市或路书"
      placeholder="想去哪里？搜索城市或路书"
    /><button class="button">搜索</button>
  </form>
  <p v-if="error" class="form-error">{{ error }}</p>
  <p v-if="busy && !posts.length" class="empty">正在加载路书…</p>
  <PostCards :posts="posts" />
  <div v-if="!busy && !posts.length" class="empty paper">
    暂无匹配的路书，换个城市试试。
  </div>
  <button
    v-if="hasMore"
    class="button secondary load-more"
    :disabled="busy"
    @click="search(false)"
  >
    {{ busy ? "加载中…" : "查看更多" }}
  </button>
</template>
