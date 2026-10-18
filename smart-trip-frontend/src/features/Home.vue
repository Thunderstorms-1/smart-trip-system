<script setup lang="ts">
import { computed, ref } from "vue";
import HomeIcon from "./HomeIcon.vue";
import { useHomeJourney } from "./useHomeJourney";
import { useSeasonScroll } from "./useSeasonScroll";
import spring from "../assets/seasons/spring.webp";
import springCompact from "../assets/seasons/spring-compact.webp";
import summer from "../assets/seasons/summer.webp";
import summerCompact from "../assets/seasons/summer-compact.webp";
import autumn from "../assets/seasons/autumn.webp";
import autumnCompact from "../assets/seasons/autumn-compact.webp";
import winter from "../assets/seasons/winter.webp";
import winterCompact from "../assets/seasons/winter-compact.webp";
import "./home-v3.css";

const root = ref<HTMLElement | null>(null);
const stage = ref<HTMLElement | null>(null);
const closing = ref<HTMLElement | null>(null);
const { activeSeason, goToSeason, skipToEnd } = useSeasonScroll(
  root,
  stage,
  closing,
);
const { session, recent, journeyBusy, journeyError, loadJourney } =
  useHomeJourney();
const failedPhotos = ref<Record<string, boolean>>({});
const seasons = [
  {
    key: "spring",
    glyph: "春",
    english: "SPRING",
    name: "万物初醒",
    lines: ["在春天，", "重新出发。"],
    note: "雨停了。沿着湿润的石板路，听见万物醒来。",
    aside: "一场雨后，世界又近了一点。",
    image: spring,
    compact: springCompact,
    width: 1672,
    height: 941,
    alt: "雨后古巷，带着水珠的白色花枝横过青瓦白墙",
  },
  {
    key: "summer",
    glyph: "夏",
    english: "SUMMER",
    name: "盛夏来信",
    lines: ["把心事，", "交给海风。"],
    note: "阳光穿过薄纱。海面上的风，正吹向你。",
    aside: "不赶时间，只追一阵风。",
    image: summer,
    compact: summerCompact,
    width: 1448,
    height: 1086,
    alt: "海边露台上迎风扬起的白色纱帘，远处是阳光下的海浪与山岸",
  },
  {
    key: "autumn",
    glyph: "秋",
    english: "AUTUMN",
    name: "山色渐深",
    lines: ["走慢一点，", "山色正深。"],
    note: "沿着落叶铺成的小路，走进山雾深处。",
    aside: "把时间，留给沿途。",
    image: autumn,
    compact: autumnCompact,
    width: 1672,
    height: 941,
    alt: "秋叶落满湿润山路，石墙、老树与木屋隐入远山云雾",
  },
  {
    key: "winter",
    glyph: "冬",
    english: "WINTER",
    name: "静待雪落",
    lines: ["世界静下来。", "而你，正走向远方。"],
    note: "山不说话，湖面也安静。让这一刻，慢一点。",
    aside: "走过四季，仍然向往。",
    image: winter,
    compact: winterCompact,
    width: 1672,
    height: 941,
    alt: "冰封湖面与覆雪树枝，远山之间升起冷雾和微光",
  },
] as const;
const current = computed(() => seasons[activeSeason.value] || seasons[0]);
</script>

<template>
  <div ref="root" class="season-journey" data-motion="pending">
    <h1 class="season-sr-only">四季流转 · A Journey Through Seasons</h1>

    <div ref="stage" class="season-stage" aria-label="四季流转，向下滚动探索">
      <div class="season-scenes">
        <section
          v-for="(season, index) in seasons"
          :id="'season-' + season.key"
          :key="season.key"
          data-season-scene
          class="season-scene"
          :class="'season-scene--' + season.key"
          :aria-labelledby="'season-title-' + season.key"
        >
          <img
            v-if="!failedPhotos[season.key]"
            data-season-photo
            class="season-photo"
            :src="season.image"
            :srcset="`${season.compact} 960w, ${season.image} ${season.width}w`"
            sizes="(max-aspect-ratio: 3/4) 150vw, 100vw"
            :width="season.width"
            :height="season.height"
            :alt="season.alt"
            :loading="index === 0 ? 'eager' : 'lazy'"
            :fetchpriority="index === 0 ? 'high' : 'low'"
            decoding="async"
            @error="failedPhotos[season.key] = true"
          />
          <div class="season-scene-shade" aria-hidden="true" />
          <div
            data-season-atmosphere
            class="season-atmosphere"
            aria-hidden="true"
          >
            <i /><i /><i />
          </div>
          <span data-season-glyph class="season-glyph" aria-hidden="true">{{
            season.glyph
          }}</span>
          <div class="season-copy">
            <h2 :id="'season-title-' + season.key">
              <span
                v-for="line in season.lines"
                :key="line"
                class="season-line-mask"
              >
                <span data-season-line>{{ line }}</span>
              </span>
            </h2>
            <p class="season-story">{{ season.note }}</p>
          </div>
          <div class="season-scene-caption" aria-hidden="true">
            <span>{{ season.english }}</span>
            <span>{{ season.aside }}</span>
          </div>
          <p
            v-if="failedPhotos[season.key]"
            class="season-photo-error"
            role="status"
          >
            这一季的影像暂时未能加载，仍可继续探索或开始规划。
          </p>
        </section>
      </div>

      <div class="season-wayfinding">
        <span class="season-wayfinding-name">四季流转</span>
        <span class="season-wayfinding-english" lang="en"
          >A Journey Through Seasons</span
        >
      </div>

      <nav class="season-rail" aria-label="选择季节">
        <span class="season-rail-track" aria-hidden="true"><i /></span>
        <button
          v-for="(season, index) in seasons"
          :key="season.key"
          type="button"
          :class="{ 'is-current': activeSeason === index }"
          :aria-label="season.glyph + '：' + season.name"
          :aria-pressed="activeSeason === index"
          :aria-controls="'season-' + season.key"
          @click="goToSeason(index)"
        >
          <span>{{ season.glyph }}</span
          ><small>{{ season.name }}</small>
        </button>
      </nav>

      <div class="season-bottom-bar">
        <div class="season-scroll-cue" aria-hidden="true">
          <span class="season-scroll-line" />
          <span>{{ current.name }}<small>向下滚动，让四季经过你</small></span>
        </div>
        <nav class="season-action-dock" aria-label="旅行快捷入口">
          <RouterLink class="season-start-link" to="/ai-generate"
            >开始规划 <HomeIcon name="arrow"
          /></RouterLink>
          <RouterLink to="/square">旅行灵感</RouterLink>
          <RouterLink to="/my-trips">我的路书</RouterLink>
        </nav>
        <button class="season-skip" type="button" @click="skipToEnd">
          直接出发 <HomeIcon name="arrow" />
        </button>
      </div>
    </div>

    <section
      id="season-next-journey"
      ref="closing"
      class="season-closing"
      aria-labelledby="season-closing-title"
      tabindex="-1"
    >
      <div class="season-closing-intro">
        <p>有些风景，值得亲自走进去。</p>
        <h2 id="season-closing-title">
          下一段旅程，<br /><span>从此刻开始。</span>
        </h2>
      </div>
      <div class="season-closing-actions">
        <RouterLink class="season-primary" to="/ai-generate"
          >开始规划旅行 <HomeIcon name="arrow"
        /></RouterLink>
        <RouterLink class="season-explore" to="/square"
          >还没想好？去找一点灵感 <HomeIcon name="arrow"
        /></RouterLink>
      </div>

      <div class="season-return" aria-label="我的旅行">
        <HomeIcon name="route" />
        <div>
          <p v-if="journeyBusy" role="status">正在读取你的旅行安排…</p>
          <template v-else-if="journeyError">
            <p role="alert">{{ journeyError }}</p>
            <button class="season-retry" type="button" @click="loadJourney">
              重新读取
            </button>
          </template>
          <template v-else>
            <strong>{{
              recent
                ? "你的旅程，还在继续。"
                : session.token
                  ? "把想去的地方，留在路书里。"
                  : "属于你的旅程，从这里继续。"
            }}</strong>
            <p>
              {{
                recent
                  ? recent.title + " · " + recent.totalDays + " 天路书"
                  : session.token
                    ? "已有的计划与下一次出发，都在这里。"
                    : "登录后，查看自己的路书与旅行安排。"
              }}
            </p>
          </template>
        </div>
        <RouterLink :to="recent ? '/trip/edit/' + recent.id : '/my-trips'">
          {{ recent ? "继续这段旅程" : "我的路书" }} <HomeIcon name="arrow" />
        </RouterLink>
      </div>
    </section>
  </div>
</template>
