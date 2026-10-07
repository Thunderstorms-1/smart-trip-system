<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSession } from "./session";
import { api } from "./api";
import { useAction } from "./ui";
import JourneyBackdrop from "./JourneyBackdrop.vue";
import HomeIcon from "./HomeIcon.vue";
const s = useSession(),
  route = useRoute(),
  router = useRouter(),
  register = ref(false),
  username = ref(""),
  password = ref(""),
  nickname = ref("");
const { busy, error, run } = useAction();
async function submit() {
  await run(async () => {
    if (register.value)
      await api("/auth/register", "POST", {
        username: username.value,
        password: password.value,
        nickname: nickname.value,
      });
    await s.login(username.value, password.value);
    const redirect =
      typeof route.query.redirect === "string" &&
      route.query.redirect.startsWith("/") &&
      !route.query.redirect.startsWith("//")
        ? route.query.redirect
        : "/my-trips";
    router.push(redirect);
  });
}
</script>
<template>
  <section class="v3-entry">
    <JourneyBackdrop />
    <div class="entry-copy">
      <h1>下一段旅程，<br />从这里开始。</h1>
      <p>收藏心动的路线，写下自己的旅行。</p>
      <RouterLink to="/square"
        >先探索旅行灵感 <HomeIcon name="arrow"
      /></RouterLink>
    </div>
    <form class="entry-form" @submit.prevent="submit">
      <h2>{{ register ? "创建你的账号" : "欢迎回来" }}</h2>
      <p>
        {{ register ? "拥有自己的旅行空间。" : "登录，继续你的旅行计划。" }}
      </p>
      <fieldset :disabled="busy">
        <legend class="sr-only">
          {{ register ? "注册账号" : "登录账号" }}
        </legend>
        <label
          >用户名<input
            v-model="username"
            autocomplete="username"
            required
            pattern="[a-zA-Z0-9_]{3,32}"
            placeholder="3–32位字母、数字或下划线" /></label
        ><label v-if="register"
          >昵称<input
            v-model="nickname"
            maxlength="50"
            placeholder="路上怎么称呼你" /></label
        ><label
          >密码<input
            v-model="password"
            :autocomplete="register ? 'new-password' : 'current-password'"
            type="password"
            required
            minlength="8"
            maxlength="72"
            placeholder="至少8位"
        /></label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="button full">
          {{ busy ? "请稍候…" : register ? "注册并开始旅行" : "登录" }}</button
        ><button
          type="button"
          class="text-button full"
          @click="
            register = !register;
            error = '';
          "
        >
          {{ register ? "已有账号？去登录" : "还没有账号？创建一个" }}
        </button>
      </fieldset>
    </form>
  </section>
</template>
