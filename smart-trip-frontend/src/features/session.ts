import { defineStore } from "pinia";
import { ref } from "vue";
import { api } from "./api";
import type { Profile, Capabilities } from "./types";
export const useSession = defineStore("session", () => {
  const token = ref(localStorage.getItem("AUTH_TOKEN") || "");
  const profile = ref<Profile | null>(null);
  const capabilities = ref<Capabilities>({
    ai: false,
    geo: false,
    map: false,
    notice: "",
  });
  const capabilitiesLoading = ref(false),
    capabilitiesError = ref(""),
    capabilitiesLoadedAt = ref(0);
  let capabilityRequest: Promise<Capabilities> | undefined;
  async function refresh() {
    const ownerToken = token.value;
    if (ownerToken) {
      const value = await api<Profile>("/user/info");
      if (token.value === ownerToken) profile.value = value;
    }
  }
  async function loadCapabilities(force = false): Promise<Capabilities> {
    if (capabilityRequest) return capabilityRequest;
    if (
      !force &&
      !capabilitiesError.value &&
      Date.now() - capabilitiesLoadedAt.value < 30000
    )
      return capabilities.value;
    capabilitiesLoading.value = true;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    capabilityRequest = (async () => {
      try {
        const value = await api<Capabilities>(
          "/capabilities",
          "GET",
          undefined,
          controller.signal,
        );
        capabilities.value = value;
        capabilitiesLoadedAt.value = Date.now();
        capabilitiesError.value = "";
        return value;
      } catch (e) {
        capabilitiesError.value = controller.signal.aborted
          ? "检查 AI 配置超时，请确认后端服务已启动后重试。"
          : "无法读取服务能力，请确认后端服务与网络连接后重试。";
        throw new Error(capabilitiesError.value);
      } finally {
        clearTimeout(timeout);
        capabilitiesLoading.value = false;
        capabilityRequest = undefined;
      }
    })();
    return capabilityRequest;
  }
  function logout() {
    capabilitiesLoadedAt.value = 0;
    token.value = "";
    profile.value = null;
    localStorage.removeItem("AUTH_TOKEN");
    localStorage.removeItem("USER_INFO");
  }
  async function login(username: string, password: string) {
    const v = await api<{ token: string }>("/auth/login", "POST", {
      username,
      password,
    });
    capabilitiesLoadedAt.value = 0;
    token.value = v.token;
    localStorage.setItem("AUTH_TOKEN", v.token);
    await refresh();
  }
  return {
    token,
    profile,
    capabilities,
    capabilitiesLoading,
    capabilitiesError,
    capabilitiesLoadedAt,
    refresh,
    loadCapabilities,
    logout,
    login,
  };
});
