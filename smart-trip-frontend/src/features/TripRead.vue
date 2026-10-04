<script setup lang="ts">
import type { Trip } from "./types";
import { money } from "./api";
defineProps<{ trip: Trip }>();
const labels: Record<string, string> = {
  SCENIC: "游览",
  FOOD: "美食",
  HOTEL: "住宿",
  TRAFFIC: "交通",
  OTHER: "活动",
};
</script>
<template>
  <div class="trip-read">
    <div class="meta-row">
      <span class="pill">{{ trip.destination }}</span
      ><span>{{ trip.days.length }} 天</span
      ><span>{{ trip.startDate || "日期待定" }}</span
      ><span>预估 {{ money(trip.totalBudget) }}</span>
    </div>
    <p class="intro">{{ trip.summary || "留一点空白，给路上的惊喜。" }}</p>
    <section
      v-for="(day, index) in trip.days"
      :key="day.id || index"
      class="day-block"
    >
      <header class="day-heading">
        <span class="day-number">{{ String(index + 1).padStart(2, "0") }}</span>
        <div>
          <small>DAY {{ index + 1 }} · {{ day.dayDate || "日期待定" }}</small>
          <h2>{{ day.dayTheme || "自由探索" }}</h2>
        </div>
      </header>
      <p v-if="day.daySummary">{{ day.daySummary }}</p>
      <div v-if="!day.items.length" class="empty">这一天还没有安排。</div>
      <article v-for="(item, n) in day.items" :key="item.id || n" class="stop">
        <div class="stop-time">
          {{ item.startTime || "待定" }}<span>{{ n + 1 }}</span>
        </div>
        <div class="stop-content">
          <small>{{ labels[item.itemType] || "活动" }}</small>
          <h3>{{ item.itemName }}</h3>
          <p v-if="item.aiTips">{{ item.aiTips }}</p>
          <p v-if="item.transportTips" class="muted">
            交通 · {{ item.transportTips }}
          </p>
          <div class="meta-row">
            <span v-if="item.costTimeHours"
              >约 {{ item.costTimeHours }} 小时</span
            ><span>预估 {{ money(item.costEstimate) }}</span
            ><span v-if="item.poiId">已关联地点</span
            ><span
              v-if="item.environmentType && item.environmentType !== 'UNKNOWN'"
              >{{
                item.environmentType === "INDOOR"
                  ? "室内（用户标记）"
                  : "室外（用户标记）"
              }}</span
            ><span v-if="item.scheduleLocked">智能调整时固定</span>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>
