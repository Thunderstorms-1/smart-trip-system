<template>
  <div class="modal-mask" v-if="visible">
    <div class="modal-box">
      <div class="modal-header">
        <div class="header-left">
          <h3>👥 行程同行好友与协同成员</h3>
          <span class="member-count-badge">共 {{ members.length }} 人</span>
        </div>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <!-- 1. 专属口令与邀请生成区 -->
      <div class="invite-card-block">
        <h4>✨ 邀请同行好友组团协同</h4>
        <p class="sub-desc">好友加入后可共同编辑时间轴、添加景点并参与 AA 制记账</p>
        
        <div class="code-copy-row" v-if="inviteCode">
          <div class="code-box">
            <span class="code-label">专属邀请码:</span>
            <strong class="code-text">{{ inviteCode }}</strong>
          </div>
          <button class="copy-btn" @click="handleCopyInvite">📋 复制邀请文案</button>
        </div>
        <div class="loading-code" v-else>正在生成加密邀请口令...</div>
      </div>

      <!-- 2. 已加入成员列表 -->
      <div class="members-list-section">
        <h4>📋 已在组团成员</h4>
        <div class="members-grid">
          <div v-for="m in members" :key="m.userId" class="member-item">
            <div class="member-left">
              <img 
                :src="m.avatarSnapshot || 'https://api.dicebear.com/7.x/bottts/svg?seed=' + m.userId" 
                class="member-avatar"
              />
              <div class="member-info">
                <span class="member-name">{{ m.nicknameSnapshot }}</span>
                <span :class="['role-pill', m.role.toLowerCase()]">
                  {{ m.role === 'OWNER' ? '👑 发起人(Owner)' : '✏️ 协同编辑者' }}
                </span>
              </div>
            </div>

            <button 
              v-if="canRemove(m)" 
              class="remove-btn" 
              @click="handleRemoveMember(m)"
            >
              {{ isSelf(m) ? '退出' : '移除' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { getInviteCodeApi, getTripMembersApi, removeMemberApi } from '@/api/member'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  tripId: { type: [Number, String], required: true },
  visible: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'member-changed'])

const userStore = useUserStore()
const inviteCode = ref('')
const members = ref([])

const loadData = async () => {
  try {
    const [codeRes, memRes] = await Promise.all([
      getInviteCodeApi(props.tripId),
      getTripMembersApi(props.tripId)
    ])
    inviteCode.value = codeRes.data.inviteCode
    members.value = memRes.data || []
    emit('member-changed', members.value)
  } catch (err) {
    console.error('拉取协同成员数据异常:', err)
  }
}

watch(() => props.visible, (val) => {
  if (val) loadData()
})

onMounted(() => {
  if (props.visible) loadData()
})

const isSelf = (m) => String(m.userId) === String(userStore.userInfo.userId || userStore.userInfo.id)
const isOwner = () => members.value.some(m => m.role === 'OWNER' && isSelf(m))

const canRemove = (m) => {
  if (m.role === 'OWNER') return false
  return isOwner() || isSelf(m)
}

const handleCopyInvite = async () => {
  const shareText = `✈️ 邀请你加入我的旅行行程协同组团！\n点击链接加入或在系统输入邀请码：【${inviteCode.value}】\n链接：${window.location.origin}/trip/edit/${props.tripId}`
  try {
    await navigator.clipboard.writeText(shareText)
    alert('🎉 邀请口令已复制！快去微信发给好友吧！')
  } catch (e) {
    alert(shareText)
  }
}

const handleRemoveMember = async (m) => {
  const text = isSelf(m) ? '确定要退出该协同行程吗？' : `确定要将成员【${m.nicknameSnapshot}】移出行程吗？`
  if (!confirm(text)) return
  try {
    await removeMemberApi(props.tripId, m.userId)
    alert('操作成功！')
    await loadData()
  } catch (err) {
    console.error(err)
  }
}
</script>

<style scoped>
.modal-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.5); display: flex; justify-content: center; align-items: center; z-index: 9999;
}
.modal-box {
  background: white; border-radius: 12px; width: 90%; max-width: 520px; padding: 24px; max-height: 85vh; overflow-y: auto;
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.header-left {
  display: flex; align-items: center; gap: 8px;
}
.header-left h3 {
  font-size: 16px; color: #0f172a; margin: 0;
}
.member-count-badge {
  font-size: 11px; background: #eff6ff; color: #2563eb; padding: 2px 8px; border-radius: 10px; font-weight: bold;
}
.close-btn { background: transparent; border: none; font-size: 18px; cursor: pointer; color: #94a3b8; }

.invite-card-block {
  background: linear-gradient(135deg, #f0fdf4 0%, #dcfce7 100%);
  border: 1px solid #bbf7d0;
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 20px;
}
.invite-card-block h4 {
  font-size: 13px; color: #166534; margin: 0 0 4px 0;
}
.sub-desc {
  font-size: 11px; color: #15803d; margin-bottom: 10px;
}
.code-copy-row {
  display: flex; justify-content: space-between; align-items: center;
  background: white; padding: 8px 12px; border-radius: 6px; border: 1px solid #86efac;
}
.code-box {
  display: flex; align-items: center; gap: 6px; font-size: 12px;
}
.code-label { color: #64748b; }
.code-text { font-family: monospace; font-size: 14px; color: #059669; }
.copy-btn {
  background: #059669; color: white; border: none; padding: 4px 10px; border-radius: 4px; font-size: 11px; font-weight: bold; cursor: pointer;
}

.members-list-section h4 {
  font-size: 13px; color: #475569; margin-bottom: 10px;
}
.members-grid {
  display: flex; flex-direction: column; gap: 8px; max-height: 240px; overflow-y: auto;
}
.member-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;
}
.member-left {
  display: flex; align-items: center; gap: 10px;
}
.member-avatar {
  width: 32px; height: 32px; border-radius: 50%; object-fit: cover; background: #cbd5e1;
}
.member-info {
  display: flex; flex-direction: column; gap: 2px;
}
.member-name {
  font-size: 13px; font-weight: 500; color: #0f172a;
}
.role-pill {
  font-size: 10px; padding: 1px 5px; border-radius: 4px; width: fit-content;
}
.role-pill.owner { background: #fef3c7; color: #b45309; }
.role-pill.editor { background: #eff6ff; color: #2563eb; }

.remove-btn {
  background: transparent; border: 1px solid #fca5a5; color: #dc2626; padding: 2px 8px; border-radius: 4px; font-size: 11px; cursor: pointer;
}
.remove-btn:hover { background: #fee2e2; }
</style>