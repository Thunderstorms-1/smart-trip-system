<script setup lang="ts">
import { onMounted, onBeforeUnmount, computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useSession } from "./session";
import { message, severity } from "./ui";
import HomeIcon from "./HomeIcon.vue";
const s = useSession(),
  router = useRouter();
const route = useRoute();
const home = computed(() => route.path === "/");
const workbench = computed(() => /^\/trip\/edit\/[^/]+\/?$/.test(route.path));
function expired() {
  s.logout();
  router.push({
    path: "/login",
    query: { redirect: router.currentRoute.value.fullPath },
  });
}
window.addEventListener("smarttrip-session-expired", expired);
onBeforeUnmount(() =>
  window.removeEventListener("smarttrip-session-expired", expired),
);
onMounted(async () => {
  await s.loadCapabilities().catch(() => {});
  await s.refresh().catch(() => s.logout());
});
function logout() {
  s.logout();
  router.push("/");
}
</script>
<template>
  <div :class="{ 'v3-shell': home, 'workbench-v3-shell': workbench }">
    <a v-if="home" class="skip-link" href="#main-content">跳到主要内容</a>
    <header :class="home ? 'home-header' : 'site-header'">
      <RouterLink to="/" class="brand"
        ><HomeIcon v-if="home || workbench" /><span v-else class="brand-mark"
          >游</span
        ><span v-if="home || workbench"
          >SmartTrip<span class="brand-cn">智游伴路</span></span
        ><strong v-else>智游伴路<small>SMARTTRIP</small></strong></RouterLink
      >
      <nav aria-label="主导航">
        <RouterLink to="/square">旅行灵感</RouterLink
        ><RouterLink to="/ai-generate">规划旅行</RouterLink
        ><RouterLink to="/my-trips">我的路书</RouterLink>
      </nav>
      <div class="account">
        <template v-if="s.token"
          ><RouterLink
            to="/profile"
            :class="home ? 'home-account-link' : 'avatar-link'"
            aria-label="个人中心"
            ><img
              v-if="s.profile?.avatar"
              :src="s.profile.avatar"
              alt="我的头像"
            /><span v-else class="avatar-letter">{{
              s.profile?.nickname?.slice(0, 1) || "我"
            }}</span
            ><span class="account-name">{{
              s.profile?.nickname || "个人中心"
            }}</span></RouterLink
          ><button class="text-button" @click="logout">退出</button></template
        ><RouterLink
          v-else
          to="/login"
          :class="home ? 'home-login' : 'button small'"
          >登录 / 注册</RouterLink
        >
      </div>
    </header>
    <main
      id="main-content"
      :class="home ? 'home-main' : workbench ? 'workbench-main' : 'site-main'"
      tabindex="-1"
    >
      <RouterView />
    </main>
    <footer v-if="!home && !workbench" class="site-footer">
      <span>智游伴路 · 把灵感走成风景</span
      ><span>行程费用为预估，实际消费以你的记账为准。</span>
    </footer>
    <footer v-else-if="home" class="home-footer">
      <RouterLink class="brand" to="/"
        ><HomeIcon /><span
          >SmartTrip<span class="brand-cn">智游伴路</span></span
        ></RouterLink
      ><span>把心动的地方，连成自己的旅程。</span
      ><RouterLink to="/square"
        >寻找旅行灵感 <HomeIcon name="arrow"
      /></RouterLink>
    </footer>
    <Transition name="toast"
      ><div v-if="message" class="toast" :class="severity" role="status">
        {{ message }}
      </div></Transition
    >
  </div>
</template>
