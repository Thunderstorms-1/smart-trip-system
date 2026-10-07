<script setup lang="ts">
import { computed, ref, reactive, onBeforeUnmount, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, requestKey } from "./api";
import { followTask } from "./task-stream";
import { useSession } from "./session";
import { useAction } from "./ui";
import type { Trip, Task } from "./types";
import HomeIcon from "./HomeIcon.vue";
import JourneyBackdrop from "./JourneyBackdrop.vue";
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
const step = ref(1);
const builderForm = ref<HTMLFormElement>();
const destinationInput = ref<HTMLInputElement>();
const daysInput = ref<HTMLInputElement>();
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
  clearTimeout(timer);
  stream.abort();
});
onMounted(async () => {
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
      const prompt =
        "请规划" +
        JSON.stringify(form) +
        "。旅行偏好：" +
        (s.profile?.travelPreferences || "无");
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
  <div class="v3-builder v3-content">
    <section class="v3-page-heading">
      <div>
        <h1>构建一段旅程</h1>
        <p>从一个目的地开始，把时间留给沿途。</p>
      </div>
      <RouterLink to="/my-trips" class="text-button"
        >打开旅行库 <HomeIcon name="arrow"
      /></RouterLink>
    </section>
    <div class="builder-layout">
      <aside class="builder-scene" aria-label="当前旅程构想">
        <JourneyBackdrop />
        <div class="builder-scene-copy">
          <HomeIcon name="pin" />
          <h2>{{ form.destination || "下一站，由你决定" }}</h2>
          <p>{{ form.title || "给心动的地方，一段属于自己的时间。" }}</p>
          <div class="builder-intentions">
            <span>{{ form.totalDays }} 天旅行</span
            ><span>{{ form.tripStyle }}</span
            ><span>{{ form.travelGroup }}</span>
          </div>
          <p class="builder-scene-note">
            {{ form.startDate || "出发日期待定" }}
          </p>
          <small>内容来自当前输入，尚未创建路书。</small>
        </div>
      </aside>
      <div class="builder-workspace">
        <div class="v3-tabs" role="group" aria-label="创建方式">
          <button
            :class="{ active: mode === 'manual' }"
            :aria-pressed="mode === 'manual'"
            :disabled="creating"
            @click="mode = 'manual'"
          >
            手动创建路书</button
          ><button
            :class="{ active: mode === 'ai' }"
            :aria-pressed="mode === 'ai'"
            :disabled="!s.capabilities.ai || creating"
            @click="mode = 'ai'"
          >
            AI 生成草稿
          </button>
        </div>
        <p v-if="!s.capabilities.ai" class="builder-notice">
          {{
            aiUnavailableReason()
          }}你可以先手动创建路书，填写地点和每天的安排。
        </p>
        <nav class="builder-steps" aria-label="旅程创建步骤">
          <button
            v-for="(label, i) in ['选择目的地', '安排旅行节奏', '确认并创建']"
            :key="label"
            :aria-current="step === i + 1 ? 'step' : undefined"
            :disabled="i + 1 > step || creating"
            @click="step = i + 1"
          >
            <span>{{ i + 1 }}</span
            >{{ label }}
          </button>
        </nav>
        <form
          ref="builderForm"
          class="builder-form"
          novalidate
          @submit.prevent="advance"
        >
          <fieldset :disabled="creating">
            <legend class="sr-only">构建旅行</legend>
            <section v-show="step === 1" class="builder-stage">
              <h2>想去哪里？</h2>
              <p>先确定目的地，再给这次旅行取个名字。</p>
              <label
                >目的地<input
                  ref="destinationInput"
                  v-model="form.destination"
                  maxlength="100"
                  required
                  placeholder="例如 成都" /></label
              ><label
                >路书标题<input
                  v-model="form.title"
                  maxlength="100"
                  placeholder="给这次旅行取个名字"
              /></label>
            </section>
            <section v-show="step === 2" class="builder-stage">
              <h2>按自己的节奏出发</h2>
              <p>决定停留的时间，以及和谁一起走。</p>
              <div class="builder-fields">
                <label
                  >出发日期<input v-model="form.startDate" type="date" /></label
                ><label
                  >旅行天数<input
                    ref="daysInput"
                    v-model.number="form.totalDays"
                    type="number"
                    min="1"
                    max="30"
                    required /></label
                ><label
                  >旅行风格<select v-model="form.tripStyle">
                    <option>城市漫步</option>
                    <option>自然山野</option>
                    <option>美食探索</option>
                    <option>亲子休闲</option>
                    <option>文化博物馆</option>
                  </select></label
                ><label
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
            <section v-show="step === 3" class="builder-stage">
              <h2>为旅程留一点期待</h2>
              <p>
                {{
                  mode === "manual"
                    ? "创建私密路书后，再逐天填写地点、时间和费用。"
                    : "根据这些输入生成草稿，完成后进入工作台继续完善。"
                }}
              </p>
              <label
                >这次旅行的小期待<textarea
                  v-model="form.requirements"
                  :maxlength="mode === 'manual' ? 500 : 1500"
                  rows="4"
                  placeholder="想去的地点、预算、节奏，或需要避开的安排"
                ></textarea>
              </label>
              <dl class="builder-review">
                <div>
                  <dt>目的地</dt>
                  <dd>{{ form.destination }}</dd>
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
          <div class="builder-controls">
            <button
              v-if="step > 1"
              type="button"
              class="button secondary"
              :disabled="creating"
              @click="step--"
            >
              上一步</button
            ><button class="button" :disabled="creating">
              {{
                creating
                  ? "请稍候…"
                  : step < 3
                    ? "继续"
                    : mode === "manual"
                      ? "创建私密路书"
                      : "生成 AI 草稿"
              }}
              <HomeIcon name="arrow" />
            </button>
          </div>
        </form>
        <p class="builder-privacy">
          新建路书默认私密，仅你和受邀成员可查看。费用为预估，实际花费由记账记录。
        </p>
      </div>
    </div>
  </div>
</template>
