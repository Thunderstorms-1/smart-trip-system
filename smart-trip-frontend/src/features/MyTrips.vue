<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { api, money } from "./api";
import { useAction } from "./ui";
import type { Trip, Post } from "./types";
import ExploreFeed from "./ExploreFeed.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
import HomeIcon from "./HomeIcon.vue";
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
  <div class="v3-library v3-content">
    <section class="v3-page-heading">
      <div>
        <h1>我的旅行库</h1>
        <p>计划、心动与走过的路，都在这里。</p>
      </div>
      <button class="button" @click="create">
        新建路书 <HomeIcon name="arrow" />
      </button>
    </section>
    <div class="library-tools">
      <div class="v3-tabs" role="group" aria-label="旅行库分类">
        <button
          v-for="t in [
            { id: 'trips', label: '我的路书' },
            { id: 'FAVORITE', label: '我的收藏' },
            { id: 'LIKE', label: '我的点赞' },
          ]"
          :key="t.id"
          :aria-pressed="tab === t.id"
          :class="{ active: tab === t.id }"
          :disabled="busy"
          @click="
            tab = t.id;
            load();
          "
        >
          {{ t.label }}
        </button>
      </div>
      <form class="library-join" @submit.prevent="join">
        <input
          v-model="inviteCode"
          required
          maxlength="43"
          aria-label="同行邀请口令"
          placeholder="输入同行邀请口令"
        /><button class="button secondary" :disabled="busy">加入同行</button>
      </form>
    </div>
    <p v-if="busy" class="v3-state" role="status">正在整理你的旅行…</p>
    <div v-else-if="error" class="v3-state" role="alert">
      <p>{{ error }}</p>
      <button class="button secondary" @click="load">重新加载</button>
    </div>
    <template v-else-if="tab === 'trips'">
      <section v-if="trips.length" aria-labelledby="recent-trip-title">
        <div class="v3-section-heading">
          <h2 id="recent-trip-title">最近的旅程</h2>
          <span>最近更新的路书</span>
        </div>
        <RouterLink :to="'/trip/edit/' + trips[0]!.id" class="library-recent">
          <JourneyBackdrop />
          <div class="recent-copy">
            <span class="recent-location"
              ><HomeIcon name="pin" />{{ trips[0]!.destination }}</span
            >
            <h2>{{ trips[0]!.title }}</h2>
            <p>{{ trips[0]!.summary || "打开路书，继续完善沿途安排。" }}</p>
            <div class="recent-facts">
              <span>{{ trips[0]!.startDate || "日期待定" }}</span
              ><span>{{
                trips[0]!.totalDays != null
                  ? trips[0]!.totalDays + " 天"
                  : "天数待定"
              }}</span
              ><span>{{
                trips[0]!.totalBudget != null
                  ? "预估 " + money(trips[0]!.totalBudget)
                  : "预算待定"
              }}</span
              ><span>{{ trips[0]!.isPublic ? "已发布" : "私密路书" }}</span>
            </div>
            <span class="recent-action"
              >打开路书 <HomeIcon name="arrow"
            /></span>
          </div>
        </RouterLink>
      </section>
      <section
        v-if="trips.length > 1"
        class="library-shelf"
        aria-labelledby="library-shelf-title"
      >
        <div class="v3-section-heading">
          <h2 id="library-shelf-title">其余旅程</h2>
          <span>{{ trips.length - 1 }} 份路书</span>
        </div>
        <RouterLink
          v-for="t in trips.slice(1)"
          :key="t.id"
          :to="'/trip/edit/' + t.id"
          class="library-row"
          ><div class="library-place">
            <HomeIcon name="pin" /><strong>{{ t.destination }}</strong>
          </div>
          <div class="library-row-copy">
            <h3>{{ t.title }}</h3>
            <p>
              {{ t.startDate || "日期待定" }} ·
              {{ t.totalDays != null ? t.totalDays + " 天" : "天数待定" }} ·
              {{ t.isPublic ? "已发布" : "私密路书" }}
            </p>
          </div>
          <span class="library-budget">{{
            t.totalBudget != null ? "预估 " + money(t.totalBudget) : "预算待定"
          }}</span
          ><HomeIcon name="arrow"
        /></RouterLink>
      </section>
      <div v-if="!trips.length" class="v3-state">
        <HomeIcon name="route" />
        <h2>把想去的地方，写成计划</h2>
        <p>创建第一份路书，旅行从这里开始。</p>
        <button class="button" @click="create">新建路书</button>
      </div>
    </template>
    <template v-else
      ><ExploreFeed :posts="assets" />
      <div v-if="!assets.length" class="v3-state">
        <h2>心动的旅程，等你收藏</h2>
        <p>这里还没有路书。去广场寻找心动的路线吧。</p>
        <RouterLink class="button secondary" to="/square"
          >探索旅行灵感 <HomeIcon name="arrow"
        /></RouterLink></div
    ></template>
  </div>
</template>
