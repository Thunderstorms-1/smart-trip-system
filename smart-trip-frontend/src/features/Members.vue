<script setup lang="ts">
import { ref, onMounted } from "vue";
import { api } from "./api";
import { useAction } from "./ui";
import { useSession } from "./session";
interface Member {
  userId: number;
  nickname: string;
  role: string;
}
interface Invite {
  id: number;
  role: string;
  expiresAt: string;
  revoked: number;
}
const props = defineProps<{ tripId: number; owner: boolean }>(),
  s = useSession(),
  members = ref<Member[]>([]),
  invites = ref<Invite[]>([]),
  role = ref("EDITOR"),
  code = ref(""),
  { busy, error, run } = useAction();
async function load() {
  members.value = await api<Member[]>("/membership/trips/" + props.tripId);
  if (props.owner)
    invites.value = await api<Invite[]>(
      "/membership/trips/" + props.tripId + "/invites",
    );
}
onMounted(() => run(load));
function invite() {
  run(async () => {
    const v = await api<{ inviteCode: string }>(
      "/membership/trips/" + props.tripId + "/invites",
      "POST",
      { role: role.value },
    );
    code.value = v.inviteCode;
    await load();
  });
}
function revoke(id: number) {
  run(async () => {
    await api("/membership/trips/" + props.tripId + "/invites/" + id, "DELETE");
    await load();
  });
}
function remove(id: number) {
  if (!confirm(id === s.profile?.id ? "退出这份协同行程？" : "移除该成员？"))
    return;
  run(async () => {
    await api("/membership/trips/" + props.tripId + "/members/" + id, "DELETE");
    await load();
  });
}
</script>
<template>
  <div class="paper editor-panel">
    <h2>一起出发</h2>
    <p class="muted">
      协作者可以编辑路书；只读成员可以查看。保存时会检查版本冲突。
    </p>
    <div v-for="m in members" :key="m.userId" class="packing-row">
      <span>{{ m.nickname }}</span
      ><span class="pill">{{
        m.role === "OWNER" ? "发起人" : m.role === "EDITOR" ? "可编辑" : "只读"
      }}</span
      ><button
        v-if="m.role !== 'OWNER' && (owner || m.userId === s.profile?.id)"
        class="text-button"
        :disabled="busy"
        @click="remove(m.userId)"
      >
        {{ m.userId === s.profile?.id ? "退出" : "移除" }}
      </button>
    </div>
    <template v-if="owner"
      ><h3 style="margin-top: 25px">邀请同行者</h3>
      <div class="button-row">
        <select v-model="role" style="width: auto">
          <option value="EDITOR">可编辑</option>
          <option value="VIEWER">只读</option></select
        ><button class="button small" :disabled="busy" @click="invite">
          生成邀请口令
        </button>
      </div>
      <p v-if="code" class="share-url">{{ code }}</p>
      <p class="muted">口令24小时有效。同行者在“我的路书”输入口令即可加入。</p>
      <div v-for="i in invites" :key="i.id" class="packing-row">
        <small>邀请 #{{ i.id }} · {{ i.role }} · {{ i.expiresAt }}</small
        ><button
          v-if="!i.revoked"
          class="text-button"
          :disabled="busy"
          @click="revoke(i.id)"
        >
          撤销</button
        ><span v-else class="muted">已撤销</span>
      </div></template
    >
    <p v-if="error" class="form-error">{{ error }}</p>
  </div>
</template>
