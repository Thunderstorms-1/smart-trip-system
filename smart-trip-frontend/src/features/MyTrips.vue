<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "./api";
import { useAction } from "./ui";
import { useSession } from "./session";
import { useLatestQuery } from "./useLatestQuery";
import type { Trip, Post } from "./types";
import HomeIcon from "./HomeIcon.vue";
import LibraryIcon from "./LibraryIcon.vue";
import LibraryTripCard from "./LibraryTripCard.vue";
import LibraryStoryCard from "./LibraryStoryCard.vue";
import "./library-v3.css";
import "./library-motion.css";

type Tab = "trips" | "FAVORITE" | "LIKE";
type AssetTab = Exclude<Tab, "trips">;
type DisplayState = {
  input: string;
  query: string;
  sort: string;
  layout: "grid" | "list";
};
const tabs = [
  {
    id: "trips",
    view: "trips",
    label: "我的路书",
    english: "My Trips",
    description: "计划、心动与走过的路，都在这里。",
    recent: "最近更新",
    icon: "route",
  },
  {
    id: "FAVORITE",
    view: "favorites",
    label: "我的收藏",
    english: "Collections",
    description: "把喜欢的风景、路线和故事，留到下一次出发。",
    recent: "最近收藏",
    icon: "bookmark",
  },
  {
    id: "LIKE",
    view: "likes",
    label: "我的点赞",
    english: "Likes",
    description: "每一个心动的旅程，都是下次出发的灵感。",
    recent: "最近点赞",
    icon: "heart",
  },
] as const;
const route = useRoute(),
  router = useRouter(),
  s = useSession();
const tab = computed<Tab>(
  () => tabs.find((t) => t.view === route.query.view)?.id ?? "trips",
);
const current = computed(() => tabs.find((t) => t.id === tab.value)!);
const states = reactive<Record<Tab, DisplayState>>({
  trips: { input: "", query: "", sort: "recent", layout: "grid" },
  FAVORITE: { input: "", query: "", sort: "recent", layout: "grid" },
  LIKE: { input: "", query: "", sort: "recent", layout: "grid" },
});
const state = computed(() => states[tab.value]);
const trips = ref<Trip[]>([]);
const assets = reactive<Record<AssetTab, Post[]>>({ FAVORITE: [], LIKE: [] });
const loaded = reactive<Record<Tab, boolean>>({
  trips: false,
  FAVORITE: false,
  LIKE: false,
});
const { loading: busy, error, query } = useLatestQuery();
const joinAction = useAction();
const showInvite = ref(false),
  inviteCode = ref("");
const inviteInput = ref<HTMLInputElement>(),
  joinToggle = ref<HTMLButtonElement>();
const searchInput = ref<HTMLInputElement>(),
  tabGroup = ref<HTMLDivElement>();
const imageFailed = ref(false),
  avatarFailed = ref(false),
  entranceKey = ref(0);
watch(
  () => s.profile?.avatar,
  () => {
    avatarFailed.value = false;
  },
);

function load() {
  const target = tab.value;
  if (target === "trips") {
    void query(
      (signal) => api<Trip[]>("/workbench", "GET", undefined, signal),
      (result) => {
        trips.value = result;
        loaded.trips = true;
      },
    );
  } else {
    void query(
      (signal) =>
        api<Post[]>(
          "/community/assets?type=" + target,
          "GET",
          undefined,
          signal,
        ),
      (result) => {
        assets[target] = result;
        loaded[target] = true;
      },
    );
  }
}
watch(tab, () => {
  entranceKey.value++;
  load();
});
function selectTab(next: Tab) {
  if (next === tab.value || joinAction.busy.value) return;
  void router.push({
    path: "/my-trips",
    query: { ...route.query, view: tabs.find((t) => t.id === next)!.view },
  });
}
async function tabKey(event: KeyboardEvent) {
  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  if (joinAction.busy.value) return;
  const index = tabs.findIndex((t) => t.id === tab.value);
  const next =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? tabs.length - 1
        : (index + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) %
          tabs.length;
  selectTab(tabs[next]!.id);
  await nextTick();
  tabGroup.value
    ?.querySelector<HTMLButtonElement>(`[data-tab="${tabs[next]!.id}"]`)
    ?.focus();
}
function count(id: Tab) {
  return id === "trips" ? trips.value.length : assets[id].length;
}
const loadedCount = computed(() => count(tab.value));
const searchTerm = computed(() => state.value.query.trim().toLocaleLowerCase());
function matches(...values: (string | undefined)[]) {
  return values.join(" ").toLocaleLowerCase().includes(searchTerm.value);
}
const visibleTrips = computed(() => {
  const result = trips.value.filter((t) => matches(t.title, t.destination));
  if (state.value.sort === "title")
    result.sort((a, b) => a.title.localeCompare(b.title, "zh-CN"));
  if (state.value.sort === "date")
    result.sort((a, b) => (b.startDate || "").localeCompare(a.startDate || ""));
  return result;
});
const visiblePosts = computed(() => {
  if (tab.value === "trips") return [];
  const result = assets[tab.value].filter((p) =>
    matches(p.title, p.destination, p.authorName),
  );
  if (state.value.sort === "title")
    result.sort((a, b) => a.title.localeCompare(b.title, "zh-CN"));
  if (state.value.sort === "likes")
    result.sort((a, b) => b.likeCount - a.likeCount);
  return result;
});
const visibleCount = computed(() =>
  tab.value === "trips" ? visibleTrips.value.length : visiblePosts.value.length,
);
const leadHeading = computed(() =>
  searchTerm.value
    ? "匹配的旅程"
    : state.value.sort === "recent"
      ? "最近的旅程"
      : "当前旅程",
);
function search() {
  state.value.query = state.value.input.trim();
}
function clearSearch() {
  state.value.input = "";
  state.value.query = "";
  searchInput.value?.focus();
}
function create() {
  void router.push("/ai-generate?mode=manual");
}
async function closeInvite() {
  if (joinAction.busy.value) return;
  showInvite.value = false;
  await nextTick();
  joinToggle.value?.focus();
}
async function toggleInvite() {
  if (showInvite.value) return closeInvite();
  joinAction.error.value = "";
  showInvite.value = true;
  await nextTick();
  inviteInput.value?.focus();
}
function inviteKey(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    void closeInvite();
  }
}
function join() {
  void joinAction.run(async () => {
    const result = await api<{ tripId: number }>("/membership/join", "POST", {
      inviteCode: inviteCode.value,
    });
    await router.push("/trip/edit/" + result.tripId);
  });
}
function logout() {
  s.logout();
  void router.push("/");
}
function replayEntrance(event: PageTransitionEvent) {
  if (event.persisted) entranceKey.value++;
}
onMounted(() => {
  load();
  window.addEventListener("pageshow", replayEntrance);
});
onBeforeUnmount(() => window.removeEventListener("pageshow", replayEntrance));

function retireView(element: Element) {
  (element as HTMLElement).inert = true;
}
function restoreView(element: Element) {
  (element as HTMLElement).inert = false;
}
function freezeLeavingCard(element: Element) {
  const card = element as HTMLElement;
  const rect = card.getBoundingClientRect();
  card.style.setProperty("--motion-card-width", rect.width + "px");
  card.style.setProperty("--motion-card-height", rect.height + "px");
  card.style.setProperty("--motion-card-left", card.offsetLeft + "px");
  card.style.setProperty("--motion-card-top", card.offsetTop + "px");
  card.inert = true;
}
function releaseLeavingCard(element: Element) {
  const card = element as HTMLElement;
  for (const name of ["width", "height", "left", "top"])
    card.style.removeProperty("--motion-card-" + name);
  card.inert = false;
}
</script>

<template>
  <div class="library-page v3-library" :class="{ 'image-failed': imageFailed }">
    <a class="skip-link" href="#library-panel">跳到路书内容</a>
    <div class="landscape" aria-hidden="true">
      <img
        v-if="!imageFailed"
        src="/design-v3/library-scenery.png"
        alt=""
        width="1672"
        height="941"
        fetchpriority="high"
        @error="imageFailed = true"
      />
    </div>
    <header class="glass-nav">
      <RouterLink class="brand" to="/" aria-label="SmartTrip 智游伴路首页"
        ><HomeIcon /><span>SmartTrip<small>智游伴路</small></span></RouterLink
      >
      <nav class="main-nav" aria-label="主导航">
        <RouterLink to="/square">旅行灵感</RouterLink
        ><RouterLink to="/ai-generate">规划旅行</RouterLink
        ><RouterLink to="/my-trips" aria-current="page">我的路书</RouterLink>
      </nav>
      <div v-if="s.token" class="library-account">
        <RouterLink to="/profile" aria-label="个人中心"
          ><img
            v-if="s.profile?.avatar && !avatarFailed"
            :src="s.profile.avatar"
            alt="我的头像"
            @error="avatarFailed = true"
          /><span v-else class="account-letter">{{
            s.profile?.nickname?.slice(0, 1) || "我"
          }}</span
          ><span class="library-account-name">{{
            s.profile?.nickname || "个人中心"
          }}</span></RouterLink
        ><button type="button" @click="logout">退出</button>
      </div>
      <RouterLink
        v-else
        class="login-link"
        :to="{ path: '/login', query: { redirect: route.fullPath } }"
        >登录 / 注册</RouterLink
      >
    </header>
    <div class="library-main">
      <section class="library-heading v3-page-heading">
        <div :key="entranceKey" class="library-title is-entering">
          <h1 :aria-label="current.label">
            {{ current.label
            }}<em lang="en" aria-hidden="true">{{ current.english }}</em>
          </h1>
          <p>{{ current.description }}</p>
        </div>
        <div class="heading-actions">
          <button
            ref="joinToggle"
            class="join-toggle"
            type="button"
            :aria-expanded="showInvite"
            aria-controls="library-invite"
            :disabled="joinAction.busy.value"
            @click="toggleInvite"
          >
            <LibraryIcon name="users" />{{
              showInvite ? "收起邀请" : "加入同行"
            }}</button
          ><button class="create-link" type="button" @click="create">
            新建路书 <LibraryIcon name="plus" />
          </button>
        </div>
      </section>
      <div class="library-tools">
        <div
          ref="tabGroup"
          class="library-tabs"
          role="group"
          aria-label="旅行库分类"
          @keydown="tabKey"
        >
          <button
            v-for="item in tabs"
            :id="'library-tab-' + item.id"
            :key="item.id"
            type="button"
            :data-tab="item.id"
            :aria-label="item.label"
            :aria-pressed="tab === item.id"
            aria-controls="library-panel"
            :disabled="joinAction.busy.value"
            @click="selectTab(item.id)"
          >
            <LibraryIcon :name="item.icon" />{{ item.label
            }}<span
              v-if="loaded[item.id]"
              class="tab-count"
              aria-hidden="true"
              >{{ count(item.id) }}</span
            >
          </button>
        </div>
        <form class="library-search" role="search" @submit.prevent="search">
          <LibraryIcon name="search" /><input
            ref="searchInput"
            v-model="state.input"
            type="search"
            :aria-label="'搜索' + current.label"
            :placeholder="'搜索' + current.label + '…'"
            maxlength="100"
          /><button
            v-if="state.input"
            class="clear-search"
            type="button"
            aria-label="清空搜索"
            @click="clearSearch"
          >
            <LibraryIcon name="close" /></button
          ><button type="submit">搜索</button>
        </form>
      </div>
      <Transition
        name="motion-panel"
        @before-leave="retireView"
        @leave-cancelled="restoreView"
      >
        <section
          v-if="showInvite"
          id="library-invite"
          class="library-invite"
          aria-labelledby="invite-title"
          @keydown="inviteKey"
        >
          <div>
            <h2 id="invite-title">加入一段同行的旅程</h2>
            <p>粘贴收到的邀请口令，打开同行路书。</p>
          </div>
          <form class="library-join" @submit.prevent="join">
            <input
              ref="inviteInput"
              v-model="inviteCode"
              required
              minlength="43"
              maxlength="43"
              pattern="[A-Za-z0-9_\-]{43}"
              autocomplete="off"
              aria-label="同行邀请口令"
              placeholder="输入同行邀请口令"
              :disabled="joinAction.busy.value"
            /><button class="invite-submit" :disabled="joinAction.busy.value">
              {{ joinAction.busy.value ? "正在加入…" : "确认加入" }}
            </button>
          </form>
          <button
            class="close-invite"
            type="button"
            aria-label="关闭同行邀请"
            :disabled="joinAction.busy.value"
            @click="closeInvite"
          >
            <LibraryIcon name="close" />
          </button>
          <p v-if="joinAction.error.value" class="join-error" role="alert">
            {{ joinAction.error.value }}
          </p>
        </section>
      </Transition>
      <section
        id="library-panel"
        class="library-workspace"
        :class="{ 'list-view': state.layout === 'list' }"
        :aria-labelledby="'library-tab-' + tab"
        :aria-busy="busy"
        tabindex="0"
      >
        <div class="workspace-heading">
          <p role="status">
            {{
              busy
                ? "正在整理你的旅行…"
                : searchTerm
                  ? `找到 ${visibleCount} 份路书 · 已载入 ${loadedCount} 份`
                  : `已载入 ${loadedCount} 份路书`
            }}
          </p>
          <div class="workspace-controls">
            <select v-model="state.sort" aria-label="路书排序">
              <option value="recent">{{ current.recent }}</option>
              <option value="title">按标题排序</option>
              <option v-if="tab === 'trips'" value="date">按出发日期</option>
              <option v-else value="likes">按点赞数</option>
            </select>
            <div class="layout-toggle" role="group" aria-label="展示方式">
              <button
                type="button"
                aria-label="卡片展示"
                :aria-pressed="state.layout === 'grid'"
                @click="state.layout = 'grid'"
              >
                <LibraryIcon name="grid" /></button
              ><button
                type="button"
                aria-label="列表展示"
                :aria-pressed="state.layout === 'list'"
                @click="state.layout = 'list'"
              >
                <LibraryIcon name="list" />
              </button>
            </div>
          </div>
        </div>
        <div class="library-motion-stage">
          <Transition
            name="library-motion-view"
            @before-leave="retireView"
            @leave-cancelled="restoreView"
          >
            <div :key="tab + ':' + state.layout" class="library-motion-view">
              <p v-if="busy" class="library-state" role="status">
                正在整理你的旅行…
              </p>
              <div v-else-if="error" class="library-state" role="alert">
                <p>{{ error }}</p>
                <button type="button" class="state-action" @click="load">
                  重新加载
                </button>
              </div>
              <template v-else-if="visibleCount">
                <template v-if="tab === 'trips'">
                  <div v-if="state.layout === 'grid'" class="trip-panels">
                    <section
                      class="recent-section"
                      aria-labelledby="recent-trip-title"
                    >
                      <div class="section-heading">
                        <h2 id="recent-trip-title">{{ leadHeading }}</h2>
                        <LibraryIcon name="arrow" />
                      </div>
                      <TransitionGroup
                        name="library-motion-list"
                        tag="div"
                        class="library-feature-slot"
                        appear
                        @before-leave="freezeLeavingCard"
                        @after-leave="releaseLeavingCard"
                        @leave-cancelled="releaseLeavingCard"
                      >
                        <LibraryTripCard
                          v-for="trip in visibleTrips.slice(0, 1)"
                          :key="trip.id"
                          :trip="trip"
                          featured
                        />
                      </TransitionGroup>
                    </section>
                    <section
                      v-if="visibleTrips.length > 1"
                      class="shelf-section"
                      aria-labelledby="library-shelf-title"
                    >
                      <div class="section-heading">
                        <h2 id="library-shelf-title">其他路书</h2>
                        <LibraryIcon name="arrow" />
                      </div>
                      <TransitionGroup
                        name="library-motion-list"
                        tag="div"
                        class="trip-shelf"
                        appear
                        @before-leave="freezeLeavingCard"
                        @after-leave="releaseLeavingCard"
                        @leave-cancelled="releaseLeavingCard"
                      >
                        <LibraryTripCard
                          v-for="trip in visibleTrips.slice(1, 5)"
                          :key="trip.id"
                          :trip="trip"
                        />
                      </TransitionGroup>
                    </section>
                  </div>
                  <TransitionGroup
                    v-else
                    name="library-motion-list"
                    tag="div"
                    class="library-trip-list"
                    appear
                    @before-leave="freezeLeavingCard"
                    @after-leave="releaseLeavingCard"
                    @leave-cancelled="releaseLeavingCard"
                  >
                    <LibraryTripCard
                      v-for="trip in visibleTrips"
                      :key="trip.id"
                      :trip="trip"
                    />
                  </TransitionGroup>
                  <section
                    v-if="state.layout === 'grid' && visibleTrips.length > 5"
                    class="library-remaining"
                    aria-labelledby="remaining-title"
                  >
                    <div class="section-heading">
                      <h2 id="remaining-title">继续浏览</h2>
                    </div>
                    <TransitionGroup
                      name="library-motion-list"
                      tag="div"
                      class="remaining-grid"
                      appear
                      @before-leave="freezeLeavingCard"
                      @after-leave="releaseLeavingCard"
                      @leave-cancelled="releaseLeavingCard"
                    >
                      <LibraryTripCard
                        v-for="trip in visibleTrips.slice(5)"
                        :key="trip.id"
                        :trip="trip"
                      />
                    </TransitionGroup>
                  </section>
                </template>
                <TransitionGroup
                  v-else
                  name="library-motion-list"
                  tag="div"
                  class="saved-grid"
                  appear
                  @before-leave="freezeLeavingCard"
                  @after-leave="releaseLeavingCard"
                  @leave-cancelled="releaseLeavingCard"
                >
                  <LibraryStoryCard
                    v-for="post in visiblePosts"
                    :key="post.id"
                    :post="post"
                  />
                </TransitionGroup>
                <p
                  v-if="tab !== 'trips' && loadedCount >= 200"
                  class="loaded-limit"
                >
                  当前展示最近的 200 份{{
                    tab === "FAVORITE" ? "收藏" : "点赞"
                  }}。
                </p>
              </template>
              <div v-else class="library-empty">
                <LibraryIcon :name="searchTerm ? 'search' : 'route'" />
                <h2>
                  {{
                    searchTerm
                      ? "还没有找到这段旅程"
                      : tab === "trips"
                        ? "把想去的地方，写成计划"
                        : tab === "FAVORITE"
                          ? "心动的旅程，等你收藏"
                          : "下一份旅行灵感，等你发现"
                  }}
                </h2>
                <p>
                  {{
                    searchTerm
                      ? "换个关键词，或清空搜索继续看看。"
                      : tab === "trips"
                        ? "创建第一份路书，旅行从这里开始。"
                        : "这里还没有路书。去广场寻找心动的路线吧。"
                  }}
                </p>
                <button v-if="searchTerm" type="button" @click="clearSearch">
                  清空搜索</button
                ><button
                  v-else-if="tab === 'trips'"
                  type="button"
                  @click="create"
                >
                  新建路书</button
                ><RouterLink v-else class="state-action" to="/square"
                  >探索旅行灵感 <LibraryIcon name="arrow"
                /></RouterLink>
              </div>
            </div>
          </Transition>
        </div>
      </section>
    </div>
    <footer class="library-footer">
      <span>行程费用为预估，实际消费以你的记账为准。</span
      ><RouterLink to="/square"
        >发现更多旅行灵感 <LibraryIcon name="arrow"
      /></RouterLink>
    </footer>
  </div>
</template>
