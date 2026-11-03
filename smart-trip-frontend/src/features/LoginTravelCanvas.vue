<script setup lang="ts">
import { useId } from "vue";

const id = useId().replace(/:/g, "");
// These contours and the journey line are brand artwork, not a geographical map.
const contours = Array.from({ length: 11 }, (_, index) => ({
  scale: 1 - index * 0.052,
  x: index * 24,
  y: index * 17,
  opacity: 0.09 + index * 0.008,
}));
</script>

<template>
  <div class="login-travel-canvas" data-login-canvas>
    <div class="canvas-geography" aria-hidden="true">
      <div class="terrain-parallax">
        <svg
          class="terrain terrain-distant"
          viewBox="0 0 780 650"
          preserveAspectRatio="none"
          data-login-terrain
        >
          <defs>
            <linearGradient :id="`${id}-land`" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#77b9ad" stop-opacity=".1" />
              <stop offset=".58" stop-color="#6ba4a7" stop-opacity=".04" />
              <stop offset="1" stop-color="#a5d2cc" stop-opacity=".12" />
            </linearGradient>
          </defs>
          <path
            :fill="`url(#${id}-land)`"
            d="M366-42C280 56 396 108 408 161S328 255 369 308 500 334 489 400 342 456 323 522 394 626 471 685H851V-42Z"
          />
          <path
            class="terrain-horizon"
            d="M226 627C197 563 269 529 303 492S279 414 353 378 473 392 510 345 465 246 527 204 649 167 645 113 585 27 674-17"
          />
          <path
            class="terrain-horizon terrain-horizon-fine"
            d="M267 669C240 595 299 550 335 518S324 449 380 420 503 423 542 366 512 270 567 233 683 191 683 131 622 50 710-5"
          />
        </svg>
        <svg
          class="terrain terrain-near"
          viewBox="0 0 780 650"
          preserveAspectRatio="none"
          data-login-terrain
        >
          <g fill="none" stroke="#a6d1cd" stroke-width="1">
            <path
              v-for="(contour, index) in contours"
              :key="index"
              d="M378 28C453-29 643 12 684 84S647 189 694 250 820 321 759 405 639 427 612 484 624 615 506 616 397 529 400 465 455 380 417 323 321 262 337 186 324 71 378 28Z"
              :transform="`translate(${contour.x}, ${contour.y}) scale(${contour.scale})`"
              :opacity="contour.opacity"
              vector-effect="non-scaling-stroke"
            />
          </g>
          <path
            class="terrain-river"
            d="M780 322C696 300 699 350 638 355S556 322 515 371 522 443 447 471 300 454 258 521 273 609 194 650"
          />
        </svg>
      </div>

      <svg
        class="journey-drawing"
        viewBox="0 0 780 650"
        preserveAspectRatio="none"
        fill="none"
      >
        <path
          class="journey-guide"
          d="M310 555C355 560 351 493 390 473S421 420 450 444 564 459 575 371 619 288 665 220"
        />
        <path
          class="journey-drawn-route"
          d="M310 555C355 560 351 493 390 473S421 420 450 444 564 459 575 371 619 288 665 220"
          pathLength="1"
          data-login-route
        />
        <g data-login-node>
          <circle class="node-response" cx="310" cy="555" r="15" />
          <circle class="journey-node-halo" cx="310" cy="555" r="9" />
          <circle class="journey-node" cx="310" cy="555" r="4" />
        </g>
        <g data-login-node>
          <circle class="node-response" cx="450" cy="444" r="17" />
          <path class="journey-node-halo" d="m450 432 12 12-12 12-12-12Z" />
          <path class="journey-node" d="m450 440 4 4-4 4-4-4Z" />
        </g>
        <g data-login-node>
          <circle class="node-response" cx="665" cy="220" r="18" />
          <circle class="journey-node-halo" cx="665" cy="220" r="12" />
          <path
            class="journey-flag"
            d="M662 228v-18m0 0c4-4 7 4 11 0v9c-4 4-7-4-11 0"
          />
        </g>
      </svg>

      <!-- Mountain, lake and town are travel moods, never fabricated POI data. -->
      <div class="destination destination-mountain" data-login-destination>
        <div class="destination-surface">
          <svg viewBox="0 0 120 66" fill="none">
            <path class="landform-fill" d="m4 58 32-42 18 23 18-31 44 50Z" />
            <path
              d="m4 58 32-42 18 23 18-31 44 50M24 58l22-30m14 30 20-37m-5 37 12-23"
            />
            <path class="landform-detail" d="M4 65h108M21 10l5-3m62 2 5 2" />
          </svg>
          <span>去山间<small>把脚步，交给风。</small></span>
        </div>
      </div>
      <div class="destination destination-lake" data-login-destination>
        <div class="destination-surface">
          <svg viewBox="0 0 120 66" fill="none">
            <path
              class="landform-fill"
              d="M9 38c3-14 25-22 52-19s47 13 47 22-28 19-54 16S4 51 9 38Z"
            />
            <path
              d="M9 38c3-14 25-22 52-19s47 13 47 22-28 19-54 16S4 51 9 38Z"
            />
            <path
              d="M22 39c4-9 22-13 40-10s34 7 31 14-24 10-42 8-33-5-29-12Z"
            />
            <path
              class="landform-detail"
              d="M40 40c2-4 14-5 25-3s14 6 4 7-32 1-29-4Z"
            />
          </svg>
          <span>在湖畔<small>留一段，慢下来的时间。</small></span>
        </div>
      </div>
      <div class="destination destination-town" data-login-destination>
        <div class="destination-surface">
          <svg viewBox="0 0 120 66" fill="none">
            <path
              class="landform-fill"
              d="M15 57V30l22-12 22 12v27m4 0V13l17-8 17 8v44Z"
            />
            <path
              d="M15 57V30l22-12 22 12v27m4 0V13l17-8 17 8v44M10 58h100M37 20v37M80 7v50"
            />
            <path
              class="landform-detail"
              d="M23 36v6m23-6v6M71 20v6m17-6v6M71 36v6m17-6v6"
            />
          </svg>
          <span>遇见小城<small>总有一条路，通向心动。</small></span>
        </div>
      </div>
    </div>

    <div class="canvas-copy">
      <h1 id="login-journey-title" class="canvas-title">
        <span class="title-mask"
          ><span data-login-title>下一段旅程，</span></span
        >
        <span class="title-mask"
          ><span data-login-title><em>从这里</em>开始。</span></span
        >
      </h1>
      <p class="canvas-caption" data-login-caption>
        收藏心动的路线，规划专属的旅行，<br />遇见更大的世界。
      </p>
      <RouterLink class="canvas-explore" to="/square" data-login-extra>
        <span class="canvas-compass">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <path class="compass-needle" d="m16 8-2 6-6 2 2-6Z" />
          </svg>
        </span>
        探索旅行灵感
        <svg
          class="explore-arrow"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <path d="M4 12h15m-5-5 5 5-5 5" />
        </svg>
      </RouterLink>
      <p class="canvas-intention">让旅行更简单，更有意义</p>
    </div>
  </div>
</template>

<style scoped>
.login-travel-canvas {
  --canvas-white: #f0f9f7;
  --canvas-soft: #bdd6d7;
  --canvas-orange: #ff9a62;
  position: relative;
  min-width: 0;
  min-height: clamp(500px, 62vh, 640px);
  container-type: inline-size;
  color: var(--canvas-white);
  font-family: var(--login-font-ui, sans-serif);
  isolation: isolate;
}
.canvas-geography {
  position: absolute;
  inset: 0 -4% -3% 0;
  pointer-events: none;
}
.terrain-parallax {
  position: absolute;
  inset: 0;
  transform: translate3d(var(--canvas-x, 0px), var(--canvas-y, 0px), 0);
}
.terrain,
.journey-drawing {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
}
.terrain-horizon {
  fill: none;
  stroke: #a5d9ce;
  stroke-width: 1;
  stroke-opacity: 0.22;
}
.terrain-horizon-fine {
  stroke-opacity: 0.1;
}
.terrain-river {
  fill: none;
  stroke: #a8dbe4;
  stroke-width: 3;
  stroke-opacity: 0.12;
}
.journey-guide,
.journey-drawn-route {
  stroke-linecap: round;
  vector-effect: non-scaling-stroke;
}
.journey-guide {
  stroke: #ccdedb;
  stroke-opacity: 0.13;
  stroke-width: 1;
}
.journey-drawn-route {
  stroke: var(--canvas-orange);
  stroke-width: 1.7;
}
.journey-node-halo {
  fill: #183b41;
  stroke: #c9e4df;
  stroke-width: 1;
}
.journey-node {
  fill: var(--canvas-orange);
}
.journey-flag {
  stroke: var(--canvas-orange);
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.node-response {
  fill: none;
  stroke: var(--canvas-orange);
  stroke-width: 1;
  opacity: 0;
  transform-box: fill-box;
  transform-origin: center;
}
.destination {
  position: absolute;
  width: 152px;
  pointer-events: auto;
  perspective: 700px;
}
.destination-mountain {
  left: 29%;
  top: 84%;
}
.destination-lake {
  left: 50%;
  top: 76%;
}
.destination-town {
  left: 75%;
  top: 12%;
}
.destination-surface {
  width: 100%;
  padding: 0 8px 10px;
  transform-origin: center 65%;
  transition: transform 450ms cubic-bezier(0.16, 1, 0.3, 1);
}
.destination:hover .destination-surface {
  transform: translate3d(0, -5px, 0) rotateY(-7deg) rotateX(4deg);
}
.destination-lake:hover .destination-surface {
  transform: translate3d(3px, -4px, 0) rotateY(6deg);
}
.destination-town:hover .destination-surface {
  transform: translate3d(-3px, -4px, 0) rotateX(-5deg);
}
.destination-surface svg {
  width: 95px;
  height: 54px;
  overflow: visible;
  stroke: #a9d2cd;
  stroke-width: 1;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.landform-fill {
  fill: rgb(162 211 200 / 0.06);
}
.landform-detail {
  stroke-opacity: 0.45;
}
.destination-surface > span {
  display: block;
  margin-top: 5px;
  color: #e5f2ef;
  font-family: var(--login-font-editorial, serif);
  font-size: 16px;
  font-weight: 500;
  letter-spacing: 0.1em;
  line-height: 1.7;
}
.destination-surface small {
  display: block;
  margin-top: 4px;
  color: var(--canvas-soft);
  font-family: var(--login-font-editorial, serif);
  font-size: 11.5px;
  font-weight: 400;
  letter-spacing: 0.035em;
  line-height: 1.9;
  white-space: nowrap;
}
.canvas-copy {
  position: relative;
  width: 78%;
  padding-top: clamp(20px, 3vh, 42px);
  pointer-events: none;
}
.canvas-intention {
  margin: 16px 0 0;
  color: var(--canvas-soft);
  font-family: var(--login-font-editorial, serif);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.1em;
  line-height: 1.8;
}
.canvas-title {
  margin: 0;
  color: var(--canvas-white);
  font-family: var(--login-font-editorial, serif);
  font-size: clamp(36px, 7.2cqw, 72px);
  font-weight: 500;
  font-optical-sizing: auto;
  letter-spacing: 0.015em;
  line-height: 1.32;
}
.title-mask {
  display: block;
  overflow: clip;
  padding-bottom: 0.06em;
}
.title-mask + .title-mask {
  margin-top: 0.08em;
}
.title-mask > span {
  display: block;
  white-space: nowrap;
}
.canvas-title em {
  color: var(--canvas-orange);
  font-style: normal;
}
.canvas-caption {
  margin: 24px 0 0;
  color: #d2e5e4;
  font-family: var(--login-font-editorial, serif);
  font-size: clamp(14px, 2.1cqw, 17px);
  font-weight: 400;
  line-height: 1.95;
  letter-spacing: 0.045em;
}
.canvas-explore {
  display: inline-flex;
  align-items: center;
  gap: 13px;
  margin-top: 24px;
  padding: 6px 0;
  color: var(--canvas-white);
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  pointer-events: auto;
}
.canvas-compass {
  display: grid;
  place-items: center;
  width: 37px;
  height: 37px;
  border: 1px solid var(--login-glass-control-edge, rgb(231 250 247 / 0.45));
  border-radius: 50%;
  background: var(--login-glass-control-bg, rgb(240 255 252 / 0.12));
  box-shadow: var(
    --login-glass-control-shadow,
    inset 0 1px 0 rgb(255 255 255 / 0.3)
  );
  transition:
    background 250ms ease,
    border-color 250ms ease;
}
.canvas-explore:hover .canvas-compass {
  border-color: rgb(243 255 253 / 0.68);
  background: var(--login-glass-control-hover, rgb(240 255 252 / 0.2));
}
.canvas-explore svg {
  width: 22px;
  height: 22px;
  stroke: currentColor;
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.compass-needle {
  transform-origin: 12px 12px;
  transition: transform 500ms cubic-bezier(0.16, 1, 0.3, 1);
}
.canvas-explore:hover .compass-needle {
  transform: rotate(35deg);
}
.explore-arrow {
  transition: transform 250ms ease;
}
.canvas-explore:hover .explore-arrow {
  transform: translateX(4px);
}
.canvas-explore:focus-visible {
  outline: 2px solid var(--canvas-orange);
  outline-offset: 7px;
  border-radius: 3px;
}
@media (max-width: 1200px) and (min-width: 1001px) {
  .canvas-intention {
    font-size: 12px;
    letter-spacing: 0.06em;
  }
  .destination {
    width: 128px;
  }
  .destination-surface small {
    font-size: 10px;
  }
  .destination-town {
    left: 73%;
    top: 8%;
  }
  .canvas-caption {
    font-size: 14px;
  }
}
@media (max-width: 1000px) {
  .login-travel-canvas {
    min-height: 520px;
  }
  .canvas-copy {
    width: 75%;
    padding-top: 8px;
  }
  .canvas-title {
    font-size: clamp(40px, 7.2cqw, 62px);
  }
  .destination-town {
    top: 8%;
  }
}
@media (max-width: 760px) {
  .login-travel-canvas {
    min-height: 350px;
  }
  .canvas-copy {
    width: 100%;
    padding-top: 0;
  }
  .canvas-intention {
    margin-top: 15px;
    font-size: 11px;
    letter-spacing: 0.04em;
  }
  .canvas-title {
    font-size: clamp(32px, 9cqw, 56px);
    letter-spacing: 0.01em;
    line-height: 1.32;
  }
  .canvas-caption {
    margin-top: 18px;
    font-size: 14px;
  }
  .canvas-explore {
    margin-top: 15px;
    font-size: 13px;
  }
  .canvas-compass {
    width: 33px;
    height: 33px;
  }
  .canvas-geography {
    inset: 0 -5% -2% 6%;
  }
  .terrain {
    opacity: 0.55;
  }
  .journey-drawing {
    opacity: 0.4;
  }
  .destination-mountain,
  .destination-lake {
    display: none;
  }
  .destination-town {
    left: auto;
    right: 0;
    top: 64%;
    width: 104px;
  }
  .destination-town .destination-surface svg {
    width: 70px;
    height: 42px;
  }
  .destination-town .destination-surface > span {
    font-size: 13px;
    letter-spacing: 0.07em;
  }
  .destination-town small {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .terrain-parallax,
  .destination:hover .destination-surface,
  .canvas-explore:hover .compass-needle,
  .canvas-explore:hover .explore-arrow {
    transform: none;
  }
  .destination-surface,
  .canvas-compass,
  .compass-needle,
  .explore-arrow {
    transition: none;
  }
}
</style>
