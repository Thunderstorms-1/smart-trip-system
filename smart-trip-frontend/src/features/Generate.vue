<script setup lang="ts">
import {
  computed,
  ref,
  reactive,
  onBeforeUnmount,
  onMounted,
  nextTick,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, requestKey } from "./api";
import { followTask } from "./task-stream";
import { useSession } from "./session";
import { useAction } from "./ui";
import type { Trip, Task } from "./types";
import HomeIcon from "./HomeIcon.vue";
import "./generate-v3.css";
const route = useRoute(),
  router = useRouter(),
  s = useSession(),
  mode = ref("manual"),
  form = reactive({
    title: "",
    destination: "",
    startDate: "",
    totalDays: 3,
    tripStyle: "城市漫步",
    travelGroup: "朋友同行",
    requirements: "",
  }),
  task = ref<Task>(),
  { busy, error, run } = useAction();
const imageFailed = ref(false);
const avatarFailed = ref(false);
const entranceKey = ref(0);
function replayEntrance(event: PageTransitionEvent) {
  if (
    event.persisted &&
    !matchMedia("(prefers-reduced-motion: reduce)").matches
  )
    entranceKey.value++;
}
function logout() {
  s.logout();
  router.push("/");
}
const step = ref(1);
const builderForm = ref<HTMLFormElement>();
const destinationInput = ref<HTMLInputElement>();
const daysInput = ref<HTMLInputElement>();
watch(step, async () => {
  await nextTick();
  const heading = builderForm.value?.querySelector<HTMLElement>(
    '[data-stage="' + step.value + '"] h2',
  );
  heading?.focus({ preventScroll: true });
  heading?.scrollIntoView({ block: "nearest", behavior: "instant" });
});
const creating = computed(
  () =>
    busy.value ||
    Boolean(task.value && ["QUEUED", "RUNNING"].includes(task.value.status)),
);
function advance() {
  if (creating.value) return;
  if (step.value === 1 && !destinationInput.value?.reportValidity()) return;
  if (step.value === 2 && !daysInput.value?.reportValidity()) return;
  if (step.value < 3) step.value++;
  else {
    if (!destinationInput.value?.checkValidity()) {
      step.value = 1;
      return;
    }
    if (!daysInput.value?.checkValidity()) {
      step.value = 2;
      return;
    }
    if (builderForm.value?.reportValidity()) submit();
  }
}
let timer: ReturnType<typeof setTimeout>;
const stream = new AbortController();
function aiUnavailableReason() {
  switch (s.capabilities.status?.ai) {
    case "QUOTA_UNVERIFIED":
      return "AI 免费额度或模型权限尚未确认。确认后再启用，避免意外计费。";
    case "KEY_MISSING":
      return "AI Key 尚未配置。";
    case "DISABLED":
      return "AI 服务当前处于关闭状态。";
    default:
      return "AI 服务暂未启用。";
  }
}
onBeforeUnmount(() => {
  window.removeEventListener("pageshow", replayEntrance);
  clearTimeout(timer);
  stream.abort();
});
onMounted(async () => {
  window.addEventListener("pageshow", replayEntrance);
  await s.loadCapabilities().catch(() => {});
  if (route.query.mode !== "manual" && s.capabilities.ai) mode.value = "ai";
  const pending = localStorage.getItem("SMARTTRIP_TASK");
  if (pending) poll(pending);
});
async function poll(id: string) {
  try {
    task.value = await api<Task>("/ai/tasks/" + id);
    if (task.value.status === "SUCCEEDED") {
      localStorage.removeItem("SMARTTRIP_TASK");
      router.push("/trip/edit/" + task.value.tripId);
    } else if (["QUEUED", "RUNNING"].includes(task.value.status))
      timer = setTimeout(() => poll(id), 1500);
    else localStorage.removeItem("SMARTTRIP_TASK");
  } catch (e) {
    localStorage.removeItem("SMARTTRIP_TASK");
  }
}
function submit() {
  run(async () => {
    if (mode.value === "manual") {
      const trip: Trip = {
        title: form.title || form.destination + "的旅行",
        destination: form.destination,
        startDate: form.startDate || undefined,
        tripStyle: form.tripStyle,
        travelGroup: form.travelGroup,
        summary: form.requirements,
        days: Array.from({ length: form.totalDays }, (_, i) => ({
          dayTheme: "第" + (i + 1) + "天 · 自由探索",
          items: [],
        })),
      };
      const t = await api<Trip>("/workbench", "POST", trip);
      router.push("/trip/edit/" + t.id);
    } else {
      const prompt = "请规划" + JSON.stringify(form);
      task.value = await api<Task>("/ai/tasks", "POST", {
        requestKey: requestKey(),
        type: "GENERATE",
        prompt,
      });
      localStorage.setItem("SMARTTRIP_TASK", task.value.id);
      const id = task.value.id;
      followTask(
        id,
        (t) => {
          task.value = t;
          if (t.status === "SUCCEEDED") {
            localStorage.removeItem("SMARTTRIP_TASK");
            router.push("/trip/edit/" + t.tripId);
          }
        },
        stream.signal,
      ).catch(() => {
        if (!stream.signal.aborted) poll(id);
      });
    }
  });
}
</script>
<template>
  <div
    class="generate-v3 planning-scene"
    :class="{ 'image-failed': imageFailed }"
  >
    <a class="skip-link" href="#planner">跳到旅行规划</a>
    <div class="landscape" aria-hidden="true">
      <img
        v-if="!imageFailed"
        src="/design-v3/generate-scenery.png"
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
        ><RouterLink to="/my-trips">我的路书</RouterLink>
      </nav>
      <div v-if="s.token" class="planning-account">
        <RouterLink
          to="/profile"
          aria-label="个人中心"
          :title="s.profile?.nickname || '个人中心'"
        >
          <img
            v-if="s.profile?.avatar && !avatarFailed"
            :src="s.profile.avatar"
            alt="我的头像"
            @error="avatarFailed = true"
          />
          <span v-else class="account-letter">{{
            s.profile?.nickname?.slice(0, 1) || "我"
          }}</span>
          <span class="planning-account-name">{{
            s.profile?.nickname || "个人中心"
          }}</span>
        </RouterLink>
        <button type="button" @click="logout">退出</button>
      </div>
      <RouterLink
        v-else
        class="login-link"
        :to="{ path: '/login', query: { redirect: route.fullPath } }"
        >登录 / 注册</RouterLink
      >
    </header>
    <div class="planning-main">
      <div :key="entranceKey" class="planning-invitation">
        <svg
          class="paper-plane"
          viewBox="0 0 150 100"
          fill="none"
          aria-hidden="true"
        >
          <path
            class="plane-trail"
            d="M3 90c55-2 64-34 45-32-20 2-2 24 20 7l33-30"
          />
          <path
            d="m87 25 58-19-24 48-10-24-24-5Zm24 5L145 6m-34 24-9 17 2-20"
          />
        </svg>
        <h1>
          <span>从一个想法，</span
          ><span
            >到一段难忘的<em
              >旅程<svg
                viewBox="0 0 190 24"
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                <path d="M3 20C57 2 126 3 185 4" /></svg></em
            >。</span
          >
        </h1>
        <p>
          告诉我们你想去的地方，<br />剩下的，交给
          <span lang="en">SmartTrip</span>。
        </p>
      </div>
      <section
        class="planner-panel"
        id="planner"
        aria-label="构建一段旅程"
        tabindex="-1"
      >
        <div class="creation-modes" role="group" aria-label="创建方式">
          <button
            type="button"
            :aria-pressed="mode === 'manual'"
            :disabled="creating"
            @click="mode = 'manual'"
          >
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path
                d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14Zm0 0 1-6m-1 6 5-1M3 4h4M5 2v4"
              /></svg
            >手动创建路书
          </button>
          <button
            type="button"
            :aria-pressed="mode === 'ai'"
            :disabled="!s.capabilities.ai || creating"
            :aria-describedby="
              !s.capabilities.ai ? 'ai-unavailable' : undefined
            "
            @click="mode = 'ai'"
          >
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
              <path
                d="m12 3 2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3ZM20 2v4m-2-2h4"
              /></svg
            >AI 生成草稿
          </button>
        </div>
        <p class="planner-intro">
          从一个目的地开始，填写旅行偏好，把想法慢慢变成行程。
        </p>
        <p v-if="!s.capabilities.ai" class="builder-notice" id="ai-unavailable">
          {{
            aiUnavailableReason()
          }}你可以先手动创建路书，填写地点和每天的安排。
        </p>
        <nav class="planning-steps" aria-label="旅程创建步骤">
          <template
            v-for="(label, i) in ['选择目的地', '安排旅行节奏', '确认并创建']"
            :key="label"
          >
            <i v-if="i" aria-hidden="true">›</i>
            <button
              type="button"
              :aria-current="step === i + 1 ? 'step' : undefined"
              :class="{ complete: i + 1 < step }"
              :disabled="i + 1 > step || creating"
              @click="step = i + 1"
            >
              <span>{{ i + 1 }}</span
              ><b>{{ label }}</b>
            </button>
          </template>
        </nav>
        <form
          ref="builderForm"
          class="builder-form"
          id="planning-form"
          :aria-busy="creating"
          novalidate
          @submit.prevent="advance"
        >
          <fieldset :disabled="creating">
            <legend class="sr-only">构建旅行</legend>
            <section v-show="step === 1" class="planning-stage" data-stage="1">
              <h2 tabindex="-1"><HomeIcon name="pin" />想去哪里？</h2>
              <p class="stage-description">
                先确定目的地，再给这次旅行取个名字吧。
              </p>
              <div class="stage-fields">
                <label
                  >目的地<span class="input-wrap"
                    ><HomeIcon name="pin" /><input
                      ref="destinationInput"
                      v-model="form.destination"
                      maxlength="100"
                      required
                      placeholder="例如 成都、东京、京都…" /></span
                ></label>
                <label
                  >路书标题<span class="input-wrap"
                    ><svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
                      <path
                        d="M14 3H5v18h14V8Zm0 0v5h5M8 12h8m-8 4h8M8 7h2"
                      /></svg
                    ><input
                      v-model="form.title"
                      maxlength="100"
                      placeholder="给这次旅行取个名字" /></span
                ></label>
              </div>
            </section>
            <section v-show="step === 2" class="planning-stage" data-stage="2">
              <h2 tabindex="-1">按自己的节奏出发</h2>
              <p class="stage-description">决定停留的时间，以及和谁一起走。</p>
              <div class="stage-fields pace-fields">
                <label
                  >出发日期<input v-model="form.startDate" type="date"
                /></label>
                <label
                  >旅行天数<input
                    ref="daysInput"
                    v-model.number="form.totalDays"
                    type="number"
                    min="1"
                    max="30"
                    required
                /></label>
                <label
                  >旅行风格<select v-model="form.tripStyle">
                    <option>城市漫步</option>
                    <option>自然山野</option>
                    <option>美食探索</option>
                    <option>亲子休闲</option>
                    <option>文化博物馆</option>
                  </select></label
                >
                <label
                  >同行人群<select v-model="form.travelGroup">
                    <option>朋友同行</option>
                    <option>独自旅行</option>
                    <option>情侣出游</option>
                    <option>家庭亲子</option>
                    <option>带长辈出行</option>
                  </select></label
                >
              </div>
            </section>
            <section v-show="step === 3" class="planning-stage" data-stage="3">
              <h2 tabindex="-1">为旅程留一点期待</h2>
              <p class="stage-description">
                {{
                  mode === "manual"
                    ? "创建私密路书后，再逐天填写地点、时间和费用。"
                    : "根据这些输入生成草稿，完成后进入工作台继续完善。"
                }}
              </p>
              <div class="stage-fields">
                <label
                  >这次旅行的小期待<textarea
                    v-model="form.requirements"
                    :maxlength="mode === 'manual' ? 500 : 1500"
                    rows="4"
                    placeholder="想去的地点、预算、节奏，或需要避开的安排"
                  ></textarea>
                </label>
              </div>
              <dl class="builder-review planning-review">
                <div>
                  <dt>目的地</dt>
                  <dd>{{ form.destination }}</dd>
                </div>
                <div>
                  <dt>路书标题</dt>
                  <dd>{{ form.title || form.destination + "的旅行" }}</dd>
                </div>
                <div>
                  <dt>出发日期</dt>
                  <dd>{{ form.startDate || "日期待定" }}</dd>
                </div>
                <div>
                  <dt>旅行安排</dt>
                  <dd>
                    {{ form.totalDays }} 天 · {{ form.tripStyle }} ·
                    {{ form.travelGroup }}
                  </dd>
                </div>
              </dl>
            </section>
          </fieldset>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <div v-if="task" class="builder-task" role="status">
            <HomeIcon name="clock" />
            <div>
              <strong>{{
                task.status === "QUEUED"
                  ? "任务已排队"
                  : task.status === "RUNNING"
                    ? "正在构建旅行草稿"
                    : task.status === "SUCCEEDED"
                      ? "草稿已完成"
                      : "任务已结束"
              }}</strong>
              <p>
                {{
                  ["QUEUED", "RUNNING"].includes(task.status)
                    ? "正在生成路书，刷新页面也可以继续查看。"
                    : task.errorMessage || "任务已完成"
                }}
              </p>
            </div>
          </div>
          <div class="builder-controls planning-controls">
            <button
              v-if="step > 1"
              type="button"
              class="back-button"
              :disabled="creating"
              @click="step--"
            >
              上一步
            </button>
            <button type="submit" class="continue-button" :disabled="creating">
              {{
                creating
                  ? "请稍候…"
                  : step < 3
                    ? "继续"
                    : mode === "manual"
                      ? "创建私密路书"
                      : "生成 AI 草稿"
              }}<HomeIcon name="arrow" />
            </button>
          </div>
        </form>
        <p class="privacy-note">
          <svg class="icon" viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M8 17c0-4-3-4-3-9a7 7 0 0 1 14 0c0 5-3 5-3 9ZM8 20h8m-7 3h6M1 9h1m20 0h1M3 2l2 2m14 0 2-2"
            /></svg
          ><span
            >新建路书默认私密，仅你和受邀成员可查看。费用为预估，实际花费由记账记录。</span
          >
        </p>
      </section>
    </div>
    <footer class="planning-footer">
      <span>把一个想法，变成自己的旅程。</span
      ><RouterLink to="/my-trips"
        >打开旅行库<HomeIcon name="arrow"
      /></RouterLink>
    </footer>
  </div>
</template>
