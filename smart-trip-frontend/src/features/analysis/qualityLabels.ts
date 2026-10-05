export const dimensionLabels: Record<string, string> = {
  TIME: "时间安排",
  PACE: "行程节奏",
  MEAL_REST: "用餐与休息",
  BALANCE: "每日均衡",
};
export const loadLabels: Record<string, string> = {
  LIGHT: "轻松",
  MODERATE: "适中",
  TIGHT: "偏紧",
  OVERLOADED: "过载",
  UNKNOWN: "无法充分判断",
};
export const stateLabels: Record<string, string> = {
  READY: "可评价",
  UNKNOWN: "暂无法判断",
  NOT_APPLICABLE: "不适用",
  PARTIAL: "部分可用",
};
export function minutes(v: number | null | undefined) {
  return v == null ? "未能确定" : v + " 分钟";
}
