<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "./api";
import type { Post } from "./types";
import PostCards from "./PostCards.vue";
import { useAction } from "./ui";
const posts = ref<Post[]>([]),
  { busy, error, run } = useAction();
onMounted(() =>
  run(
    async () =>
      (posts.value = (await api<Post[]>("/community/posts")).slice(0, 3)),
  ),
);
</script>
<template>
  <section class="hero">
    <div class="hero-copy">
      <p class="eyebrow">A LITTLE PLAN. A LOT OF WONDER.</p>
      <h1>让每一次出发，<br />都有自己的<span>节奏。</span></h1>
      <p>
        从一份灵感到一段旅程。规划路线、记录花费，<br
          class="desktop"
        />把你的旅行故事分享给下一个出发的人。
      </p>
      <div class="button-row">
        <RouterLink class="button" to="/ai-generate"
          >开始规划 <span>↗</span></RouterLink
        ><RouterLink class="button secondary" to="/square"
          >寻找旅行灵感</RouterLink
        >
      </div>
      <div class="hero-note">
        <span class="dot"></span> 为中国境内旅行打造的路书工作台
      </div>
    </div>
    <div class="hero-art" aria-hidden="true">
      <span class="compass">N<br />✧</span>
      <div class="sun"></div>
      <div class="hill hill-back"></div>
      <div class="hill hill-front"></div>
      <div class="trail"></div>
      <div class="art-caption">
        GO SOMEWHERE<br /><strong>that feels like you.</strong>
      </div>
      <span class="stamp">慢慢走<br />好好看</span>
    </div>
  </section>
  <section class="section">
    <div class="section-heading">
      <div>
        <p class="eyebrow">TRAVEL JOURNALS</p>
        <h2>下一站，从这里开始</h2>
      </div>
      <RouterLink to="/square" class="text-link">探索全部 ↗</RouterLink>
    </div>
    <p v-if="busy" class="empty">正在寻找旅行灵感…</p>
    <p v-else-if="error" class="empty">{{ error }}</p>
    <PostCards v-else-if="posts.length" :posts="posts" />
    <div v-else class="empty paper">
      <h3>第一段故事，等你出发</h3>
      <p>广场还没有公开路书。先创建自己的旅行，再与大家分享。</p>
      <RouterLink to="/my-trips" class="button secondary">创建路书</RouterLink>
    </div>
  </section>
  <section class="feature-strip">
    <div>
      <span>01 / PLAN</span>
      <h3>灵感有了落点</h3>
      <p>AI草稿与手动编辑相互衔接，每一天都可调整。</p>
    </div>
    <div>
      <span>02 / TRAVEL</span>
      <h3>路上心里有数</h3>
      <p>把路线、天气、行李和记账放在同一份路书里。</p>
    </div>
    <div>
      <span>03 / SHARE</span>
      <h3>故事值得留下</h3>
      <p>发布旅行快照，或创建可随时撤销的分享链接。</p>
    </div>
  </section>
</template>
