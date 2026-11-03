<script setup lang="ts">
import {
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRouter } from "vue-router";
import { useSession } from "./session";
import { api } from "./api";
import { useAction } from "./ui";
import type { Profile } from "./types";
import HomeIcon from "./HomeIcon.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
import "./library-motion.css";
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
const profileForm = ref<HTMLFormElement>(),
  passwordForm = ref<HTMLFormElement>();
const errorMessage = ref<HTMLParagraphElement>();
const invalidFields = ref(new Set<string>()),
  savedFeedback = ref(false);
function markInvalid(event: Event) {
  const field = event.target as HTMLInputElement | HTMLTextAreaElement;
  if (field.name) invalidFields.value.add(field.name);
}
function clearInvalid(event: Event) {
  const field = event.target as HTMLInputElement | HTMLTextAreaElement;
  if (field.name) invalidFields.value.delete(field.name);
}
function retireView(element: Element) {
  (element as HTMLElement).inert = true;
}
function restoreView(element: Element) {
  (element as HTMLElement).inert = false;
}
async function security() {
  tab.value = "security";
  error.value = "";
  invalidFields.value.clear();
  savedFeedback.value = false;
  await nextTick();
  passwordForm.value
    ?.querySelector<HTMLInputElement>("[name=currentPassword]")
    ?.focus();
}
function resetForm() {
  Object.assign(form, {
    nickname: s.profile?.nickname || "",
    bio: s.profile?.bio || "",
    homeCity: s.profile?.homeCity || "",
    travelPreferences: s.profile?.travelPreferences || "",
  });
}
async function edit() {
  invalidFields.value.clear();
  savedFeedback.value = false;
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
  invalidFields.value.clear();
  tab.value = "identity";
  await nextTick();
  editButton.value?.focus();
}
const { busy, error, run } = useAction();
watch(error, async (message) => {
  if (!message) return;
  await nextTick();
  if (message !== error.value) return;
  const activeForm =
    tab.value === "profile"
      ? profileForm.value
      : tab.value === "security"
        ? passwordForm.value
        : undefined;
  const name =
    tab.value === "profile"
      ? /昵称/.test(message)
        ? "nickname"
        : /简介/.test(message)
          ? "bio"
          : /城市/.test(message)
            ? "homeCity"
            : /偏好/.test(message)
              ? "travelPreferences"
              : ""
      : /当前密码|原密码/.test(message)
        ? "currentPassword"
        : /新密码/.test(message)
          ? "newPassword"
          : "";
  const field =
    activeForm?.querySelector<HTMLInputElement | HTMLTextAreaElement>(
      "input:invalid, textarea:invalid",
    ) ??
    (name
      ? activeForm?.querySelector<HTMLInputElement | HTMLTextAreaElement>(
          "[name=" + name + "]",
        )
      : undefined);
  if (field) {
    if (field.name) invalidFields.value.add(field.name);
    field.focus();
  } else errorMessage.value?.focus();
});
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
    savedFeedback.value = true;
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
        <h2
          :class="{ 'profile-save-feedback': savedFeedback }"
          @animationend="savedFeedback = false"
        >
          {{ s.profile?.nickname || "旅行者" }}
        </h2>
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
        <button class="traveler-link" :disabled="busy" @click="security">
          账号安全 <HomeIcon name="arrow" />
        </button>
      </aside>
      <section class="traveler-details">
        <p v-if="busy && !s.profile" class="v3-state" role="status">
          正在读取旅行资料…
        </p>
        <div class="profile-motion-stage">
          <Transition
            name="library-profile-view"
            @before-leave="retireView"
            @leave-cancelled="restoreView"
          >
            <div :key="tab" class="profile-motion-body">
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
                  <button
                    class="text-button"
                    :disabled="busy"
                    @click="closeEditor"
                  >
                    返回主页
                  </button>
                </div>
                <form
                  v-if="tab === 'profile'"
                  ref="profileForm"
                  class="traveler-form"
                  @invalid.capture="markInvalid"
                  @input.capture="clearInvalid"
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
                        name="nickname"
                        :aria-invalid="
                          invalidFields.has('nickname') || undefined
                        "
                        required
                        maxlength="50"
                    /></label>
                    <label
                      >个人简介<textarea
                        v-model="form.bio"
                        name="bio"
                        :aria-invalid="invalidFields.has('bio') || undefined"
                        maxlength="500"
                        rows="3"
                        placeholder="用几句话介绍路上的自己"
                      ></textarea
                      ><small
                        >{{ form.bio.length }} / 500 ·
                        简介会显示在公开主页</small
                      ></label
                    >
                    <label
                      >常住城市<input
                        v-model="form.homeCity"
                        name="homeCity"
                        :aria-invalid="
                          invalidFields.has('homeCity') || undefined
                        "
                        maxlength="50"
                        placeholder="如 成都"
                    /></label>
                    <label
                      >旅行偏好<textarea
                        v-model="form.travelPreferences"
                        name="travelPreferences"
                        :aria-invalid="
                          invalidFields.has('travelPreferences') || undefined
                        "
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
                <form
                  v-else
                  ref="passwordForm"
                  class="traveler-form"
                  @invalid.capture="markInvalid"
                  @input.capture="clearInvalid"
                  @submit.prevent="change"
                >
                  <fieldset :disabled="busy">
                    <legend class="sr-only">修改密码</legend>
                    <p>修改密码后，当前账号的所有登录都会失效。</p>
                    <label
                      >当前密码<input
                        v-model="password.currentPassword"
                        name="currentPassword"
                        :aria-invalid="
                          invalidFields.has('currentPassword') || undefined
                        "
                        type="password"
                        autocomplete="current-password"
                        required /></label
                    ><label
                      >新密码<input
                        v-model="password.newPassword"
                        name="newPassword"
                        :aria-invalid="
                          invalidFields.has('newPassword') || undefined
                        "
                        type="password"
                        autocomplete="new-password"
                        minlength="8"
                        maxlength="72"
                        required /></label
                    ><button class="button">修改密码</button>
                  </fieldset>
                </form>
              </template>
            </div>
          </Transition>
        </div>
        <Transition name="motion-fade">
          <p
            v-if="error"
            ref="errorMessage"
            class="form-error profile-motion-error"
            role="alert"
            tabindex="-1"
          >
            {{ error }}
          </p>
        </Transition>
      </section>
    </div>
  </div>
</template>
