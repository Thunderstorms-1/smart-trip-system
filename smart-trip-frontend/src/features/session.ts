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
  async function refresh() {
    if (token.value) profile.value = await api<Profile>("/user/info");
  }
  async function loadCapabilities() {
    capabilities.value = await api<Capabilities>("/capabilities");
  }
  function logout() {
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
    token.value = v.token;
    localStorage.setItem("AUTH_TOKEN", v.token);
    await refresh();
  }
  return {
    token,
    profile,
    capabilities,
    refresh,
    loadCapabilities,
    logout,
    login,
  };
});
