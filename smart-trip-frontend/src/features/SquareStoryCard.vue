<script setup lang="ts">
import type { Post } from "./types";
import { money } from "./api";
import HomeIcon from "./HomeIcon.vue";
withDefaults(defineProps<{ post: Post; lead?: boolean; compact?: boolean }>(), {
  lead: false,
  compact: false,
});
</script>

<template>
  <article
    class="square-story"
    :class="{ 'story-card': !compact, lead, 'compact-card': compact }"
    data-testid="square-story"
  >
    <div class="story-media" :aria-hidden="compact ? undefined : true">
      <div v-if="compact" class="compact-destination">
        <HomeIcon name="pin" /><span>{{ post.destination }}</span>
      </div>
    </div>
    <div class="story-copy">
      <div v-if="!compact" class="story-destination">
        <HomeIcon name="pin" /><span>{{ post.destination }}</span
        ><span>{{ post.totalDays }} 天</span>
      </div>
      <h3>{{ post.title }}</h3>
      <p class="story-summary">
        {{ post.summary || "打开路书，看看沿途安排。" }}
      </p>
      <div class="story-bottom">
        <div class="story-meta explore-meta">
          <span class="story-author">{{ post.authorName }}</span
          ><span v-if="compact">{{ post.totalDays }} 天</span
          ><span>预估 {{ money(post.totalBudget) }}</span
          ><span class="story-interactions"
            >点赞 {{ post.likeCount }} · 收藏 {{ post.favoriteCount }}</span
          >
        </div>
        <RouterLink
          class="open-story explore-entry"
          :class="{ 'explore-lead': lead }"
          :to="'/posts/' + post.id"
          :aria-label="'阅读路书：' + post.title"
          ><HomeIcon name="arrow"
        /></RouterLink>
      </div>
    </div>
  </article>
</template>

<style scoped>
.square-story {
  position: relative;
  isolation: isolate;
  min-width: 0;
  overflow: hidden;
  border-radius: 14px;
  overflow-wrap: anywhere;
}
.story-card {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  background: #244455;
  color: #fff;
}
.story-card.lead {
  grid-row: span 2;
}
.story-media {
  position: absolute;
  inset: 0;
  z-index: -1;
  background: linear-gradient(180deg, #2d5163, #172f3d);
}
.story-copy {
  padding: 24px;
}
.story-destination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  font-size: 14px;
}
.icon {
  width: 18px;
  height: 18px;
  flex: none;
}
h3 {
  margin: 10px 0 0;
  font:
    650 23px/1.35 "Segoe UI",
    "Microsoft YaHei",
    "PingFang SC",
    sans-serif;
  text-wrap: balance;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.lead h3 {
  font-size: 32px;
}
.story-summary {
  color: #eef4f7;
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.7;
  max-width: 43ch;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
  overflow: hidden;
}
.lead .story-summary {
  -webkit-line-clamp: 3;
}
.story-bottom {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin-top: 16px;
}
.story-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  min-width: 0;
  font-size: 12px;
  line-height: 1.6;
}
.story-author,
.story-interactions {
  flex-basis: 100%;
}
.open-story {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: #fff;
  color: #1b3444;
  flex: none;
  text-decoration: none;
  transition: background 160ms cubic-bezier(0.16, 1, 0.3, 1);
}
.open-story::after {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: 14px;
}
.open-story:hover {
  background: #ffd8bd;
}
.open-story .icon {
  width: 20px;
  height: 20px;
}
.open-story:focus-visible {
  outline: 3px solid #ffbf88;
  outline-offset: 4px;
}
.compact-card {
  background: #fff;
  color: #1f303d;
}
.compact-card .story-media {
  position: relative;
  height: 178px;
  z-index: auto;
}
.compact-destination {
  position: absolute;
  bottom: 12px;
  left: 16px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #fff;
  font-size: 13px;
}
.compact-card .story-copy {
  padding: 17px 18px;
}
.compact-card h3 {
  margin: 0;
  font-size: 18px;
  line-height: 1.4;
}
.compact-card .story-summary {
  color: #536b7b;
  font-size: 13px;
  line-height: 1.65;
  -webkit-line-clamp: 2;
}
.compact-card .story-meta {
  color: #536b7b;
}
.compact-card .open-story {
  width: 30px;
  height: 30px;
  color: #ae481e;
  border: 1px solid #e4b8a0;
}
.compact-card .open-story .icon {
  width: 18px;
  height: 18px;
}
.compact-card .open-story:focus-visible {
  outline-color: #ae481e;
}
@media (max-width: 1100px) {
  .story-copy {
    padding: 20px;
  }
  h3 {
    font-size: 20px;
  }
  .lead h3 {
    font-size: 27px;
  }
}
@media (max-width: 760px) {
  .story-card {
    min-height: 260px;
  }
  .story-card.lead {
    min-height: 350px;
    grid-row: auto;
  }
  h3 {
    font-size: 22px;
  }
  .lead h3 {
    font-size: 26px;
  }
  .story-summary {
    -webkit-line-clamp: 2;
  }
  .compact-card {
    display: grid;
    grid-template-columns: 112px minmax(0, 1fr);
  }
  .compact-card .story-media {
    height: 100%;
    min-height: 154px;
  }
  .compact-destination {
    left: 9px;
    right: 9px;
    bottom: 10px;
    font-size: 12px;
    gap: 3px;
  }
  .compact-destination .icon {
    width: 15px;
    height: 15px;
  }
  .compact-card .story-copy {
    padding: 14px;
  }
  .compact-card h3 {
    font-size: 16px;
    margin-bottom: 5px;
  }
  .compact-card .story-summary {
    font-size: 12px;
  }
  .compact-card .story-bottom {
    margin-top: 10px;
  }
  .compact-card .story-meta {
    font-size: 11px;
    gap: 2px 8px;
  }
}
@media (max-width: 360px) {
  .compact-card {
    grid-template-columns: 96px minmax(0, 1fr);
  }
  .compact-card .story-copy {
    padding: 12px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .open-story {
    transition: none;
  }
}
</style>
