import type {
  Day,
  Item,
  ItemType,
  Task,
  TravelRequirement,
  Trip,
} from "./types";

export type EditableItem = Omit<Item, "costEstimate" | "costTimeHours"> & {
  costEstimate: number | "" | null;
  costTimeHours?: number | "" | null;
};
export type EditableTrip = Omit<Trip, "days"> & {
  days: (Omit<Day, "items"> & { items: EditableItem[] })[];
};
export type DraftIssue = NonNullable<
  NonNullable<Task["generation"]>["issues"]
>[number];

const typeNames: Record<ItemType, string> = {
  SCENIC: "景点",
  FOOD: "餐饮",
  HOTEL: "住宿",
  TRAFFIC: "交通",
  OTHER: "其他",
};
const typeIcons: Record<ItemType, string> = {
  SCENIC: "pin",
  FOOD: "food",
  HOTEL: "pin",
  TRAFFIC: "route",
  OTHER: "clock",
};
export const activityTypes = (Object.keys(typeNames) as ItemType[]).map(
  (value) => ({ value, label: typeNames[value] }),
);
export function itemTypeLabel(type?: string): string {
  return type != null && Object.hasOwn(typeNames, type)
    ? typeNames[type as ItemType]
    : "其他";
}
export function itemTypeIcon(type?: string): string {
  return type != null && Object.hasOwn(typeIcons, type)
    ? typeIcons[type as ItemType]
    : "pin";
}

const technicalWords: Record<string, string> = {
  costEstimate: "活动费用",
  startTime: "开始时间",
  costTimeHours: "预计时长",
  itemType: "活动类型",
  itemName: "活动名称",
  totalBudget: "预计总费用",
  totalDays: "旅行天数",
  dayIndex: "旅行日期",
  dayTheme: "当天主题",
  daySummary: "当天说明",
  scheduleLocked: "固定时间",
  environmentType: "活动环境",
  aiTips: "旅行建议",
  transportTips: "交通说明",
  budgetScope: "预算口径",
  summary: "行程概要",
  travelers: "同行人数",
  budgetMax: "预算上限",
  RELAXED: "轻松慢游",
  BALANCED: "均衡安排",
  PACKED: "紧凑安排",
  PER_PERSON: "人均",
  TOTAL: "全体合计",
  UNKNOWN: "待确认",
  SCENIC: "景点",
  FOOD: "餐饮",
  HOTEL: "住宿",
  TRAFFIC: "交通",
  OTHER: "其他",
  INDOOR: "室内",
  OUTDOOR: "户外",
  CNY: "人民币",
  QUEUED: "等待处理",
  RUNNING: "正在处理",
  PREVIEW: "草稿预览",
  FAILED: "未能完成",
  APPLIED: "已保存为路书",
  SUCCEEDED: "已完成",
  TripWriteDTO: "路书内容",
  TripDetailVO: "路书内容",
  TravelRequirement: "旅行需求",
};
const identifiers = Object.keys(technicalWords)
  .sort((a, b) => b.length - a.length)
  .join("|");
// Unicode token boundaries protect place names such as startTime咖啡馆 and my_costEstimate.
const technicalToken = new RegExp(
  "(?<![\\p{L}\\p{N}_$])(" + identifiers + ")(?![\\p{L}\\p{N}_$])",
  "gu",
);
const explanationContext =
  /字段|参数|枚举|属性|格式|DTO|JSON|待确认|待核实|尚待|未填写|未提供|未设置|缺少|缺失|为空|空值|预估|估算|预算口径|旅行节奏|活动类型|活动环境|按.{0,16}节奏|采用.{0,16}口径/u;
const assignmentContext = new RegExp(
  "(?<![\\p{L}\\p{N}_$])(?:" +
    Object.keys(technicalWords)
      .filter((key) => /[a-z]/.test(key))
      .join("|") +
    ")\\s*(?:[:=：]|为|是)",
  "u",
);
const fieldNames = Object.keys(technicalWords)
  .filter((key) => /[a-z]/.test(key))
  .join("|");
const compactExplanation = new RegExp(
  "^(部分|有些|当前|其中)?((?:" +
    fieldNames +
    ")(?:[和与及、](?:" +
    fieldNames +
    "))*)(尚待确认|待确认|尚待核实|待核实|未填写|未提供|未设置|不明确|为空|缺失)$",
  "u",
);
const fieldToken = new RegExp(fieldNames, "g");
const placeContext = new RegExp(
  "(?:名为|叫做)\\s*(?:" +
    identifiers +
    ")|(?:" +
    identifiers +
    ")\\s*(?:咖啡馆|咖啡店|艺术空间|工作室|博物馆|书店|餐厅|酒店|民宿|公园|古镇)",
  "u",
);
const enumContext =
  /预算|口径|节奏|类型|环境|枚举|状态|模式|币种|budgetScope|itemType|environmentType|pace/u;

/** Display-only conversion of explicit technical explanations; never rewrites stored trip data. */
export function userText(value?: string | null): string {
  if (!value) return "";
  const exact = value.trim();
  if (Object.hasOwn(technicalWords, exact)) return technicalWords[exact]!;
  if (/^[{[]/.test(exact)) {
    try {
      const parsed: unknown = JSON.parse(exact);
      if (
        parsed &&
        typeof parsed === "object" &&
        Object.keys(parsed).some((key) => Object.hasOwn(technicalWords, key))
      )
        return "这段行程说明需要补充，请编辑后再确认。";
    } catch {
      /* Ordinary prose containing brackets remains unchanged. */
    }
  }
  return value
    .split(/([，,。；;！？!?\n]+)/u)
    .map((clause) => {
      if (
        (!explanationContext.test(clause) && !assignmentContext.test(clause)) ||
        placeContext.test(clause)
      )
        return clause;
      const compact = compactExplanation.exec(clause.trim());
      if (compact)
        return (
          (compact[1] ?? "") +
          compact[2]!.replace(fieldToken, (token) => technicalWords[token]!) +
          compact[3]
        );
      return clause
        .replace(technicalToken, (token) =>
          /^[A-Z_]+$/.test(token) && !enumContext.test(clause)
            ? token
            : (technicalWords[token] ?? token),
        )
        .replace(/(?:JSON|DTO)\s*(?:字段|属性|结构|数据)/gu, "行程信息");
    })
    .join("");
}

/** Costs are all-traveller totals. Round each explicit amount to cents before summing. */
function cents(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0)
    return null;
  if (value < 0.005) return 0;
  const match = /^(\d+)(?:\.(\d+))?$/.exec(String(value));
  if (!match) return null;
  const fraction = (match[2] ?? "").padEnd(3, "0");
  const result =
    Number(match[1]) * 100 +
    Number(fraction.slice(0, 2)) +
    (Number(fraction[2]) >= 5 ? 1 : 0);
  return Number.isSafeInteger(result) ? result : null;
}
export function money(value?: number | null, currency = "CNY"): string {
  if (value == null || !Number.isFinite(value)) return "待确认";
  try {
    return new Intl.NumberFormat("zh-CN", {
      style: "currency",
      currency,
      maximumFractionDigits: 2,
    }).format(value);
  } catch {
    return (
      new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(
        value,
      ) + (currency ? " " + currency : "（币种待确认）")
    );
  }
}
export function dateLabel(
  value?: string | null,
  fallback = "日期待定",
): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value ?? "");
  if (!match) return fallback;
  const date = new Date(value + "T00:00:00Z");
  if (
    !Number.isFinite(date.getTime()) ||
    date.toISOString().slice(0, 10) !== value
  )
    return fallback;
  return Number(match[2]) + "月" + Number(match[3]) + "日";
}

/** Exact expiry timestamps are displayed in the viewer's local time, including the clock. */
export function dateTimeLabel(
  value?: string | null,
  fallback = "时间待确认",
): string {
  if (!value || !/(?:Z|[+-]\d{2}:?\d{2})$/i.test(value)) return fallback;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return fallback;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

export interface DraftBudgetCategory {
  type: ItemType;
  label: string;
  total: number | null;
  knownTotal: number;
  unknownCount: number;
  count: number;
}
export interface DraftHighCost {
  dayIndex: number;
  itemIndex: number;
  name: string;
  type: ItemType;
  amount: number;
}
export interface DraftBudgetSummary {
  total: number | null;
  knownTotal: number;
  unknownCount: number;
  itemCount: number;
  limit: number | null;
  remaining: number | null;
  over: number | null;
  currency: "CNY";
  comparable: boolean;
  comparisonNote: string;
  categories: DraftBudgetCategory[];
  highCosts: DraftHighCost[];
}
export function budgetSummary(
  draft?: EditableTrip | Trip | null,
  requirement?: TravelRequirement | null,
): DraftBudgetSummary {
  const categories = activityTypes.map(({ value: type, label }) => ({
    type,
    label,
    cents: 0,
    unknownCount: 0,
    count: 0,
  }));
  const highCosts: DraftHighCost[] = [];
  let knownCents = 0,
    unknownCount = 0,
    itemCount = 0;
  for (const [dayIndex, day] of (draft?.days ?? []).entries()) {
    for (const [itemIndex, item] of day.items.entries()) {
      const type: ItemType = Object.hasOwn(typeNames, item.itemType)
        ? item.itemType
        : "OTHER";
      const category = categories.find((entry) => entry.type === type)!;
      const amount = cents(item.costEstimate);
      itemCount++;
      category.count++;
      if (amount == null) {
        unknownCount++;
        category.unknownCount++;
        continue;
      }
      knownCents += amount;
      category.cents += amount;
      if (amount > 0)
        highCosts.push({
          dayIndex: dayIndex + 1,
          itemIndex: itemIndex + 1,
          name: item.itemName,
          type,
          amount: amount / 100,
        });
    }
  }
  const budget = requirement?.budgetMax ?? requirement?.budget;
  let limitCents: number | null = null,
    comparisonNote = "尚未确认预算上限";
  if (budget != null) {
    if (requirement?.currency !== "CNY")
      comparisonNote = "预算币种尚不能与人民币活动费用直接比较，未自行换算汇率";
    else if (requirement.budgetScope === "TOTAL") limitCents = cents(budget);
    else if (requirement.budgetScope === "PER_PERSON") {
      if (
        requirement.travelers != null &&
        Number.isInteger(requirement.travelers) &&
        requirement.travelers > 0
      ) {
        const perPerson = cents(budget);
        if (
          perPerson != null &&
          Number.isSafeInteger(perPerson * requirement.travelers)
        )
          limitCents = perPerson * requirement.travelers;
      } else comparisonNote = "人均预算尚缺同行人数，未猜测全体预算";
    } else comparisonNote = "预算口径待确认，未将人均费用当成全体预算";
  }
  if (limitCents != null)
    comparisonNote = "活动费用均为全体同行人合计，仅人均预算上限按确认人数换算";
  const total = itemCount > 0 && unknownCount === 0 ? knownCents / 100 : null;
  return {
    total,
    knownTotal: knownCents / 100,
    unknownCount,
    itemCount,
    limit: limitCents == null ? null : limitCents / 100,
    remaining:
      limitCents != null && total != null
        ? (limitCents - knownCents) / 100
        : null,
    over:
      limitCents == null
        ? null
        : knownCents > limitCents
          ? (knownCents - limitCents) / 100
          : total != null
            ? 0
            : null,
    currency: "CNY",
    comparable: limitCents != null,
    comparisonNote,
    categories: categories.map((category) => ({
      type: category.type,
      label: category.label,
      total: category.unknownCount ? null : category.cents / 100,
      knownTotal: category.cents / 100,
      unknownCount: category.unknownCount,
      count: category.count,
    })),
    highCosts: highCosts
      .sort(
        (a, b) =>
          b.amount - a.amount ||
          a.dayIndex - b.dayIndex ||
          a.itemIndex - b.itemIndex,
      )
      .slice(0, 5),
  };
}

export function issueLocation(issue: DraftIssue): string {
  const parts: string[] = [];
  if (issue.dayIndex != null && issue.dayIndex > 0)
    parts.push("第 " + issue.dayIndex + " 天");
  if (issue.itemIndex != null && issue.itemIndex > 0)
    parts.push("第 " + issue.itemIndex + " 项活动");
  if (issue.relatedItemIndex != null && issue.relatedItemIndex > 0)
    parts.push("与第 " + issue.relatedItemIndex + " 项活动");
  return parts.join(" · ");
}
export interface DraftIssueTarget {
  kind: "budget" | "activity" | "day" | "trip";
  dayIndex?: number;
  itemIndex?: number;
}
export function issueTarget(
  issue: DraftIssue,
  draft?: EditableTrip | Trip,
): DraftIssueTarget {
  if (issue.code === "BUDGET_EXCEEDED" || issue.code === "COST_TOTAL")
    return { kind: "budget" };
  const day = issue.dayIndex;
  if (
    !Number.isInteger(day) ||
    day == null ||
    day < 1 ||
    (draft && day > draft.days.length)
  )
    return { kind: "trip" };
  const item = issue.itemIndex ?? issue.relatedItemIndex;
  if (
    Number.isInteger(item) &&
    item != null &&
    item > 0 &&
    (!draft || item <= (draft.days[day - 1]?.items.length ?? 0))
  )
    return { kind: "activity", dayIndex: day, itemIndex: item };
  return { kind: "day", dayIndex: day };
}
