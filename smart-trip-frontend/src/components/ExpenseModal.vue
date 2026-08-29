<template>
  <div class="modal-mask" v-if="visible">
    <div class="modal-box">
      <div class="modal-header">
        <h3>💰 旅行记账与预算看板</h3>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <!-- 预算进度大屏 -->
      <div class="budget-dashboard" v-if="summary">
        <div class="dashboard-cards">
          <div class="d-card">
            <span class="label">预设总预算</span>
            <span class="val">￥{{ summary.totalBudget }}</span>
          </div>
          <div class="d-card">
            <span class="label">实际已花费</span>
            <span class="val spent">￥{{ summary.totalSpent }}</span>
          </div>
          <div class="d-card">
            <span class="label">剩余预算</span>
            <span :class="['val', summary.remaining < 0 ? 'over' : 'remain']">
              ￥{{ summary.remaining }}
            </span>
          </div>
        </div>
      </div>

      <!-- 快速记账表单 -->
      <form @submit.prevent="handleAddExpense" class="expense-form">
        <h4>➕ 快速记一笔</h4>
        <div class="form-row">
          <select v-model="form.category" required>
            <option value="CATERING">🍜 餐饮美食</option>
            <option value="TRAFFIC">✈️ 交通出行</option>
            <option value="HOTEL">🏨 酒店住宿</option>
            <option value="TICKET">🎫 门票娱乐</option>
            <option value="SHOPPING">🛍️ 购物纪念</option>
            <option value="OTHER">💡 其他开支</option>
          </select>
          <input type="number" step="0.01" v-model="form.amount" placeholder="金额 (元)" required />
          <input type="text" v-model="form.expenseName" placeholder="消费说明 (如：火锅午餐)" required />
          <button type="submit" class="add-btn">记账</button>
        </div>
      </form>

      <!-- 历史流水列表 -->
      <div class="expense-list-section">
        <h4>📋 消费明细流水</h4>
        <div class="list-container" v-if="summary && summary.expenseList && summary.expenseList.length">
          <div v-for="item in summary.expenseList" :key="item.id" class="expense-item">
            <div class="item-left">
              <span class="cat-tag">{{ getCatLabel(item.category) }}</span>
              <span class="item-name">{{ item.expenseName }}</span>
              <span class="item-date">{{ item.expenseDate }}</span>
            </div>
            <div class="item-amount">-￥{{ item.amount }}</div>
          </div>
        </div>
        <div v-else class="empty-tips">暂无记账明细，快记一笔吧~</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getExpenseSummaryApi, addExpenseApi } from '@/api/tool'

const props = defineProps({
  tripId: { type: [Number, String], required: true },
  visible: { type: Boolean, default: false }
})
const emit = defineEmits(['close'])

const summary = ref(null)
const form = reactive({
  category: 'CATERING',
  amount: '',
  expenseName: '',
  expenseDate: new Date().toISOString().split('T')[0]
})

const fetchSummary = async () => {
  const res = await getExpenseSummaryApi(props.tripId)
  summary.value = res.data
}

onMounted(() => {
  if (props.visible) fetchSummary()
})

const handleAddExpense = async () => {
  await addExpenseApi(props.tripId, { ...form, amount: parseFloat(form.amount) })
  form.amount = ''
  form.expenseName = ''
  await fetchSummary()
}

const getCatLabel = (cat) => {
  const map = {
    CATERING: '🍜 餐饮', TRAFFIC: '✈️ 交通', HOTEL: '🏨 住宿',
    TICKET: '🎫 门票', SHOPPING: '🛍️ 购物', OTHER: '💡 其他'
  }
  return map[cat] || cat
}
</script>

<style scoped>
.modal-mask {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.5);
  display: flex; justify-content: center; align-items: center;
  z-index: 999;
}
.modal-box {
  background: white; border-radius: 12px;
  width: 90%; max-width: 650px; padding: 24px; max-height: 85vh; overflow-y: auto;
}
.modal-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;
}
.close-btn {
  background: transparent; border: none; font-size: 18px; cursor: pointer; color: #94a3b8;
}
.dashboard-cards {
  display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; margin-bottom: 20px;
}
.d-card {
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; text-align: center;
}
.d-card .label { font-size: 12px; color: #64748b; display: block; margin-bottom: 4px; }
.d-card .val { font-size: 18px; font-weight: bold; color: #0f172a; }
.d-card .val.spent { color: #ea580c; }
.d-card .val.remain { color: #16a34a; }
.d-card .val.over { color: #dc2626; }
.expense-form {
  background: #f1f5f9; padding: 16px; border-radius: 8px; margin-bottom: 20px;
}
.expense-form h4 { font-size: 13px; margin-bottom: 8px; color: #334155; }
.form-row { display: grid; grid-template-columns: 130px 100px 1fr 70px; gap: 8px; }
.form-row input, .form-row select {
  padding: 8px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; outline: none;
}
.add-btn {
  background: #2563eb; color: white; border: none; border-radius: 6px; font-weight: bold; cursor: pointer;
}
.expense-list-section h4 { font-size: 14px; margin-bottom: 8px; color: #475569; }
.list-container { max-height: 200px; overflow-y: auto; border: 1px solid #f1f5f9; border-radius: 6px; }
.expense-item {
  display: flex; justify-content: space-between; align-items: center; padding: 10px 12px;
  border-bottom: 1px solid #f1f5f9; font-size: 13px;
}
.item-left { display: flex; align-items: center; gap: 10px; }
.cat-tag { background: #e2e8f0; padding: 2px 6px; border-radius: 4px; font-size: 11px; }
.item-date { color: #94a3b8; font-size: 12px; }
.item-amount { font-weight: bold; color: #ef4444; }
.empty-tips { text-align: center; color: #94a3b8; padding: 20px; font-size: 13px; }
</style>