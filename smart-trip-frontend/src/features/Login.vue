<script setup lang="ts">
import { ref } from "vue";
import LoginTravelCanvas from "./LoginTravelCanvas.vue";
import { useLoginMotion } from "./useLoginMotion";
import { useRoute, useRouter } from "vue-router";
import { useSession } from "./session";
import { api } from "./api";
import { useAction } from "./ui";
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

const scene = ref<HTMLElement | null>(null);
useLoginMotion(scene);
const passwordVisible = ref(false);
function toggleMode() {
  if (busy.value) return;
  register.value = !register.value;
  error.value = "";
}
function logout() {
  s.logout();
  router.push("/");
}
</script>
<template>
  <section ref="scene" class="login-v3 entry-scene v3-entry">
    <svg
      class="svg-library"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <defs>
        <symbol id="login-i-route" viewBox="0 0 24 24">
          <path d="M6 5a2 2 0 1 0 0 .1M18 19a2 2 0 1 0 0 .1M6 7v5h12v5" />
        </symbol>
        <symbol id="login-i-arrow" viewBox="0 0 24 24">
          <path d="M5 12h14m-5-5 5 5-5 5" />
        </symbol>
        <symbol id="login-i-user" viewBox="0 0 24 24">
          <path
            d="M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-2a8 8 0 0 1 16 0v2"
          />
        </symbol>
        <symbol id="login-i-lock" viewBox="0 0 24 24">
          <rect x="5" y="10" width="14" height="11" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3" />
        </symbol>
        <symbol id="login-i-eye" viewBox="0 0 24 24">
          <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
          <circle cx="12" cy="12" r="3" />
        </symbol>
        <symbol id="login-i-compass" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="9" />
          <path d="m16 8-2 6-6 2 2-6Z" />
        </symbol>
        <symbol id="login-i-map" viewBox="0 0 24 24">
          <path d="m3 5 6-3 6 3 6-3v17l-6 3-6-3-6 3Zm6-3v17m6-14v17" />
        </symbol>
        <symbol id="login-i-people" viewBox="0 0 24 24">
          <circle cx="9" cy="7" r="3" />
          <path
            d="M3 21v-3a6 6 0 0 1 12 0v3ZM16 4a3 3 0 0 1 0 6m2 4a5 5 0 0 1 3 4v3"
          />
        </symbol>
        <symbol id="login-i-spark" viewBox="0 0 24 24">
          <path
            d="m12 3 2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3ZM3 3v2m17 15v2"
          />
        </symbol>
        <symbol id="login-i-pin" viewBox="0 0 24 28">
          <path d="M21 11c0 6-9 15-9 15S3 17 3 11a9 9 0 0 1 18 0Z" />
          <circle cx="12" cy="11" r="3" />
        </symbol>
      </defs>
    </svg>
    <a class="skip-link" href="#auth-panel">跳到登录</a>
    <header class="entry-header">
      <RouterLink class="brand" to="/" aria-label="SmartTrip 智游伴路首页"
        ><svg class="icon" aria-hidden="true"><use href="#login-i-route" /></svg
        ><span>SmartTrip<small>智游伴路</small></span></RouterLink
      >
      <nav class="main-nav" aria-label="主导航">
        <RouterLink to="/square">旅行灵感</RouterLink
        ><RouterLink to="/ai-generate">规划旅行</RouterLink
        ><RouterLink to="/my-trips">我的路书</RouterLink>
      </nav>
      <a v-if="!s.token" class="auth-shortcut" href="#auth-panel"
        >登录 / 注册</a
      >
      <div v-else class="login-account">
        <RouterLink to="/profile" aria-label="个人中心"
          ><img
            v-if="s.profile?.avatar"
            :src="s.profile.avatar"
            alt="我的头像"
          /><span v-else class="account-letter">{{
            s.profile?.nickname?.slice(0, 1) || "我"
          }}</span
          ><span class="account-name">{{
            s.profile?.nickname || "个人中心"
          }}</span></RouterLink
        ><button type="button" @click="logout">退出</button>
      </div>
      <RouterLink v-if="!s.token" class="mobile-explore" to="/square"
        >探索灵感<svg class="icon" aria-hidden="true">
          <use href="#login-i-arrow" /></svg
      ></RouterLink>
    </header>
    <div class="entry-main">
      <LoginTravelCanvas class="entry-canvas" />
      <section
        class="auth-panel"
        id="auth-panel"
        :class="{ 'is-register': register }"
        aria-labelledby="auth-title"
        tabindex="-1"
      >
        <svg class="travel-stamp" viewBox="0 0 150 95" aria-hidden="true">
          <circle cx="49" cy="46" r="36" />
          <circle cx="49" cy="46" r="29" stroke-dasharray="2 5" />
          <path
            d="m30 44 16 1 12-16 5 2-7 18 12 6-2 4-14-4-8 14-4-1 3-17-14-3Zm54-20c10 8 21-8 34-2m-31 8c10 8 23-8 34-2m-32 8c10 8 23-8 34-2m-33 8c10 8 23-8 34-2m-34 8c10 8 23-8 34-2"
          />
        </svg>
        <h2 id="auth-title">{{ register ? "创建你的账号" : "欢迎回来" }}</h2>
        <p class="auth-intro" id="auth-intro">
          {{ register ? "拥有自己的旅行空间。" : "登录，继续你的旅行计划。" }}
        </p>
        <form id="auth-form" @submit.prevent="submit" :aria-busy="busy">
          <fieldset id="auth-fields" :disabled="busy">
            <legend class="sr-only" id="auth-legend">
              {{ register ? "注册账号" : "登录账号" }}
            </legend>
            <div class="field">
              <label for="username">用户名</label>
              <div class="input-wrap">
                <svg class="input-icon icon" aria-hidden="true">
                  <use href="#login-i-user" /></svg
                ><input
                  v-model="username"
                  id="username"
                  autocomplete="username"
                  placeholder="3–32位字母、数字或下划线"
                  pattern="[a-zA-Z0-9_]{3,32}"
                  minlength="3"
                  maxlength="32"
                  required
                  aria-describedby="username-hint"
                />
              </div>
              <p id="username-hint" class="field-hint" v-show="register">
                3–32 位字母、数字或下划线
              </p>
            </div>
            <Transition name="motion-panel">
              <div class="field" id="nickname-field" v-if="register">
                <label for="nickname"
                  >昵称<span class="optional">选填</span></label
                >
                <div class="input-wrap">
                  <svg class="input-icon icon" aria-hidden="true">
                    <use href="#login-i-user" /></svg
                  ><input
                    v-model="nickname"
                    id="nickname"
                    autocomplete="nickname"
                    placeholder="旅行中，怎么称呼你？"
                    maxlength="50"
                  />
                </div>
              </div>
            </Transition>
            <div class="field">
              <label for="password">密码</label>
              <div class="input-wrap password-input">
                <svg class="input-icon icon" aria-hidden="true">
                  <use href="#login-i-lock" /></svg
                ><input
                  v-model="password"
                  id="password"
                  :type="passwordVisible ? 'text' : 'password'"
                  :autocomplete="register ? 'new-password' : 'current-password'"
                  placeholder="输入你的密码，至少8位"
                  minlength="8"
                  maxlength="72"
                  required
                  aria-describedby="password-hint"
                /><button
                  class="password-toggle"
                  type="button"
                  :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
                  :aria-pressed="passwordVisible"
                  @click="passwordVisible = !passwordVisible"
                >
                  <svg class="icon" aria-hidden="true">
                    <use href="#login-i-eye" />
                  </svg>
                </button>
              </div>
              <p id="password-hint" class="field-hint" v-show="register">
                8–72 个字符
              </p>
            </div>
            <Transition name="motion-fade">
              <p v-if="error" id="auth-error" class="form-error" role="alert">
                {{ error }}
              </p>
            </Transition>
            <button class="submit-button" type="submit" id="auth-submit">
              <span>{{
                busy ? "请稍候…" : register ? "注册并登录" : "登录"
              }}</span
              ><svg class="icon" aria-hidden="true">
                <use href="#login-i-arrow" />
              </svg>
            </button>
          </fieldset>
        </form>

        <p class="account-switch">
          <span id="switch-prompt">{{
            register ? "已经有账号？" : "还没有账号？"
          }}</span
          ><button
            id="mode-switch"
            type="button"
            :disabled="busy"
            @click="toggleMode"
          >
            {{ register ? "返回登录" : "创建账号" }}
          </button>
        </p>
      </section>

      <nav class="journey-tools" data-login-extra aria-label="旅行功能">
        <RouterLink class="tool-inspiration" to="/square">
          <svg class="tool-icon" viewBox="0 0 32 32" aria-hidden="true">
            <circle cx="16" cy="16" r="11" />
            <g class="compass-needle">
              <path d="m21 11-3 7-7 3 3-7Z" />
              <path d="m14 14 4 4" />
            </g>
            <path class="tool-quiet-line" d="M16 2v3m0 22v3M2 16h3m22 0h3" />
          </svg>
          <span><strong>收藏灵感</strong><small>寻找心动的风景</small></span>
        </RouterLink>
        <RouterLink class="tool-planning" to="/ai-generate">
          <svg class="tool-icon" viewBox="0 0 32 32" aria-hidden="true">
            <path
              class="tool-quiet-line"
              d="M5 24C7 12 24 27 25 13S13 8 16 4"
            />
            <path
              class="tool-route-progress"
              pathLength="1"
              d="M5 24C7 12 24 27 25 13S13 8 16 4"
            />
            <circle cx="5" cy="25" r="2.5" />
            <circle cx="16" cy="4" r="2" />
          </svg>
          <span><strong>规划路线</strong><small>构建自己的旅程</small></span>
        </RouterLink>
        <RouterLink class="tool-sharing" to="/my-trips">
          <svg class="tool-icon" viewBox="0 0 32 32" aria-hidden="true">
            <path
              class="tool-share-path"
              pathLength="1"
              d="m9 17 13-9M9 17l13 8"
            />
            <circle class="share-point share-point-a" cx="6" cy="17" r="3.5" />
            <circle class="share-point share-point-b" cx="25" cy="6" r="3.5" />
            <circle class="share-point share-point-c" cx="25" cy="26" r="3.5" />
          </svg>
          <span><strong>分享路书</strong><small>记录走过的世界</small></span>
        </RouterLink>
      </nav>
    </div>
    <footer class="entry-footer" data-login-extra>
      <span>智游伴路 · 把灵感走成风景</span>
      <span>抽象旅行画布 · 非实际地图或导航路线</span>
    </footer>
  </section>
</template>
<style scoped src="./login-v3.css"></style>
