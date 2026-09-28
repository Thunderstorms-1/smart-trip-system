<template>
  <div class="generate-container">
    <!-- 顶部导航 -->
    <header class="header">
      <button class="back-btn" @click="router.push('/')">← 返回首页</button>
      <h2>✨ AI 智能旅行路线定制</h2>
      <div class="quota-tag">当前剩余配额: {{ userStore.remainingQuota }} 次</div>
    </header>

    <div class="main-layout">
      <!-- 左侧：偏好配置表单 -->
      <section class="form-card">
        <h3>1. 告诉 AI 你的旅行偏好</h3>

        <div class="form-group">
          <label>目的地城市</label>
          <input type="text" v-model="form.destination" placeholder="如：大理、敦煌、苏州" required />
        </div>

        <div class="form-row">
          <div class="form-group">
            <label>计划出行天数</label>
            <select v-model="form.totalDays">
              <option :value="1">1 天 (特种兵一日游)</option>
              <option :value="2">2 天 1 晚</option>
              <option :value="3">3 天 2 晚 (推荐)</option>
              <option :value="5">5 天 4 晚</option>
              <option :value="7">7 天深度游</option>
            </select>
          </div>

          <div class="form-group">
            <label>出发日期</label>
            <input type="date" v-model="form.startDate" />
          </div>
        </div>

        <div class="form-row">
          <div class="form-group">
            <label>总预算 (元)</label>
            <input type="number" v-model="form.totalBudget" placeholder="如：1500" />
          </div>

          <div class="form-group">
            <label>同行人群</label>
            <select v-model="form.travelGroup">
              <option value="独自一人">独自一人</option>
              <option value="情侣出行">情侣出行</option>
              <option value="亲子家庭">亲子家庭</option>
              <option value="三五好友">三五好友</option>
            </select>
          </div>
        </div>

        <div class="form-group">
          <label>偏好风格</label>
          <div class="style-tags">
            <span 
              v-for="style in styles" 
              :key="style" 
              :class="['tag', { active: form.tripStyle === style }]"
              @click="form.tripStyle = style"
            >
              {{ style }}
            </span>
          </div>
        </div>

        <div class="form-group">
          <label>补充个性化要求</label>
          <textarea 
            v-model="form.customRequirements" 
            rows="3" 
            placeholder="如：想看日落，多吃地道特色小吃，不想太累..."
          ></textarea>
        </div>

        <button 
          class="generate-btn" 
          :disabled="aiStore.isGenerating || !form.destination" 
          @click="startStreamGenerate"
        >
          {{ aiStore.isGenerating ? 'AI 正在深度规划中...' : '🚀 开始生成专属路书' }}
        </button>
      </section>

      <!-- 右侧：SSE 流式实时打字机终端 -->
      <section class="output-card">
        <div class="terminal-header">
          <div class="dots">
            <span class="dot red"></span>
            <span class="dot yellow"></span>
            <span class="dot green"></span>
          </div>
          <span class="terminal-title">SmartTrip AI Engine Live Stream</span>
          <button class="skip-btn" @click="goToTripWorkbench">
            {{ aiStore.isFinished ? '🎉 进入工作台 →' : '⏩ 稍后查看/直接进入工作台' }}
          </button>
        </div>

        <div class="terminal-body" ref="terminalBodyRef">
          <div v-if="!aiStore.streamOutput && !aiStore.isGenerating" class="placeholder">
            <p>👈 在左侧填写偏好后点击生成</p>
            <p>AI 将在此处实时以打字机形式输出路线方案与避坑指南...</p>
          </div>

          <!-- 实时文字流与 Markdown 渲染 -->
          <!-- 实时纯净中文流式打字机 -->
          <div v-else class="stream-content">
            <div class="markdown-body" v-html="marked.parse(aiStore.streamOutput || '')"></div>
            <span v-if="aiStore.isGenerating" class="cursor"></span>
          </div>
        </div>

        <div v-if="aiStore.isFinished" class="finish-action">
          <p>🎉 路书已生成并保存！</p>
          <button class="view-trip-btn" @click="goToTripWorkbench">
            进入行程可视化工作台 →
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import { useUserStore } from '@/stores/user'
import { useAIStore } from '@/stores/ai'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const aiStore = useAIStore()

const terminalBodyRef = ref(null)

const styles = ['美食探索', '人文古迹', '自然风光', '特种兵打卡', '休闲度假']

const form = reactive({
  destination: '成都',
  totalDays: 3,
  startDate: new Date().toISOString().split('T')[0],
  totalBudget: 1500,
  travelGroup: '情侣出行',
  tripStyle: '美食探索',
  customRequirements: '想看大熊猫，不想早起，多吃地道辣味'
})

// =========================================================================
// 【核心新增】：挂载时解析 URL Query 参数并自动回填表单
// =========================================================================
onMounted(() => {
  const query = route.query
  if (query && Object.keys(query).length > 0) {
    if (query.destination) form.destination = String(query.destination)
    if (query.totalDays) form.totalDays = Number(query.totalDays)
    if (query.tripStyle) form.tripStyle = String(query.tripStyle)
    if (query.travelGroup) form.travelGroup = String(query.travelGroup)
    if (query.customRequirements) form.customRequirements = String(query.customRequirements)

    // 若带有 autoStart='true' 且有目的地，可直接自动触发流式生成
    if (query.autoStart === 'true' && form.destination) {
      startStreamGenerate()
    }
  }
})

// 纯净文本展示（过滤 json 数据块）
const cleanMarkdown = (rawText) => {
  if (!rawText) return ''
  return rawText.replace(/```(?:json|JSON)?[\s\S]*$/, '').trim()
}

const startStreamGenerate = async () => {
  aiStore.startGenerating()
  const token = localStorage.getItem('AUTH_TOKEN')

  try {
    const response = await fetch('http://localhost:8080/api/ai/trip/stream-generate', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(form)
    })

    if (!response.ok) {
      alert('请求 AI 引擎失败，状态码: ' + response.status)
      aiStore.resetState()
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      const chunk = decoder.decode(value, { stream: true })
      const lines = chunk.split('\n')
      
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const rawData = line.replace('data:', '')
          
          if (rawData.includes('tripId')) {
            try {
              const finishObj = JSON.parse(rawData.trim())
              aiStore.finishGenerating(finishObj.tripId)
            } catch (e) {}
          } else {
            // 防污拦截
            if (rawData.includes('```json') || rawData.includes('itemType') || rawData.includes('costEstimate')) {
              continue
            }
            aiStore.appendChunk(rawData)
            nextTick(() => {
              if (terminalBodyRef.value) {
                terminalBodyRef.value.scrollTop = terminalBodyRef.value.scrollHeight
              }
            })
          }
        }
      }
    }

    userStore.fetchUserInfo()

  } catch (error) {
    console.error('SSE 流读取异常', error)
    alert('流式连接中断: ' + error.message)
    aiStore.resetState()
  }
}

const goToTripWorkbench = () => {
  const targetTripId = aiStore.generatedTripId || 200501
  router.push({ name: 'trip-edit', params: { tripId: targetTripId } })
}
</script>

<style scoped>
.generate-container {
  min-height: 100vh;
  background-color: #0f172a;
  color: #e2e8f0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  padding: 20px 40px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.back-btn {
  background: transparent;
  border: 1px solid #334155;
  color: #94a3b8;
  padding: 8px 16px;
  border-radius: 6px;
  cursor: pointer;
}

.back-btn:hover {
  color: white;
  border-color: #64748b;
}

.quota-tag {
  background: #1e293b;
  border: 1px solid #38bdf8;
  color: #38bdf8;
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 13px;
}

.main-layout {
  display: grid;
  grid-template-columns: 420px 1fr;
  gap: 24px;
}

.form-card {
  background: #1e293b;
  padding: 24px;
  border-radius: 12px;
  border: 1px solid #334155;
}

.form-card h3 {
  margin-bottom: 20px;
  font-size: 18px;
  color: #f8fafc;
}

.form-group {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

label {
  font-size: 13px;
  color: #94a3b8;
  margin-bottom: 6px;
}

input, select, textarea {
  background: #0f172a;
  border: 1px solid #334155;
  color: white;
  padding: 10px;
  border-radius: 6px;
  font-size: 14px;
  outline: none;
}

input:focus, select:focus, textarea:focus {
  border-color: #38bdf8;
}

.style-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag {
  padding: 6px 12px;
  border: 1px solid #334155;
  border-radius: 20px;
  font-size: 12px;
  cursor: pointer;
  background: #0f172a;
}

.tag.active {
  background: #38bdf8;
  color: #0f172a;
  font-weight: bold;
  border-color: #38bdf8;
}

.generate-btn {
  width: 100%;
  padding: 14px;
  background: linear-gradient(135deg, #0ea5e9 0%, #2563eb 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  margin-top: 10px;
}

.generate-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.output-card {
  background: #1e293b;
  border-radius: 12px;
  border: 1px solid #334155;
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
}

.terminal-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: #0f172a;
  border-top-left-radius: 12px;
  border-top-right-radius: 12px;
  border-bottom: 1px solid #334155;
}

.dots {
  display: flex;
  gap: 6px;
  margin-right: 12px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.dot.red { background: #ef4444; }
.dot.yellow { background: #eab308; }
.dot.green { background: #22c55e; }

.terminal-title {
  font-size: 12px;
  color: #64748b;
  font-family: monospace;
}

.skip-btn {
  margin-left: auto;
  background: #2563eb;
  color: white;
  border: none;
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: bold;
  cursor: pointer;
}
.skip-btn:hover {
  background: #1d4ed8;
}

.terminal-body {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  font-size: 14px;
  line-height: 1.6;
}

.placeholder {
  text-align: center;
  color: #64748b;
  margin-top: 100px;
}

/* Markdown 优雅排版样式 */
.markdown-body {
  color: #e2e8f0;
  line-height: 1.8;
  font-size: 14px;
}
.markdown-body :deep(h3) {
  color: #38bdf8;
  margin: 16px 0 8px 0;
  font-size: 16px;
  border-bottom: 1px solid #334155;
  padding-bottom: 4px;
}
.markdown-body :deep(h4) {
  color: #fbbf24;
  margin: 12px 0 6px 0;
  font-size: 14px;
}
.markdown-body :deep(p) {
  margin-bottom: 8px;
}
.markdown-body :deep(strong) {
  color: #f8fafc;
}
.markdown-body :deep(pre) {
  background: #0f172a;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  border: 1px solid #334155;
  color: #a5f3fc;
}

.cursor {
  display: inline-block;
  width: 8px;
  height: 16px;
  background: #38bdf8;
  vertical-align: middle;
  animation: blink 1s infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.finish-action {
  padding: 16px;
  background: #0f172a;
  border-top: 1px solid #334155;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom-left-radius: 12px;
  border-bottom-right-radius: 12px;
}

.view-trip-btn {
  padding: 10px 20px;
  background: #22c55e;
  color: white;
  border: none;
  border-radius: 6px;
  font-weight: bold;
  cursor: pointer;
}
</style>