<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { api, money } from "./api";
import { useAction } from "./ui";
import type { Trip, Post } from "./types";
import PostCards from "./PostCards.vue";
const router = useRouter(),
  trips = ref<Trip[]>([]),
  assets = ref<Post[]>([]),
  tab = ref("trips"),
  inviteCode = ref("");
const { busy, error, run } = useAction();
function load() {
  run(async () => {
    if (tab.value === "trips") trips.value = await api<Trip[]>("/workbench");
    else
      assets.value = await api<Post[]>("/community/assets?type=" + tab.value);
  });
}
function join() {
  run(async () => {
    const v = await api<{ tripId: number }>("/membership/join", "POST", {
      inviteCode: inviteCode.value,
    });
    router.push("/trip/edit/" + v.tripId);
  });
}
function create() {
  router.push("/ai-generate?mode=manual");
}
onMounted(load);
</script>
<template>
  <section class="page-title row">
    <div>
      <p class="eyebrow">YOUR TRAVEL COLLECTION</p>
      <h1>我的旅行资产</h1>
      <p>计划、心动与走过的路，都在这里。</p>
    </div>
    <button class="button" @click="create">＋ 新建路书</button>
  </section>
  <form class="search-bar" @submit.prevent="join">
    <input
      v-model="inviteCode"
      required
      maxlength="43"
      aria-label="同行邀请口令"
      placeholder="输入邀请口令，加入同行路书"
    /><button class="button secondary" :disabled="busy">加入同行</button>
  </form>
  <div class="tabs" role="tablist">
    <button
      v-for="t in [
        { id: 'trips', label: '我的路书' },
        { id: 'FAVORITE', label: '我的收藏' },
        { id: 'LIKE', label: '我的点赞' },
      ]"
      :key="t.id"
      :class="{ active: tab === t.id }"
      @click="
        tab = t.id;
        load();
      "
    >
      {{ t.label }}
    </button>
  </div>
  <p v-if="busy" class="empty">正在整理你的旅行…</p>
  <p v-else-if="error" class="empty">{{ error }}</p>
  <template v-else-if="tab === 'trips'"
    ><div class="card-grid">
      <RouterLink
        v-for="(t, n) in trips"
        :key="t.id"
        :to="'/trip/edit/' + t.id"
        class="travel-card"
        ><div class="card-art" :class="'art-' + (n % 3)">
          <span class="art-city">{{ t.destination }}</span
          ><span class="art-days">{{ t.totalDays }} DAYS</span>
        </div>
        <div class="card-body">
          <small
            >{{ t.startDate || "日期待定" }} ·
            {{ t.isPublic ? "已发布" : "私密路书" }}</small
          >
          <h3>{{ t.title }}</h3>
          <p>{{ t.summary || "你的下一段旅程，正在成形。" }}</p>
          <footer>
            <span>预估 {{ money(t.totalBudget) }}</span
            ><span>继续编辑 ↗</span>
          </footer>
        </div></RouterLink
      >
    </div>
    <div v-if="!trips.length" class="empty paper">
      <h3>把想去的地方，写成计划</h3>
      <p>创建第一份路书，旅行从这里开始。</p>
      <button class="button" @click="create">新建路书</button>
    </div></template
  ><template v-else
    ><PostCards :posts="assets" />
    <div v-if="!assets.length" class="empty paper">
      这里还没有路书。去广场寻找心动的路线吧。
    </div></template
  >
</template>
