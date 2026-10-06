<script setup lang="ts">
import { ref } from "vue";
import { money } from "./api";
import { useHomeJourney } from "./useHomeJourney";
import HomeIcon from "./HomeIcon.vue";
import "./home-v3.css";
const {
  session,
  posts,
  trip,
  recent,
  target,
  days,
  stops,
  day,
  items,
  itemCount,
  duration,
  feedBusy,
  feedError,
  journeyBusy,
  journeyError,
  loadPosts,
  loadJourney,
} = useHomeJourney();
const photoFailed = ref(false);
const photo = "/design-v3/lake-journey.png";
const positions = ["stop-one", "stop-two", "stop-three", "stop-four"];
const intentions = ["出发", "探索", "停留", "记录"];
const diagramPoints = [
  { x: 107, y: 290 },
  { x: 215, y: 225 },
  { x: 274, y: 76 },
];
</script>
<template>
  <div class="v3-home">
    <section class="journey-hero" aria-labelledby="hero-title">
      <img
        v-if="!photoFailed"
        class="hero-photo"
        :src="photo"
        alt="山脉、湖水与村落的旅行概念影像，非目的地实拍"
        fetchpriority="high"
        width="1536"
        height="1024"
        @error="photoFailed = true"
      />
      <div class="hero-copy">
        <h1 id="hero-title">沿着旅程，<br />把时间交给风。</h1>
        <p>
          {{
            trip
              ? trip.destination + " · " + days.length + " 天旅行"
              : "把心动的地方，连成自己的旅程。"
          }}
        </p>
      </div>
      <div class="hero-weather glass">
        <HomeIcon /><span
          ><strong>{{ trip ? days.length + " 天" : "下一站" }}</strong
          ><small>{{
            trip ? itemCount + " 个已保存安排" : "从自己的旅行开始"
          }}</small></span
        >
      </div>
      <div
        class="journey-route"
        :aria-label="
          trip
            ? '已保存行程的日期顺序示意，非导航路线'
            : '装饰性旅程意象，非实际路线'
        "
      >
        <svg
          class="hero-route-desktop"
          viewBox="0 0 1440 740"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <path
            class="route-shadow"
            d="M370 525C530 610 420 355 690 333S1110 250 1160 410 855 534 960 622"
          />
          <path
            class="route-trace"
            d="M370 525C530 610 420 355 690 333S1110 250 1160 410 855 534 960 622"
          />
        </svg>
        <svg
          class="hero-route-mobile"
          viewBox="0 0 390 730"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          <path
            class="route-shadow"
            d="M78 295C162 253 175 393 292 341S277 479 156 430 12 439 66 505"
          />
          <path
            class="route-trace"
            d="M78 295C162 253 175 393 292 341S277 479 156 430 12 439 66 505"
          />
        </svg>
        <template v-if="trip"
          ><RouterLink
            v-for="(stop, i) in stops"
            :key="stop.index"
            :to="target"
            class="hero-stop"
            :class="positions[i]"
            ><span class="stop-dot">{{ stop.index }}</span
            ><span class="stop-label"
              ><small>第 {{ stop.index }} 天</small
              ><strong>{{ stop.name }}</strong></span
            ></RouterLink
          ></template
        >
        <template v-else
          ><span
            v-for="(word, i) in intentions"
            :key="word"
            class="hero-stop route-intention"
            :class="positions[i]"
            aria-hidden="true"
            ><span class="stop-dot"
              ><HomeIcon :name="i === 0 ? 'route' : 'pin'" /></span
            ><span class="stop-label"
              ><strong>{{ word }}</strong></span
            ></span
          ></template
        >
        <span class="route-distance"
          ><HomeIcon />{{
            trip
              ? "行程序列示意" +
                (days.length > 4 ? " · 另有 " + (days.length - 4) + " 天" : "")
              : "旅程意象 · 非实际路线"
          }}</span
        >
      </div>
      <div class="quick-plan glass" aria-label="开始规划旅行">
        <div class="plan-destination">
          <HomeIcon name="pin" /><span
            ><small>{{ trip ? "旅行目的地" : "下一站" }}</small
            ><strong>{{ trip?.destination || "由你决定" }}</strong></span
          >
        </div>
        <div class="plan-days">
          <small>{{ trip ? "路书安排" : "旅行方式" }}</small
          ><span>{{ trip ? days.length + " 天旅行" : "按自己的节奏" }}</span>
        </div>
        <RouterLink class="primary" to="/ai-generate"
          >开始规划 <HomeIcon name="arrow"
        /></RouterLink>
      </div>
      <div class="hero-footnote">
        <span>{{
          photoFailed ? "旅行概念影像暂不可用" : "概念影像 · 非目的地实拍"
        }}</span
        ><span>{{
          trip
            ? "地点来自已保存路书 · 连线非导航路线"
            : "用一份路书，开始下一段旅程"
        }}</span>
      </div>
    </section>

    <section class="trip-continuation" aria-label="继续我的旅行">
      <div class="trip-thumb">
        <img
          v-if="!photoFailed"
          :src="photo"
          alt="旅行主题概念缩略图"
          loading="lazy"
          @error="photoFailed = true"
        /><HomeIcon v-else /><span>{{ recent?.destination || "下一站" }}</span>
      </div>
      <div class="continue-copy">
        <h2>
          {{
            recent
              ? "继续你的旅行"
              : session.token
                ? "准备下一段旅程"
                : "让旅行，从这里继续"
          }}
        </h2>
        <p v-if="journeyBusy" role="status">正在读取旅行安排…</p>
        <p v-else-if="journeyError" role="alert">{{ journeyError }}</p>
        <p v-else>
          {{
            recent
              ? recent.title + " · " + recent.totalDays + " 天路书"
              : session.token
                ? "还没有路书？从一份新计划开始。"
                : "登录后查看自己的路书与旅行安排。"
          }}
        </p>
        <button v-if="journeyError" class="text-button" @click="loadJourney">
          重新读取旅行
        </button>
      </div>
      <div v-if="recent && trip" class="trip-progress">
        <span
          ><b>{{ days.length }} 天</b
          ><small> / {{ itemCount }} 个安排</small></span
        >
        <div class="saved-day-marks" aria-hidden="true">
          <i v-for="n in Math.min(days.length, 12)" :key="n" />
        </div>
        <p>已保存的旅行计划</p>
      </div>
      <RouterLink
        class="outline-button"
        :to="
          recent
            ? '/trip/edit/' + recent.id
            : session.token
              ? '/ai-generate?mode=manual'
              : '/my-trips'
        "
        >{{
          recent ? "回到工作台" : session.token ? "创建路书" : "查看我的路书"
        }}
        <HomeIcon name="arrow"
      /></RouterLink>
    </section>

    <section class="travel-system" aria-labelledby="itinerary-title">
      <div class="system-heading">
        <h2 id="itinerary-title">每一站，连成你的旅程。</h2>
        <p>地点、时间与旅行节奏，一起看清。</p>
        <span class="demo-caption">{{
          trip
            ? "已保存安排 · 顺序示意，非地图导航"
            : "有了路书，这里就会展开你的行程"
        }}</span>
      </div>
      <div class="system-composition">
        <div class="mini-map" aria-label="行程顺序示意，不代表实际地理位置">
          <svg
            viewBox="0 0 540 360"
            role="img"
            :aria-label="
              '行程序列示意，非导航路线：' +
              items.map((item, i) => i + 1 + '，' + item.itemName).join('；')
            "
          >
            <path
              fill="#bce1e9"
              d="M287 0h253v360H330c-36-50 37-68-9-118s-109-69-57-126S238 19 287 0Z"
            />
            <path
              d="M107 290 153 222 215 225 246 153 274 76"
              fill="none"
              stroke="#c64e1e"
              stroke-width="5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
            <g
              v-for="(item, i) in items"
              :key="item.id || i"
              class="sequence-node"
              :transform="`translate(${diagramPoints[i].x} ${diagramPoints[i].y})`"
            >
              <title>{{ i + 1 }} · {{ item.itemName }}</title>
              <circle
                class="sequence-marker"
                r="13"
                fill="#fff"
                stroke="#c64e1e"
                stroke-width="3"
              />
              <text
                class="sequence-number"
                y="1"
                text-anchor="middle"
                dominant-baseline="middle"
              >
                {{ i + 1 }}
              </text>
              <rect x="20" y="-18" width="220" height="36" rx="8" fill="#fff" />
              <text class="sequence-label" x="30" y="7">
                {{
                  item.itemName.length > 10
                    ? item.itemName.slice(0, 9) + "…"
                    : item.itemName
                }}
              </text>
            </g>
          </svg>
          <div class="mini-map-status">
            <HomeIcon />行程序列示意
            <b v-if="items.length">{{ items.length }} 个展示节点</b>
          </div>
        </div>
        <div class="graphic-itinerary">
          <div class="graphic-title">
            <h3>
              {{
                day?.dayTheme ||
                (trip ? trip.destination + "的旅行" : "你的安排，留给你决定")
              }}
            </h3>
            <span v-if="day?.dayIndex">第 {{ day.dayIndex }} 天</span>
          </div>
          <p v-if="journeyBusy" class="home-state" role="status">
            正在展开已保存行程…
          </p>
          <div v-else-if="journeyError" class="home-state">
            <p>暂时无法读取旅行安排。</p>
            <button class="text-button" @click="loadJourney">
              重新读取旅行
            </button>
          </div>
          <template v-else-if="items.length"
            ><RouterLink
              v-for="(item, i) in items"
              :key="item.id || i"
              :to="target"
              class="graph-row"
              ><b>{{ item.startTime || "时间待定" }}</b
              ><span class="graph-pin">{{ i + 1 }}</span>
              <div>
                <strong>{{ item.itemName }}</strong
                ><small
                  >{{
                    item.costTimeHours != null
                      ? item.costTimeHours + " 小时"
                      : "停留时长待定"
                  }}
                  · 预估 {{ money(item.costEstimate) }}</small
                >
              </div>
              <span class="type-icon"
                ><HomeIcon
                  :name="item.itemType === 'FOOD' ? 'food' : 'pin'" /></span
            ></RouterLink>
            <div class="pace-summary">
              <span
                ><HomeIcon name="clock" />{{
                  duration != null
                    ? "本日已填活动时长 " + duration + " 小时"
                    : "本日活动时长尚未填全"
                }}</span
              ><RouterLink :to="target"
                >查看完整路书 <HomeIcon name="arrow"
              /></RouterLink></div
          ></template>
          <div v-else class="home-state">
            <p>
              {{
                trip
                  ? "这份路书还没有地点安排，留出空间慢慢完善。"
                  : "先写下想去的地方，再安排属于自己的每一天。"
              }}
            </p>
            <RouterLink
              class="outline-button"
              :to="trip ? target : '/ai-generate?mode=manual'"
              >{{ trip ? "打开路书" : "创建自己的路书" }}
              <HomeIcon name="arrow"
            /></RouterLink>
          </div>
        </div>
      </div>
    </section>

    <section class="home-inspiration" aria-labelledby="inspiration-title">
      <div class="system-heading">
        <h2 id="inspiration-title">下一站，从这里开始</h2>
        <RouterLink class="text-button" to="/square"
          >探索全部 <HomeIcon name="arrow"
        /></RouterLink>
      </div>
      <p v-if="feedBusy" class="home-state" role="status">正在寻找旅行灵感…</p>
      <div v-else-if="feedError" class="home-state">
        <p role="alert">{{ feedError }}</p>
        <button class="text-button" @click="loadPosts">重新加载</button>
      </div>
      <div v-else-if="posts.length" class="home-posts">
        <RouterLink
          v-for="(post, i) in posts"
          :key="post.id"
          :to="'/posts/' + post.id"
          class="home-post"
          ><div class="post-route-art" aria-hidden="true">
            <HomeIcon /><span>{{ post.destination }}</span
            ><b>{{ post.totalDays }} 天</b
            ><svg viewBox="0 0 320 100">
              <path
                :d="
                  i % 2
                    ? 'M0 70Q100 0 160 50T320 20'
                    : 'M0 80Q70 10 150 65T320 25'
                "
                fill="none"
                stroke="currentColor"
                stroke-width="3"
              />
            </svg>
          </div>
          <div class="home-post-copy">
            <p>
              {{ post.destination }} · {{ post.totalDays }} 天 · 预估
              {{ money(post.totalBudget) }}
            </p>
            <h3>{{ post.title }}</h3>
            <p>{{ post.summary || "打开路书，看看沿途安排。" }}</p>
            <footer>
              <span>{{ post.authorName }}</span
              ><span
                >点赞 {{ post.likeCount }} · 收藏 {{ post.favoriteCount }}</span
              >
            </footer>
          </div></RouterLink
        >
      </div>
      <div v-else class="home-state">
        <h3>第一段故事，等你出发</h3>
        <p>广场还没有公开路书。先创建自己的旅行，再与大家分享。</p>
        <RouterLink class="outline-button" to="/my-trips"
          >创建路书 <HomeIcon name="arrow"
        /></RouterLink>
      </div>
    </section>

    <section class="horizon-section">
      <div class="horizon-photo">
        <img
          v-if="!photoFailed"
          :src="photo"
          alt="湖岸旅行概念影像，非目的地实拍"
          loading="lazy"
          @error="photoFailed = true"
        />
        <div class="horizon-overlay">
          <h2>让路线有方向，<br />让旅行有余地。</h2>
          <RouterLink class="light-button" to="/my-trips"
            >打开我的路书 <HomeIcon name="arrow" /></RouterLink
          ><small>概念影像 · 非目的地实拍</small>
        </div>
      </div>
      <div class="journey-facts">
        <div class="fact-route">
          <HomeIcon /><span>一份路书，串起所有安排</span>
        </div>
        <div class="fact-days" v-if="trip">
          <b>{{ days.length }}</b
          ><span>天旅行计划</span>
          <div class="day-marks" aria-hidden="true">
            <i v-for="n in Math.min(days.length, 4)" :key="n" />
          </div>
        </div>
        <div class="fact-saved">
          <HomeIcon name="pin" />
          <div>
            <b>{{ trip?.destination || "目的地，由你决定" }}</b
            ><span>{{
              trip ? itemCount + " 个已保存安排" : "手动创建，从一份新计划开始"
            }}</span>
          </div>
        </div>
        <RouterLink class="fact-action" to="/ai-generate"
          >开始规划旅行 <HomeIcon name="arrow"
        /></RouterLink>
      </div>
    </section>
  </div>
</template>
