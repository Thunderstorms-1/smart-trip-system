<script setup lang="ts">
import { onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { useSession } from "./session";
import { message, severity } from "./ui";
const s = useSession(),
  router = useRouter();
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
  <header class="site-header">
    <RouterLink to="/" class="brand"
      ><span class="brand-mark">游</span
      ><strong>智游伴路<small>SMARTTRIP</small></strong></RouterLink
    >
    <nav aria-label="主导航">
      <RouterLink to="/square">旅行灵感</RouterLink
      ><RouterLink to="/ai-generate">规划旅行</RouterLink
      ><RouterLink to="/my-trips">我的路书</RouterLink>
    </nav>
    <div class="account">
      <template v-if="s.token"
        ><RouterLink to="/profile" class="avatar-link"
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
      ><RouterLink v-else to="/login" class="button small"
        >登录 / 注册</RouterLink
      >
    </div>
  </header>
  <main class="site-main"><RouterView /></main>
  <footer class="site-footer">
    <span>智游伴路 · 把灵感走成风景</span
    ><span>行程费用为预估，实际消费以你的记账为准。</span>
  </footer>
  <Transition name="toast"
    ><div v-if="message" class="toast" :class="severity" role="status">
      {{ message }}
    </div></Transition
  >
</template>
