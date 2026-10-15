<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { useRouter } from "vue-router";
import { api } from "./api";
import type { Post, PostPage } from "./types";
import { useLatestQuery } from "./useLatestQuery";
import { useSession } from "./session";
import HomeIcon from "./HomeIcon.vue";
import SquareStoryCard from "./SquareStoryCard.vue";
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

const s = useSession();
const router = useRouter();
const backgroundFailed = ref(false);
const avatarFailed = ref(false);
const queryInput = ref<HTMLInputElement | null>(null);
const featuredPosts = computed(() => posts.value.slice(0, 3));
const compactPosts = computed(() => posts.value.slice(3));
function clearSearch() {
  q.value = "";
  search();
  queryInput.value?.focus();
}
function logout() {
  s.logout();
  router.push("/");
}
</script>

<template>
  <div
    class="square-page v3-explore"
    :class="{ 'image-failed': backgroundFailed }"
  >
    <a class="skip-link" href="#square-stories">跳到路书内容</a>
    <section class="square-hero" aria-labelledby="square-title">
      <div class="hero-photo" aria-hidden="true">
        <img
          src="/design-v3/square-scenery.png"
          alt=""
          width="1448"
          height="1086"
          fetchpriority="high"
          @error="backgroundFailed = true"
        />
      </div>
      <header class="glass-nav">
        <RouterLink class="brand" to="/" aria-label="SmartTrip 智游伴路首页"
          ><HomeIcon /><span>SmartTrip<small>智游伴路</small></span></RouterLink
        >
        <nav class="main-nav" aria-label="主导航">
          <RouterLink to="/square">旅行灵感</RouterLink
          ><RouterLink to="/ai-generate">规划旅行</RouterLink
          ><RouterLink to="/my-trips">我的路书</RouterLink>
        </nav>
        <RouterLink
          v-if="!s.token"
          class="login-link"
          :to="{ path: '/login', query: { redirect: '/square' } }"
          >登录 / 注册</RouterLink
        >
        <div v-else class="square-account">
          <RouterLink to="/profile" aria-label="个人中心"
            ><img
              v-if="s.profile?.avatar && !avatarFailed"
              :src="s.profile.avatar"
              alt="我的头像"
              @error="avatarFailed = true"
            /><span v-else class="account-letter">{{
              s.profile?.nickname?.slice(0, 1) || "我"
            }}</span
            ><span class="account-name">{{
              s.profile?.nickname || "个人中心"
            }}</span></RouterLink
          >
          <button type="button" @click="logout">退出</button>
        </div>
      </header>
      <div class="square-hero-copy">
        <h1 id="square-title" aria-label="把世界变成你的下一段旅程">
          <span>把世界变成</span><span>你的下一段旅程</span>
        </h1>
        <p>探索旅行灵感广场，沿着真实路书找到自己的方向。</p>
        <form class="square-search" @submit.prevent="search()">
          <HomeIcon name="pin" /><input
            ref="queryInput"
            v-model="q"
            maxlength="100"
            aria-label="搜索城市或路书"
            placeholder="想去哪里？搜索城市或路书"
          /><button type="submit">
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="10" cy="10" r="6.5" />
              <path d="m15 15 6 6" /></svg
            >搜索
          </button>
        </form>
        <div class="hero-ways">
          <span><strong>公开路书</strong><small>沿着真实行程探索</small></span
          ><span><strong>目的地搜索</strong><small>寻找想去的地方</small></span
          ><span
            ><strong>旅行者分享</strong><small>发现不同的旅行方式</small></span
          >
        </div>
      </div>
    </section>

    <div class="square-results">
      <section
        class="stories-section"
        id="square-stories"
        tabindex="-1"
        aria-labelledby="stories-heading"
        :aria-busy="busy"
      >
        <div class="section-heading">
          <div>
            <h2 id="stories-heading">
              {{ activeSearch ? "探索结果" : "最近更新的路书" }}
            </h2>
            <p>从一段旅程，找到下一次出发的方向。</p>
          </div>
          <a
            v-if="compactPosts.length"
            class="text-link"
            href="#square-more-stories"
            >更多旅行灵感<HomeIcon name="arrow"
          /></a>
        </div>
        <div v-if="error" class="request-error" role="alert">
          <p>{{ error }}</p>
          <button type="button" :disabled="busy" @click="search(!posts.length)">
            重新加载
          </button>
        </div>
        <p v-if="busy && !posts.length" class="loading-state" role="status">
          正在加载路书…
        </p>
        <div
          v-if="featuredPosts.length"
          class="square-featured-stories"
          :data-count="featuredPosts.length"
        >
          <SquareStoryCard
            v-for="(post, index) in featuredPosts"
            :key="post.id"
            :post="post"
            :lead="index === 0"
          />
        </div>
        <div v-if="!error && !busy && !posts.length" class="empty-state">
          <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M3 4h6a4 4 0 0 1 3 2 4 4 0 0 1 3-2h6v16h-6a4 4 0 0 0-3 2 4 4 0 0 0-3-2H3Zm9 2v16"
            />
          </svg>
          <h3>
            {{ activeSearch ? "还没有找到这段旅程" : "等待下一段真实旅程" }}
          </h3>
          <p>
            {{
              activeSearch
                ? "暂无匹配的路书，换个城市或关键词试试。"
                : "这里将呈现旅行者公开分享的路书。"
            }}
          </p>
          <button v-if="activeSearch" type="button" @click="clearSearch">
            查看全部路书
          </button>
        </div>
      </section>
      <section
        v-if="compactPosts.length"
        class="stories-section continuation"
        id="square-more-stories"
        aria-labelledby="more-heading"
      >
        <div class="section-heading">
          <div>
            <h2 id="more-heading">旅行灵感 · 发现更多目的地</h2>
            <p>在旅行者的故事里，找到下一次出发的理由。</p>
          </div>
        </div>
        <div class="square-compact-stories">
          <SquareStoryCard
            v-for="post in compactPosts"
            :key="post.id"
            :post="post"
            compact
          />
        </div>
      </section>
      <div v-if="hasMore" class="square-pagination">
        <button type="button" :disabled="busy" @click="search(false)">
          {{ busy ? "加载中…" : "查看更多" }}
        </button>
      </div>
    </div>
    <section class="travel-close" aria-labelledby="close-heading">
      <img
        src="/design-v3/login-scenery.png"
        alt=""
        width="1672"
        height="941"
      />
      <div class="close-content">
        <div>
          <h2 id="close-heading">用真实的旅行路书，连接更大的世界</h2>
          <p>来自旅行者，回到旅行者。让每一次出发，都更有方向。</p>
        </div>
        <RouterLink to="/">了解 SmartTrip<HomeIcon name="arrow" /></RouterLink>
      </div>
    </section>
    <footer class="square-footer">
      <RouterLink class="brand" to="/"
        ><HomeIcon /><span>SmartTrip<small>智游伴路</small></span></RouterLink
      >
      <p>把心动的地方，连成自己的旅程。</p>
      <RouterLink to="/ai-generate"
        >规划下一段旅程<HomeIcon name="arrow"
      /></RouterLink>
    </footer>
  </div>
</template>

<style scoped src="./square-v3.css"></style>
