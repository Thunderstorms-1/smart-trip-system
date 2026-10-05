import type { Capabilities } from "./types";
export type ExternalFeature = "ai" | "poi" | "route" | "weather" | "map";
const labels: Record<ExternalFeature, string> = {
  ai: "AI",
  poi: "地点搜索",
  route: "路线",
  weather: "天气",
  map: "地图",
};
export function capabilityEnabled(c: Capabilities, feature: ExternalFeature) {
  return c[feature] ?? c.geo;
}
export function capabilityNotice(c: Capabilities, feature: ExternalFeature) {
  if (capabilityEnabled(c, feature)) return "";
  const label = labels[feature];
  const state =
    c.status?.[feature] ??
    (feature === "ai" || feature === "map" ? undefined : c.status?.geo);
  switch (state) {
    case "QUOTA_UNVERIFIED":
      return label + "免费额度或接口权限尚未确认，当前不会调用外部服务。";
    case "KEY_MISSING":
      return label + "服务端 Key 尚未配置，当前不会调用外部服务。";
    case "BROWSER_KEY_MISSING":
      return "地图浏览器 Key 或安全配置尚未完成。";
    case "DISABLED":
      return label + "服务当前处于关闭状态。";
    default:
      return label + "服务暂未启用。";
  }
}
