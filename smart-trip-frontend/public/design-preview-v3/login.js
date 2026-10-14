"use strict";

// This isolated visual prototype never calls an API or persists form values.
const form = document.querySelector("#auth-form");
const fields = document.querySelector("#auth-fields");
const submitButton = document.querySelector("#auth-submit");
const modeSwitch = document.querySelector("#mode-switch");
const passwordInput = document.querySelector("#password");
const passwordToggle = document.querySelector(".password-toggle");
const errorMessage = document.querySelector("#auth-error");
const resultMessage = document.querySelector("#preview-result");
const authPanel = document.querySelector("#auth-panel");
let register = false;
let busy = false;
let previewTimer;

function setMode(next) {
  if (busy) return;
  register = next;
  authPanel.classList.toggle("is-register", register);
  document.querySelector("#auth-title").textContent = register ? "创建你的账号" : "欢迎回来";
  document.querySelector("#auth-intro").textContent = register ? "拥有自己的旅行空间。" : "登录，继续你的旅行计划。";
  document.querySelector("#auth-legend").textContent = register ? "注册账号" : "登录账号";
  document.querySelector("#nickname-field").hidden = !register;
  document.querySelector("#username-hint").hidden = !register;
  document.querySelector("#password-hint").hidden = !register;
  document.querySelector("#switch-prompt").textContent = register ? "已经有账号？" : "还没有账号？";
  modeSwitch.textContent = register ? "返回登录" : "创建账号";
  submitButton.querySelector("span").textContent = register ? "注册并登录" : "登录";
  passwordInput.autocomplete = register ? "new-password" : "current-password";
  errorMessage.hidden = true;
  resultMessage.hidden = true;
}

function setBusy(next) {
  busy = next;
  fields.disabled = busy;
  modeSwitch.disabled = busy;
  form.setAttribute("aria-busy", String(busy));
  submitButton.querySelector("span").textContent = busy ? "正在处理…" : register ? "注册并登录" : "登录";
}

modeSwitch.addEventListener("click", () => setMode(!register));
passwordToggle.addEventListener("click", () => {
  const showing = passwordInput.type === "password";
  passwordInput.type = showing ? "text" : "password";
  passwordToggle.setAttribute("aria-label", showing ? "隐藏密码" : "显示密码");
  passwordToggle.setAttribute("aria-pressed", String(showing));
});

function previewSubmit() {
  if (busy) return;
  form.requestSubmit();
}
submitButton.addEventListener("click", previewSubmit);
form.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && event.target.tagName === "INPUT") {
    event.preventDefault();
    previewSubmit();
  }
});
form.addEventListener("submit", (event) => {
  event.preventDefault();
  if (busy) return;
  errorMessage.hidden = true;
  resultMessage.hidden = true;
  setBusy(true);
  previewTimer = window.setTimeout(() => {
    setBusy(false);
    resultMessage.textContent = "这是设计预览，未发送登录或注册请求。";
    resultMessage.hidden = false;
    submitButton.focus({ preventScroll: true });
  }, 550);
});

const landscape = document.querySelector(".landscape img");
function imageFailed() {
  document.body.classList.add("image-failed");
  landscape.hidden = true;
  document.querySelector("#image-caption").textContent = "旅行概念影像暂不可用";
}
landscape.addEventListener("error", imageFailed);
if (landscape.complete && landscape.naturalWidth === 0) imageFailed();
window.addEventListener("pagehide", () => window.clearTimeout(previewTimer));

const params = new URLSearchParams(window.location.search);
if (params.get("mode") === "register") setMode(true);
if (params.get("state") === "error") {
  errorMessage.textContent = "用户名或密码不正确，请检查后重试。";
  errorMessage.hidden = false;
} else if (params.get("state") === "busy") {
  setBusy(true);
}
