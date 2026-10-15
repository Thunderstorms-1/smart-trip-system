"use strict";
const featured = document.querySelector("#featured-stories");
const compact = document.querySelector("#compact-stories");
const input = document.querySelector("#query");
const status = document.querySelector("#search-status");
const empty = document.querySelector("#empty-state");
const reset = document.querySelector("#reset-search");
const moreSection = document.querySelector("#more-stories");
const moreLink = document.querySelector("#more-link");
let currentStories = [];

// Reserved for the production community feed. The preview has no API or sample dataset.
// Posts use the existing id/title/destination/totalDays/summary/authorName fields.
// There is no cover field in that API, so these templates do not invent cover photos.
function renderStories(posts = []) {
  currentStories = Array.isArray(posts)
    ? posts.filter(post => post && /^[1-9]\d*$/.test(String(post.id)))
    : [];
  render(input.value);
}
function text(value) { return typeof value === "string" ? value : ""; }
function storyCard(post, index, compactView) {
  const template = document.querySelector(compactView ? "#compact-story-template" : "#featured-story-template");
  const fragment = template.content.cloneNode(true);
  const card = fragment.firstElementChild;
  if (!compactView && index === 0) card.classList.add("lead");
  const fields = {
    destination: text(post.destination), title: text(post.title),
    summary: text(post.summary), author: text(post.authorName),
    days: Number.isInteger(post.totalDays) && post.totalDays > 0 ? `${post.totalDays}天` : "",
  };
  for (const [name, value] of Object.entries(fields)) {
    const field = card.querySelector(`[data-field="${name}"]`);
    field.textContent = value;
    field.hidden = !value;
  }
  const link = card.querySelector(".open-story");
  link.href = `/posts/${post.id}`;
  link.setAttribute("aria-label", `阅读路书：${fields.title}`);
  return fragment;
}
function render(query = "") {
  const q = query.trim().toLocaleLowerCase();
  const results = currentStories.filter(post =>
    (text(post.destination) + text(post.title)).toLocaleLowerCase().includes(q));
  featured.dataset.count = String(Math.min(results.length, 3));
  featured.replaceChildren(...results.slice(0, 3).map((post, index) => storyCard(post, index, false)));
  compact.replaceChildren(...results.slice(3).map((post, index) => storyCard(post, index, true)));
  empty.hidden = results.length > 0;
  moreSection.hidden = moreLink.hidden = results.length <= 3;
  featured.hidden = results.length === 0;
  document.querySelector("#stories-heading").textContent = q ? "探索结果" : "最近更新的路书";
  empty.querySelector("h3").textContent = currentStories.length ? "还没有找到这段旅程" : "等待下一段真实旅程";
  empty.querySelector("p").textContent = currentStories.length ? "换个城市或关键词试试。" : "这里将呈现旅行者公开分享的路书。";
  reset.hidden = !q;
  status.hidden = !q;
  if (q) status.textContent = currentStories.length
    ? `当前内容中找到 ${results.length} 篇路书。`
    : "当前预览尚未接入社区数据；真实路书搜索将在正式迁移后接入。";
}
document.querySelector("#search-form").addEventListener("submit", event => {
  event.preventDefault();
  render(input.value);
  document.querySelector("#stories").scrollIntoView({ block: "start", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth" });
});
reset.addEventListener("click", () => { input.value = ""; render(); input.focus(); });
const hero = document.querySelector(".hero-photo img");
function failedHero() { document.body.classList.add("image-failed"); hero.hidden = true; }
hero.addEventListener("error", failedHero);
if (hero.complete && !hero.naturalWidth) failedHero();
document.addEventListener("error", event => {
  if (event.target instanceof HTMLImageElement && !event.target.closest(".hero-photo")) event.target.hidden = true;
}, true);
renderStories();
