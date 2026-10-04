<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api } from "./api";
import type { Post } from "./types";
import { useAction } from "./ui";
import PostCards from "./PostCards.vue";
const q = ref(""),
  posts = ref<Post[]>([]),
  page = ref(1);
const { busy, error, run } = useAction();
function search(reset = true) {
  if (reset) page.value = 1;
  run(async () => {
    const rows = await api<Post[]>(
      "/community/posts?q=" +
        encodeURIComponent(q.value) +
        "&page=" +
        page.value,
    );
    posts.value = reset ? rows : [...posts.value, ...rows];
  });
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
    /><button class="button" :disabled="busy">搜索</button>
  </form>
  <p v-if="error" class="form-error">{{ error }}</p>
  <p v-if="busy && !posts.length" class="empty">正在加载路书…</p>
  <PostCards :posts="posts" />
  <div v-if="!busy && !posts.length" class="empty paper">
    暂无匹配的路书，换个城市试试。
  </div>
  <button
    v-if="posts.length >= page * 20"
    class="button secondary load-more"
    :disabled="busy"
    @click="
      page++;
      search(false);
    "
  >
    {{ busy ? "加载中…" : "查看更多" }}
  </button>
</template>
