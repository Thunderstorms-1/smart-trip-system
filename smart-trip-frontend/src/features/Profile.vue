<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useSession } from "./session";
import { api } from "./api";
import { useAction } from "./ui";
import type { Profile } from "./types";
import HomeIcon from "./HomeIcon.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
const s = useSession(),
  router = useRouter(),
  form = reactive({
    nickname: "",
    bio: "",
    homeCity: "",
    travelPreferences: "",
  }),
  password = reactive({ currentPassword: "", newPassword: "" }),
  tab = ref("identity");
const editButton = ref<HTMLButtonElement>();
const nicknameInput = ref<HTMLInputElement>();
function resetForm() {
  Object.assign(form, {
    nickname: s.profile?.nickname || "",
    bio: s.profile?.bio || "",
    homeCity: s.profile?.homeCity || "",
    travelPreferences: s.profile?.travelPreferences || "",
  });
}
async function edit() {
  resetForm();
  tab.value = "profile";
  await nextTick();
  nicknameInput.value?.focus();
}
async function closeEditor() {
  if (busy.value) return;
  resetForm();
  password.currentPassword = "";
  password.newPassword = "";
  error.value = "";
  tab.value = "identity";
  await nextTick();
  editButton.value?.focus();
}
const { busy, error, run } = useAction();
function escapeEditor(event: KeyboardEvent) {
  if (event.key === "Escape" && tab.value !== "identity") void closeEditor();
}
onMounted(() => window.addEventListener("keydown", escapeEditor));
onBeforeUnmount(() => window.removeEventListener("keydown", escapeEditor));
onMounted(() =>
  run(async () => {
    await s.refresh();
    resetForm();
  }),
);
async function save() {
  const saved = await run(async () => {
    s.profile = await api<Profile>("/user/profile", "PUT", form);
    resetForm();
    return true;
  }, "资料已更新");
  if (saved) {
    tab.value = "identity";
    await nextTick();
    editButton.value?.focus();
  }
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
  <div class="v3-traveler v3-content">
    <section class="traveler-banner">
      <JourneyBackdrop />
      <div>
        <h1>我的旅行者主页</h1>
        <p>让下一次旅行，更懂你的喜好。</p>
      </div>
    </section>
    <div class="traveler-layout">
      <aside class="traveler-identity">
        <img
          v-if="s.profile?.avatar"
          class="traveler-avatar"
          :src="s.profile.avatar"
          alt="当前头像"
        /><span v-else class="traveler-avatar traveler-letter">{{
          s.profile?.nickname?.slice(0, 1) || "游"
        }}</span>
        <h2>{{ s.profile?.nickname || "旅行者" }}</h2>
        <p class="traveler-city">
          <HomeIcon name="pin" />{{ s.profile?.homeCity || "常住城市待填写" }}
        </p>
        <button ref="editButton" class="button" :disabled="busy" @click="edit">
          编辑旅行资料
        </button>
        <RouterLink to="/my-trips" class="traveler-link"
          >打开我的旅行库 <HomeIcon name="arrow"
        /></RouterLink>
        <RouterLink
          v-if="s.profile"
          :to="'/authors/' + s.profile.id"
          class="traveler-link"
          >查看公开主页 <HomeIcon name="arrow"
        /></RouterLink>
        <button
          class="traveler-link"
          :disabled="busy"
          @click="
            tab = 'security';
            error = '';
          "
        >
          账号安全 <HomeIcon name="arrow" />
        </button>
      </aside>
      <section class="traveler-details">
        <p v-if="busy && !s.profile" class="v3-state" role="status">
          正在读取旅行资料…
        </p>
        <template v-if="tab === 'identity'">
          <div class="v3-section-heading">
            <h2>路上的自己</h2>
            <HomeIcon name="user" />
          </div>
          <p class="traveler-bio">
            {{
              s.profile?.bio ||
              "还没有填写个人简介。写下自己的旅行故事，让同行的人认识你。"
            }}
          </p>
          <div class="traveler-preferences">
            <h3>我的旅行偏好</h3>
            <p>
              {{
                s.profile?.travelPreferences ||
                "还没有填写偏好。编辑资料，记录喜欢的旅行节奏与体验。"
              }}
            </p>
            <small>仅自己可见</small>
          </div>
          <div class="traveler-next">
            <HomeIcon name="route" />
            <div>
              <h3>下一段旅程，由你开启</h3>
              <p>打开旅行库，继续完善已保存的计划。</p>
            </div>
            <RouterLink to="/my-trips" class="button secondary"
              >查看路书</RouterLink
            >
          </div>
        </template>
        <template v-else>
          <div class="v3-section-heading">
            <h2>{{ tab === "profile" ? "编辑旅行资料" : "账号安全" }}</h2>
            <button class="text-button" :disabled="busy" @click="closeEditor">
              返回主页
            </button>
          </div>
          <form
            v-if="tab === 'profile'"
            class="traveler-form"
            @submit.prevent="save"
          >
            <fieldset :disabled="busy">
              <legend class="sr-only">旅行资料</legend>
              <div class="traveler-upload">
                <label class="button secondary"
                  >更换头像<input
                    type="file"
                    accept="image/png,image/jpeg"
                    class="sr-only"
                    @change="upload" /></label
                ><small>PNG / JPEG，最大 2MB</small>
              </div>
              <label
                >昵称<input
                  ref="nicknameInput"
                  v-model="form.nickname"
                  required
                  maxlength="50"
              /></label>
              <label
                >个人简介<textarea
                  v-model="form.bio"
                  maxlength="500"
                  rows="3"
                  placeholder="用几句话介绍路上的自己"
                ></textarea
                ><small
                  >{{ form.bio.length }} / 500 · 简介会显示在公开主页</small
                ></label
              >
              <label
                >常住城市<input
                  v-model="form.homeCity"
                  maxlength="50"
                  placeholder="如 成都"
              /></label>
              <label
                >旅行偏好<textarea
                  v-model="form.travelPreferences"
                  maxlength="1000"
                  rows="3"
                  placeholder="例如喜欢博物馆、少走回头路、每天留出休息时间"
                ></textarea
                ><small>偏好仅在自己的个人中心显示</small></label
              >
              <div class="traveler-form-actions">
                <button class="button">
                  {{ busy ? "保存中…" : "保存资料" }}</button
                ><button
                  type="button"
                  class="button secondary"
                  @click="closeEditor"
                >
                  取消编辑
                </button>
              </div>
            </fieldset>
          </form>
          <form v-else class="traveler-form" @submit.prevent="change">
            <fieldset :disabled="busy">
              <legend class="sr-only">修改密码</legend>
              <p>修改密码后，当前账号的所有登录都会失效。</p>
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
              ><button class="button">修改密码</button>
            </fieldset>
          </form>
        </template>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </section>
    </div>
  </div>
</template>
