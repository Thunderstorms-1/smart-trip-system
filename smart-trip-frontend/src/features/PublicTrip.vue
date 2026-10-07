<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "./api";
import { useAction } from "./ui";
import { useSession } from "./session";
import type { Trip, Post, Comment } from "./types";
import TripRead from "./TripRead.vue";
import { useLatestQuery } from "./useLatestQuery";
import HomeIcon from "./HomeIcon.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
const reader = ref<HTMLElement>();
function focusDay(index: number) {
  const section =
    reader.value?.querySelectorAll<HTMLElement>(".day-block")[index];
  if (!section) return;
  section.tabIndex = -1;
  section.focus({ preventScroll: true });
  section.scrollIntoView({ block: "start", behavior: "auto" });
}
const queryState = useLatestQuery();
const route = useRoute(),
  router = useRouter(),
  s = useSession(),
  post = ref<Post>(),
  trip = ref<Trip>(),
  comments = ref<Comment[]>([]),
  content = ref("");
const { busy, error, run } = useAction();
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
  <div class="v3-journal v3-content">
    <p
      v-if="(busy || queryState.loading.value) && !trip"
      class="v3-state"
      role="status"
    >
      正在打开旅行故事…
    </p>
    <div
      v-else-if="(error || queryState.error.value) && !trip"
      class="v3-state"
      role="alert"
    >
      <h2>暂时无法打开</h2>
      <p>{{ error || queryState.error.value }}</p>
      <RouterLink to="/square" class="button secondary"
        >返回旅行广场</RouterLink
      >
    </div>
    <template v-if="trip">
      <header class="journal-cover">
        <JourneyBackdrop />
        <div class="journal-cover-copy">
          <span class="journal-location"
            ><HomeIcon name="pin" />{{ trip.destination }}</span
          >
          <h1>{{ trip.title }}</h1>
          <RouterLink
            v-if="post"
            :to="'/authors/' + post.authorId"
            class="journal-author"
            ><img
              v-if="post.authorAvatar"
              :src="post.authorAvatar"
              alt="作者头像" /><HomeIcon v-else name="user" /><span
              >{{ post.authorName }} 的旅行故事</span
            ><HomeIcon name="arrow"
          /></RouterLink>
          <p v-else>这是一份独立的分享快照，作者可随时撤销链接。</p>
        </div>
      </header>
      <div v-if="post" class="journal-actions">
        <button
          class="button secondary"
          :disabled="busy"
          :aria-pressed="post.liked"
          @click="interact('LIKE')"
        >
          {{ post.liked ? "已点赞" : "点赞" }} {{ post.likeCount }}</button
        ><button
          class="button secondary"
          :disabled="busy"
          :aria-pressed="post.favorited"
          @click="interact('FAVORITE')"
        >
          {{ post.favorited ? "已收藏" : "收藏路书" }}
          {{ post.favoriteCount }}</button
        ><button class="button" :disabled="busy" @click="clone">
          保存为我的路书 <HomeIcon name="arrow" />
        </button>
      </div>
      <p v-if="error && trip" class="form-error" role="alert">{{ error }}</p>
      <div class="journal-reading">
        <aside class="journal-outline">
          <h2>沿途安排</h2>
          <p>按路书日期顺序阅读</p>
          <nav aria-label="每日行程">
            <button
              v-for="(day, index) in trip.days"
              :key="day.id || index"
              @click="focusDay(index)"
            >
              <span>第 {{ index + 1 }} 天</span
              ><strong>{{ day.dayTheme || "自由探索" }}</strong
              ><small>{{ day.dayDate || "日期待定" }}</small>
            </button>
          </nav>
          <p v-if="!trip.days.length">这份路书还没有每日安排。</p>
          <RouterLink to="/square" class="text-button"
            >探索更多旅程 <HomeIcon name="arrow"
          /></RouterLink>
        </aside>
        <div ref="reader" class="journal-content">
          <TripRead :trip="trip" />
        </div>
      </div>
      <section v-if="post" class="journal-comments">
        <div class="v3-section-heading">
          <h2>路上的声音</h2>
          <span>{{ post.commentCount }} 条评论</span>
        </div>
        <form @submit.prevent="comment">
          <label
            >写下你的想法<textarea
              v-model="content"
              maxlength="1000"
              required
              rows="3"
              placeholder="分享旅行经验，或留下想去的心情"
            ></textarea></label
          ><button class="button" :disabled="busy">发表评论</button>
        </form>
        <article v-for="c in comments" :key="c.id" class="journal-comment">
          <header>
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
          </header>
          <p>{{ c.content }}</p>
          <small>{{ new Date(c.createdAt).toLocaleString("zh-CN") }}</small>
        </article>
        <p v-if="!comments.length" class="v3-state">
          还没有评论，留下第一句话吧。
        </p>
      </section>
    </template>
  </div>
</template>
