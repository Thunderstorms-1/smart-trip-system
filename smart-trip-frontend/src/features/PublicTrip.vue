<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "./api";
import { useAction } from "./ui";
import { useSession } from "./session";
import type { Trip, Post, Comment } from "./types";
import TripRead from "./TripRead.vue";
import { useLatestQuery } from "./useLatestQuery";
const queryState = useLatestQuery();
const route = useRoute(),
  router = useRouter(),
  s = useSession(),
  post = ref<Post>(),
  trip = ref<Trip>(),
  comments = ref<Comment[]>([]),
  content = ref("");
const { busy, error, run } = useAction();
const shared = () => Boolean(route.params.token);
async function load() {
  const token = String(route.params.token || ""),
    id = String(route.params.id || "");
  await queryState.query(
    async (signal) => {
      if (token)
        return {
          trip: await api<Trip>(
            "/community/share/" + token,
            "GET",
            undefined,
            signal,
          ),
          post: undefined,
          comments: [] as Comment[],
        };
      const [p, c] = await Promise.all([
        api<Post>("/community/posts/" + id, "GET", undefined, signal),
        api<Comment[]>(
          "/community/posts/" + id + "/comments",
          "GET",
          undefined,
          signal,
        ),
      ]);
      return { trip: p.trip, post: p, comments: c };
    },
    (value) => {
      trip.value = value.trip;
      post.value = value.post;
      comments.value = value.comments;
    },
  );
}
watch(
  () => route.fullPath,
  () => {
    trip.value = undefined;
    post.value = undefined;
    comments.value = [];
    content.value = "";
    void load();
  },
  { immediate: true },
);
function loginRequired() {
  if (s.token) return false;
  router.push("/login?redirect=" + encodeURIComponent(route.fullPath));
  return true;
}
function interact(type: string) {
  if (loginRequired() || !post.value) return;
  run(async () => {
    const id = post.value!.id;
    const result = await api<Post>(
      "/community/posts/" + post.value!.id + "/interaction",
      "PUT",
      {
        type,
        enabled: !(type === "LIKE" ? post.value!.liked : post.value!.favorited),
      },
    );
    if (post.value?.id === id) post.value = result;
  });
}
function clone() {
  if (loginRequired() || !post.value) return;
  run(async () => {
    const currentPath = route.fullPath;
    const t = await api<Trip>(
      "/community/posts/" + post.value!.id + "/clone",
      "POST",
      {},
    );
    if (route.fullPath === currentPath) router.push("/trip/edit/" + t.id);
  }, "已保存为你的私密路书");
}
function comment() {
  if (loginRequired() || !post.value) return;
  run(async () => {
    const currentPath = route.fullPath;
    await api("/community/posts/" + post.value!.id + "/comments", "POST", {
      content: content.value,
    });
    if (route.fullPath !== currentPath) return;
    content.value = "";
    await load();
  });
}
function remove(id: number) {
  run(async () => {
    const currentPath = route.fullPath;
    await api("/community/comments/" + id, "DELETE");
    if (route.fullPath === currentPath) await load();
  });
}
</script>
<template>
  <p v-if="(busy || queryState.loading.value) && !trip" class="empty">
    正在打开旅行故事…
  </p>
  <div
    v-else-if="(error || queryState.error.value) && !trip"
    class="empty paper"
  >
    <h2>暂时无法打开</h2>
    <p>{{ error || queryState.error.value }}</p>
    <RouterLink to="/square" class="button secondary">返回旅行广场</RouterLink>
  </div>
  <template v-if="trip"
    ><header class="page-title">
      <p class="eyebrow">
        {{ shared() ? "A SHARED TRAVEL JOURNAL" : "TRAVEL JOURNAL" }}
      </p>
      <h1>{{ trip.title }}</h1>
      <RouterLink
        v-if="post"
        :to="'/authors/' + post.authorId"
        class="text-link"
        >{{ post.authorName }} 的旅行故事 ↗</RouterLink
      >
      <p v-else>这是一份独立的分享快照，作者可随时撤销链接。</p>
    </header>
    <div v-if="post" class="button-row public-actions">
      <button
        class="button secondary"
        :disabled="busy"
        :aria-pressed="post.liked"
        @click="interact('LIKE')"
      >
        {{ post.liked ? "♥ 已点赞" : "♡ 点赞" }} {{ post.likeCount }}</button
      ><button
        class="button secondary"
        :disabled="busy"
        :aria-pressed="post.favorited"
        @click="interact('FAVORITE')"
      >
        {{ post.favorited ? "已收藏" : "收藏路书" }}
        {{ post.favoriteCount }}</button
      ><button class="button" :disabled="busy" @click="clone">
        保存为我的路书 ↗
      </button>
    </div>
    <TripRead :trip="trip" />
    <section v-if="post" class="paper comments">
      <h2>
        路上的声音 <small>{{ post.commentCount }}</small>
      </h2>
      <form @submit.prevent="comment">
        <label
          >写下你的想法<textarea
            v-model="content"
            maxlength="1000"
            required
            rows="3"
            placeholder="分享旅行经验，或留下想去的心情"
          ></textarea></label
        ><button class="button small" :disabled="busy">发表评论</button>
      </form>
      <article v-for="c in comments" :key="c.id" class="comment">
        <div class="row">
          <RouterLink :to="'/authors/' + c.authorId">{{
            c.authorName
          }}</RouterLink
          ><button
            v-if="c.authorId === s.profile?.id"
            class="text-button"
            :disabled="busy"
            @click="remove(c.id)"
          >
            删除
          </button>
        </div>
        <p>{{ c.content }}</p>
        <small>{{ new Date(c.createdAt).toLocaleString("zh-CN") }}</small>
      </article>
      <p v-if="!comments.length" class="empty">还没有评论，留下第一句话吧。</p>
    </section></template
  >
</template>
