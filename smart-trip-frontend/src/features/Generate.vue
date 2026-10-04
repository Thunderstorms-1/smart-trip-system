<script setup lang="ts">
import { ref, reactive, onBeforeUnmount, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, requestKey } from "./api";
import { followTask } from "./task-stream";
import { useSession } from "./session";
import { useAction } from "./ui";
import type { Trip, Task } from "./types";
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
let timer: ReturnType<typeof setTimeout>;
const stream = new AbortController();
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
  <section class="page-title">
    <p class="eyebrow">MAKE IT YOUR OWN</p>
    <h1>从心动，到出发</h1>
    <p>告诉我们想去哪里，剩下的细节，慢慢完善。</p>
  </section>
  <div class="tabs">
    <button :class="{ active: mode === 'manual' }" @click="mode = 'manual'">
      手动创建路书</button
    ><button
      :class="{ active: mode === 'ai' }"
      :disabled="!s.capabilities.ai"
      @click="mode = 'ai'"
    >
      AI 生成草稿
    </button>
  </div>
  <div v-if="!s.capabilities.ai" class="notice">
    AI 服务暂未启用。你可以先手动创建路书，填写地点和每天的安排。
  </div>
  <div class="generate-layout">
    <form class="paper generate-form" @submit.prevent="submit">
      <div class="form-grid">
        <label class="wide"
          >目的地<input
            v-model="form.destination"
            maxlength="100"
            required
            placeholder="例如 成都" /></label
        ><label class="wide"
          >路书标题<input
            v-model="form.title"
            maxlength="100"
            placeholder="给这次旅行取个名字" /></label
        ><label>出发日期<input v-model="form.startDate" type="date" /></label
        ><label
          >旅行天数<input
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
        ><label class="wide"
          >这次旅行的小期待<textarea
            v-model="form.requirements"
            :maxlength="mode === 'manual' ? 500 : 1500"
            rows="4"
            placeholder="想去的地点、预算、节奏，或需要避开的安排"
          ></textarea>
        </label>
      </div>
      <p v-if="error" class="form-error">{{ error }}</p>
      <div v-if="task" class="notice">
        <template v-if="['QUEUED', 'RUNNING'].includes(task.status)"
          ><span class="status-pulse"></span
          >正在生成路书，刷新页面也可以继续查看。</template
        ><template v-else>{{ task.errorMessage || "任务已完成" }}</template>
      </div>
      <button
        class="button full"
        :disabled="
          busy || Boolean(task && ['QUEUED', 'RUNNING'].includes(task.status))
        "
      >
        {{
          busy
            ? "请稍候…"
            : mode === "manual"
              ? "创建私密路书 →"
              : "生成 AI 草稿 →"
        }}
      </button>
    </form>
    <aside class="paper generate-side">
      <p class="eyebrow">A PLAN, WITH ROOM TO WANDER</p>
      <h3>旅程，由你做主</h3>
      <ol>
        <li>新建的路书默认私密，只有你和受邀成员可以查看。</li>
        <li>行程可逐天编辑，地点、时间和费用都能调整。</li>
        <li>费用始终标记为预估，真实花费由记账记录。</li>
        <li>AI调整会先展示预览，确认后才保存，并保留旧版本。</li>
      </ol>
    </aside>
  </div>
</template>
