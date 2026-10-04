<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useSession } from "./session";
import { api } from "./api";
import { useAction } from "./ui";
import type { Profile } from "./types";
const s = useSession(),
  router = useRouter(),
  form = reactive({
    nickname: "",
    bio: "",
    homeCity: "",
    travelPreferences: "",
  }),
  password = reactive({ currentPassword: "", newPassword: "" }),
  tab = ref("profile");
const { busy, error, run } = useAction();
onMounted(() =>
  run(async () => {
    await s.refresh();
    Object.assign(form, {
      nickname: s.profile?.nickname || "",
      bio: s.profile?.bio || "",
      homeCity: s.profile?.homeCity || "",
      travelPreferences: s.profile?.travelPreferences || "",
    });
  }),
);
function save() {
  run(async () => {
    s.profile = await api<Profile>("/user/profile", "PUT", form);
  }, "资料已更新");
}
function upload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  const data = new FormData();
  data.append("file", file);
  run(async () => {
    await api("/user/avatar", "POST", data);
    await s.refresh();
  }, "头像已更新");
}
function change() {
  run(async () => {
    await api("/user/password", "PUT", password);
    s.logout();
    router.push("/login");
  }, "密码已修改，请重新登录");
}
</script>
<template>
  <section class="page-title">
    <p class="eyebrow">A LITTLE ABOUT YOU</p>
    <h1>个人中心</h1>
    <p>让下一次旅行，更懂你的喜好。</p>
  </section>
  <div class="profile-layout">
    <aside class="paper profile-card">
      <img
        v-if="s.profile?.avatar"
        class="profile-avatar"
        :src="s.profile.avatar"
        alt="当前头像"
      /><span v-else class="profile-avatar avatar-letter">{{
        s.profile?.nickname?.slice(0, 1) || "游"
      }}</span>
      <h2>{{ s.profile?.nickname }}</h2>
      <p>{{ s.profile?.homeCity || "常住城市待填写" }}</p>
      <label class="button secondary small"
        >更换头像<input
          type="file"
          accept="image/png,image/jpeg"
          class="sr-only"
          :disabled="busy"
          @change="upload" /></label
      ><small>PNG / JPEG，最大2MB</small
      ><RouterLink to="/my-trips" class="text-link">查看我的路书 →</RouterLink
      ><RouterLink
        v-if="s.profile"
        :to="'/authors/' + s.profile.id"
        class="text-link"
        >查看公开主页 →</RouterLink
      >
    </aside>
    <div class="paper profile-form">
      <div class="tabs">
        <button :class="{ active: tab === 'profile' }" @click="tab = 'profile'">
          旅行资料</button
        ><button
          :class="{ active: tab === 'security' }"
          @click="tab = 'security'"
        >
          账号安全
        </button>
      </div>
      <form v-if="tab === 'profile'" @submit.prevent="save">
        <label
          >昵称<input v-model="form.nickname" required maxlength="50" /></label
        ><label
          >个人简介<textarea
            v-model="form.bio"
            maxlength="500"
            rows="3"
            placeholder="用几句话介绍路上的自己"
          ></textarea
          ><small
            >{{ form.bio.length }} / 500 · 简介会显示在公开主页</small
          ></label
        ><label
          >常住城市<input
            v-model="form.homeCity"
            maxlength="50"
            placeholder="如 成都" /></label
        ><label
          >旅行偏好<textarea
            v-model="form.travelPreferences"
            maxlength="1000"
            rows="3"
            placeholder="例如喜欢博物馆、少走回头路、每天留出休息时间"
          ></textarea
          ><small>偏好仅在自己的个人中心显示</small></label
        ><button class="button" :disabled="busy">
          {{ busy ? "保存中…" : "保存资料" }}
        </button>
      </form>
      <form v-else @submit.prevent="change">
        <p class="muted">修改密码后，当前账号的所有登录都会失效。</p>
        <label
          >当前密码<input
            v-model="password.currentPassword"
            type="password"
            autocomplete="current-password"
            required /></label
        ><label
          >新密码<input
            v-model="password.newPassword"
            type="password"
            autocomplete="new-password"
            minlength="8"
            maxlength="72"
            required /></label
        ><button class="button" :disabled="busy">修改密码</button>
      </form>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>
  </div>
</template>
