<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from "vue";
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
const props = defineProps<{
  tripId: number;
  owner: boolean;
  workspace?: boolean;
  leaveBlocked?: boolean;
}>();
const emit = defineEmits<{ left: []; busy: [value: boolean] }>();
const s = useSession(),
  members = ref<Member[]>([]),
  invites = ref<Invite[]>([]),
  role = ref("EDITOR"),
  code = ref("");
const { busy, error, run } = useAction();
watch(busy, (value) => emit("busy", value), { immediate: true, flush: "sync" });
const loading = ref(false),
  loaded = ref(false),
  loadError = ref(""),
  inviteOpen = ref(false),
  hasLeft = ref(false);
const labels: Record<string, string> = {
  OWNER: "所有者",
  EDITOR: "可编辑",
  VIEWER: "只读",
};
const currentRole = computed(() =>
  props.owner
    ? "所有者"
    : labels[
        members.value.find((member) => member.userId === s.profile?.id)?.role ||
          ""
      ] || "待确认",
);
const blocked = computed(
  () => busy.value || loading.value || !loaded.value || hasLeft.value,
);
let request: AbortController | undefined;
let epoch = 0;
let disposed = false;
async function load() {
  if (disposed || hasLeft.value) return;
  request?.abort();
  const controller = new AbortController(),
    current = ++epoch,
    tripId = props.tripId,
    owner = props.owner;
  request = controller;
  loading.value = true;
  loadError.value = "";
  try {
    const [nextMembers, nextInvites] = await Promise.all([
      api<Member[]>(
        "/membership/trips/" + tripId,
        "GET",
        undefined,
        controller.signal,
      ),
      owner
        ? api<Invite[]>(
            "/membership/trips/" + tripId + "/invites",
            "GET",
            undefined,
            controller.signal,
          )
        : Promise.resolve([] as Invite[]),
    ]);
    if (
      disposed ||
      current !== epoch ||
      tripId !== props.tripId ||
      owner !== props.owner
    )
      return;
    members.value = nextMembers;
    invites.value = nextInvites;
    loaded.value = true;
  } catch (failure) {
    if (disposed || current !== epoch || controller.signal.aborted) return;
    loadError.value =
      failure instanceof Error ? failure.message : "无法读取同行成员，请重试";
    throw failure;
  } finally {
    if (current === epoch) loading.value = false;
  }
}
watch(
  () => [props.tripId, props.owner] as const,
  ([tripId], before) => {
    if (!before || tripId !== before[0]) {
      members.value = [];
      invites.value = [];
      code.value = "";
      inviteOpen.value = false;
      loaded.value = false;
      hasLeft.value = false;
    }
    if (!props.owner) {
      invites.value = [];
      code.value = "";
      inviteOpen.value = false;
    }
    void load().catch(() => undefined);
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  emit("busy", false);
  disposed = true;
  epoch++;
  request?.abort();
});
function invite() {
  if (!props.owner || blocked.value) return;
  const tripId = props.tripId;
  run(async () => {
    const value = await api<{ inviteCode: string }>(
      "/membership/trips/" + tripId + "/invites",
      "POST",
      { role: role.value },
    );
    if (disposed || tripId !== props.tripId || !props.owner) return;
    code.value = value.inviteCode;
    await load();
  }, "邀请口令已生成");
}
function revoke(id: number) {
  if (!props.owner || blocked.value) return;
  const tripId = props.tripId;
  run(async () => {
    await api("/membership/trips/" + tripId + "/invites/" + id, "DELETE");
    if (!disposed && tripId === props.tripId) await load();
  }, "邀请已撤销");
}
async function remove(id: number) {
  if (blocked.value || (!props.owner && id !== s.profile?.id)) return;
  const self = id === s.profile?.id;
  if (self && props.leaveBlocked) return;
  const member = members.value.find((entry) => entry.userId === id);
  if (!member || member.role === "OWNER") return;
  if (!confirm(self ? "退出这份协同行程？" : "移除该成员？")) return;
  const tripId = props.tripId;
  const left = await run(
    async () => {
      await api("/membership/trips/" + tripId + "/members/" + id, "DELETE");
      if (disposed || tripId !== props.tripId) return false;
      if (self) {
        hasLeft.value = true;
        members.value = [];
        invites.value = [];
        code.value = "";
        return true;
      }
      await load();
      return false;
    },
    self ? "已退出协同行程" : "成员已移除",
  );
  // Parent navigation must happen after the mutation busy guard has been released.
  if (left && !disposed && tripId === props.tripId) emit("left");
}
</script>
<template>
  <div
    class="mw-space"
    :class="{ 'mw-workspace': workspace }"
    :aria-busy="loading || busy"
  >
    <header class="mw-overview">
      <div>
        <small>TRAVEL TOGETHER</small>
        <h2>一起出发</h2>
        <p>让同行者参与计划，也为每个人保留清晰的权限边界。</p>
      </div>
      <div v-if="!hasLeft" class="mw-current-role">
        <small>你的当前权限</small><strong>{{ currentRole }}</strong
        ><span v-if="loaded">{{ members.length }} 位同行成员</span>
      </div>
    </header>
    <p v-if="error || loadError" class="mw-error" role="alert">
      {{ error || loadError }}
      <button
        v-if="loadError && !hasLeft"
        type="button"
        :disabled="loading || busy"
        @click="load().catch(() => undefined)"
      >
        重新读取
      </button>
    </p>
    <div v-if="hasLeft" class="mw-panel mw-left-message" role="status">
      <h3>你已退出这份路书</h3>
      <p>此工作区不再展示成员与邀请信息。</p>
      <RouterLink to="/my-trips">返回我的路书</RouterLink>
    </div>
    <div v-else class="mw-columns">
      <section class="mw-panel mw-members" aria-labelledby="members-list-title">
        <header class="mw-section-heading">
          <h3 id="members-list-title">
            同行成员 <span>{{ members.length }}</span>
          </h3>
          <span v-if="loading" role="status">读取中…</span
          ><span v-else>按实际加入记录展示</span>
        </header>
        <div class="mw-member-scroll">
          <TransitionGroup name="mw-list" tag="div" class="mw-member-list">
            <article
              v-for="member in members"
              :key="member.userId"
              class="mw-member-row"
            >
              <div class="mw-avatar" aria-hidden="true">
                {{ [...(member.nickname || "旅")][0] }}
              </div>
              <div class="mw-member-name">
                <h4>
                  {{ member.nickname || "同行者" }}
                  <small v-if="member.userId === s.profile?.id">你</small>
                </h4>
                <span>{{
                  member.role === "OWNER"
                    ? "这份旅程的发起人"
                    : member.role === "EDITOR"
                      ? "可以共同编辑行程与旅行工具"
                      : "可以阅读行程与旅行工具"
                }}</span>
              </div>
              <span class="mw-role" :data-role="member.role">{{
                labels[member.role] || "权限待确认"
              }}</span
              ><button
                v-if="
                  member.role !== 'OWNER' &&
                  (owner || member.userId === s.profile?.id)
                "
                type="button"
                class="mw-text-action"
                :disabled="
                  blocked || (member.userId === s.profile?.id && leaveBlocked)
                "
                @click="remove(member.userId)"
              >
                {{ member.userId === s.profile?.id ? "退出" : "移除" }}
              </button>
            </article>
          </TransitionGroup>
          <p v-if="loaded && !members.length" class="mw-empty">
            暂无可展示的成员记录。
          </p>
        </div>
        <p v-if="leaveBlocked && !owner" class="mw-footnote" role="status">
          退出前请先保存当前修改，并等待正在进行的操作完成。
        </p>
        <p class="mw-footnote">
          协作者保存时仍检查路书版本；发生冲突后，请保留修改并核对最新内容。
        </p>
      </section>
      <div class="mw-side">
        <section
          class="mw-panel mw-invites"
          aria-labelledby="members-invites-title"
        >
          <header class="mw-section-heading">
            <h3 id="members-invites-title">邀请管理</h3>
            <button
              v-if="owner"
              type="button"
              :disabled="blocked"
              :aria-expanded="inviteOpen"
              aria-controls="members-invite-form"
              @click="inviteOpen = !inviteOpen"
            >
              {{ inviteOpen ? "收起邀请" : "邀请同行者" }}
            </button>
          </header>
          <template v-if="owner">
            <Transition name="mw-panel"
              ><form
                v-show="inviteOpen"
                id="members-invite-form"
                class="mw-invite-form"
                @submit.prevent="invite"
              >
                <label
                  >加入后的权限<select v-model="role" :disabled="blocked">
                    <option value="EDITOR">可编辑</option>
                    <option value="VIEWER">只读</option>
                  </select></label
                ><button class="mw-primary" :disabled="blocked">
                  {{ busy ? "处理中…" : "生成邀请口令" }}
                </button>
                <p>口令 24 小时有效。同行者在“我的路书”输入口令即可加入。</p>
              </form></Transition
            >
            <Transition name="mw-panel"
              ><div v-if="code" class="mw-invite-code">
                <small>本次生成的邀请口令</small
                ><output tabindex="0">{{ code }}</output>
                <p>请将口令交给受邀同行者；历史记录不会再次显示完整口令。</p>
              </div></Transition
            >
            <div class="mw-invite-scroll">
              <h4>
                邀请记录 <span>{{ invites.length }}</span>
              </h4>
              <TransitionGroup name="mw-list" tag="div" class="mw-invite-list"
                ><article
                  v-for="invitation in invites"
                  :key="invitation.id"
                  class="mw-invite-row"
                >
                  <div>
                    <strong
                      >{{ labels[invitation.role] || "权限待确认" }}邀请
                      <small>#{{ invitation.id }}</small></strong
                    >
                    <p>到期时间 {{ invitation.expiresAt }}</p>
                    <span>{{
                      invitation.revoked ? "已撤销" : "未撤销，请留意到期时间"
                    }}</span>
                  </div>
                  <button
                    v-if="!invitation.revoked"
                    type="button"
                    :disabled="blocked"
                    @click="revoke(invitation.id)"
                  >
                    撤销
                  </button>
                </article></TransitionGroup
              >
              <p v-if="loaded && !invites.length" class="mw-empty">
                还没有生成过邀请。
              </p>
            </div>
          </template>
          <p v-else class="mw-permission-copy">
            仅路书所有者可以生成或撤销邀请、移除其他成员。你可以查看当前成员及自己的权限。
          </p>
        </section>
        <section
          class="mw-panel mw-access"
          aria-labelledby="members-access-title"
        >
          <h3 id="members-access-title">权限说明</h3>
          <dl>
            <div>
              <dt>所有者</dt>
              <dd>编辑路书，管理成员、邀请、发布与分享。</dd>
            </div>
            <div>
              <dt>可编辑</dt>
              <dd>共同编辑路书、记账与行李，不管理他人的权限。</dd>
            </div>
            <div>
              <dt>只读</dt>
              <dd>阅读路书和旅行工具，可以自行退出协同行程。</dd>
            </div>
          </dl>
        </section>
      </div>
    </div>
  </div>
</template>
<style scoped>
.mw-space {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  color: var(--wb-v5-panel-ink, #163c57);
}
.mw-workspace {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.mw-overview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex: none;
  padding: 19px 23px;
  border: 1px solid var(--wb-v5-edge, #eaf7ff);
  border-radius: 20px;
  background: var(
    --wb-v5-subpanel,
    linear-gradient(130deg, #dceefaec, #c7e0f1df)
  );
  box-shadow: inset 0 1px 0 #ffffff9c;
}
.mw-overview small {
  color: var(--wb-v5-muted, #35566d);
  font-size: 10px;
  letter-spacing: 0.09em;
}
.mw-overview h2 {
  margin: 4px 0 7px;
  font-size: 23px;
  font-weight: 650;
  letter-spacing: 0.035em;
}
.mw-overview p {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--wb-v5-panel-muted, #35566d);
}
.mw-current-role {
  flex: none;
  display: grid;
  gap: 4px;
  padding-left: 22px;
  border-left: 1px solid #749bb438;
}
.mw-current-role strong {
  font-size: 18px;
  font-weight: 600;
}
.mw-current-role span {
  font-size: 12px;
  color: var(--wb-v5-panel-muted, #35566d);
}
.mw-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  gap: 16px;
  min-height: 0;
  flex: 1;
}
.mw-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 20px;
  border: 1px solid var(--wb-v5-edge, #eaf7ff);
  border-radius: 20px;
  background: var(--wb-v5-panel, linear-gradient(135deg, #d5eafaed, #c1dcefed));
  box-shadow: var(--wb-v5-shadow, 0 6px 22px #163c571a);
}
.mw-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 17px;
  flex: none;
}
.mw-section-heading h3,
.mw-access h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
}
.mw-section-heading h3 span {
  display: inline-block;
  margin-left: 6px;
  padding: 2px 7px;
  border-radius: 8px;
  background: var(--wb-v5-input-bg, #edf8ff);
  font-size: 11px;
  vertical-align: middle;
}
.mw-section-heading > span {
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 11px;
}
.mw-member-scroll {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: #7299b866 transparent;
}
.mw-workspace .mw-member-scroll {
  flex: 1;
}
.mw-member-list {
  display: grid;
  gap: 10px;
}
.mw-member-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 15px 11px;
  border: 1px solid var(--wb-v5-edge, #eaf7ff);
  border-radius: 14px;
  background: var(--wb-v5-subpanel, #d2e7f5);
}
.mw-avatar {
  width: 38px;
  height: 38px;
  border: 1px solid #ffffffa8;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: none;
  color: #275c7e;
  background: linear-gradient(140deg, #edf7ffb8, #adcfeb8c);
  font-size: 15px;
}
.mw-member-name {
  min-width: 0;
  flex: 1;
}
.mw-member-name h4 {
  margin: 0 0 5px;
  font-size: 14px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.mw-member-name h4 small {
  margin-left: 3px;
  font-size: 10px;
  font-weight: 400;
  color: var(--wb-v5-panel-muted, #35566d);
}
.mw-member-name > span {
  display: block;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 11px;
  line-height: 1.5;
}
.mw-role {
  padding: 4px 8px;
  border: 1px solid #ffffffb8;
  border-radius: 8px;
  color: #2e6077;
  background: #c5e6ee80;
  flex: none;
  font-size: 11px;
  white-space: nowrap;
}
.mw-role[data-role="OWNER"] {
  color: #9a572c;
  background: #f8e4ca9c;
}
.mw-footnote {
  margin: 16px 0 0;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 11px;
  line-height: 1.7;
  flex: none;
}
.mw-side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}
.mw-invites {
  display: block;
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
}
.mw-invite-scroll {
  min-height: 0;
  flex: none;
  overflow: visible;
}
.mw-access {
  flex: none;
}
.mw-space button {
  min-height: 31px;
  padding: 6px 10px;
  color: var(--wb-v5-panel-ink, #163c57);
  background: var(--wb-v5-control-bg, #d9eaf7);
  border: 1px solid var(--wb-v5-edge, #eaf7ff);
  border-radius: 10px;
  box-shadow: inset 0 1px 0 #ffffff9c;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.mw-space button:hover:not(:disabled) {
  filter: brightness(1.035);
}
.mw-space button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.mw-space button:focus-visible,
.mw-invite-form select:focus-visible,
.mw-invite-code output:focus-visible {
  outline: 2px solid #ad6037;
  outline-offset: 3px;
}
.mw-space .mw-text-action {
  min-height: 27px;
  padding: 4px;
  background: transparent;
  border: 0;
  box-shadow: none;
  font-size: 11px;
}
.mw-space .mw-primary {
  color: #fff;
  background: linear-gradient(135deg, #d86c31, #b94a1b);
  border-color: #e49d70;
  box-shadow: inset 0 1px 0 #ffd3b899;
}
.mw-invite-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
  padding: 12px;
  margin-bottom: 12px;
  border-radius: 12px;
  background: var(--wb-v5-subpanel, #d5e7f5);
  border: 1px solid var(--wb-v5-edge, #eaf7ff);
}
.mw-invite-form label {
  display: grid;
  gap: 6px;
  font-size: 12px;
}
.mw-invite-form select {
  width: 100%;
  min-width: 0;
  min-height: 34px;
  padding: 6px 9px;
  border: 1px solid var(--wb-v5-edge, #eaf7ff);
  border-radius: 9px;
  color: var(--wb-v5-panel-ink, #163c57);
  background: var(--wb-v5-input-bg, #edf7ff);
  font: inherit;
}
.mw-invite-form p,
.mw-invite-code p {
  margin: 0;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 11px;
  line-height: 1.6;
}
.mw-invite-form p {
  grid-column: 1/-1;
}
.mw-invite-code {
  padding: 12px;
  margin-bottom: 13px;
  border-radius: 11px;
  border: 1px solid #8db7d086;
  background: var(--wb-v5-input-bg, #edf7ff);
}
.mw-invite-code small {
  display: block;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 11px;
}
.mw-invite-code output {
  display: block;
  user-select: all;
  overflow-wrap: anywhere;
  padding: 7px 0;
  font:
    12px/1.65 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
}
.mw-invite-scroll h4 {
  margin: 3px 0 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--wb-v5-panel-muted, #35566d);
}
.mw-invite-scroll h4 span {
  margin-left: 4px;
}
.mw-invite-list {
  display: grid;
  gap: 8px;
}
.mw-invite-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--wb-v5-edge, #eaf7ff);
  border-radius: 12px;
  background: var(--wb-v5-subpanel, #d2e7f5);
}
.mw-invite-row strong {
  font-size: 13px;
  font-weight: 600;
}
.mw-invite-row strong small {
  font-weight: 400;
  color: var(--wb-v5-panel-muted, #35566d);
}
.mw-invite-row p {
  margin: 6px 0;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 11px;
  line-height: 1.6;
}
.mw-invite-row div > span {
  font-size: 10px;
  color: var(--wb-v5-muted, #35566d);
}
.mw-access dl {
  display: grid;
  gap: 9px;
  margin: 13px 0 0;
}
.mw-access dl > div {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  gap: 9px;
}
.mw-access dt {
  font-size: 12px;
  font-weight: 600;
}
.mw-access dd {
  margin: 0;
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 11px;
  line-height: 1.6;
}
.mw-permission-copy,
.mw-empty,
.mw-left-message p {
  color: var(--wb-v5-panel-muted, #35566d);
  font-size: 13px;
  line-height: 1.8;
}
.mw-empty {
  text-align: center;
  margin: 20px 0;
}
.mw-error {
  margin: 0;
  color: #9c3433;
  font-size: 12px;
  line-height: 1.6;
}
.mw-panel-enter-active,
.mw-panel-leave-active,
.mw-list-enter-active,
.mw-list-leave-active,
.mw-list-move {
  transition:
    opacity var(--motion-base, 280ms),
    transform var(--motion-base, 280ms)
      var(--motion-ease, cubic-bezier(0.22, 1, 0.36, 1));
}
.mw-panel-enter-from,
.mw-panel-leave-to {
  opacity: 0;
  transform: translateY(-7px);
}
.mw-list-enter-from,
.mw-list-leave-to {
  opacity: 0;
  transform: translateY(7px);
}
@media (max-width: 1000px) {
  .mw-columns {
    grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
    gap: 12px;
  }
  .mw-panel {
    padding: 15px;
  }
  .mw-member-row {
    gap: 8px;
    padding: 12px 9px;
  }
  .mw-avatar {
    width: 31px;
    height: 31px;
  }
}
@media (max-width: 760px) {
  .mw-workspace {
    overflow-y: auto;
  }
  .mw-columns {
    display: flex;
    flex-direction: column;
    flex: none;
  }
  .mw-workspace .mw-member-scroll,
  .mw-workspace .mw-invite-scroll {
    overflow: visible;
    flex: none;
  }
  .mw-panel,
  .mw-invites,
  .mw-side {
    flex: none;
  }
  .mw-overview {
    padding: 16px;
    gap: 12px;
  }
  .mw-overview h2 {
    font-size: 21px;
  }
  .mw-overview p {
    font-size: 11px;
  }
  .mw-current-role {
    padding-left: 12px;
  }
  .mw-current-role strong {
    font-size: 15px;
  }
  .mw-invite-form {
    grid-template-columns: 1fr;
  }
  .mw-member-name > span {
    font-size: 10px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .mw-panel-enter-active,
  .mw-panel-leave-active,
  .mw-list-enter-active,
  .mw-list-leave-active,
  .mw-list-move {
    transition: none;
  }
}
</style>
