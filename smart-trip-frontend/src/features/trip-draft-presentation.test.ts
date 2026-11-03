import { describe, expect, it } from "vitest";
import {
  budgetSummary,
  dateLabel,
  dateTimeLabel,
  issueTarget,
  itemTypeLabel,
  money,
  userText,
  type EditableTrip,
} from "./trip-draft-presentation";
import type { TravelRequirement } from "./types";

function requirement(
  changes: Partial<TravelRequirement> = {},
): TravelRequirement {
  return {
    description: "",
    origin: "苏州",
    destinations: ["稻城亚丁"],
    days: 10,
    startDate: "2026-10-28",
    timeDescription: "",
    includesTravelDays: true,
    travelers: 6,
    companions: "",
    budget: 3000,
    budgetMax: 4500,
    budgetScope: "PER_PERSON",
    currency: "CNY",
    budgetIncludes: [],
    interests: [],
    pace: "",
    exertion: "",
    transport: "",
    accommodation: "",
    mustVisit: [],
    avoid: [],
    timeConstraints: "",
    notes: "",
    ...changes,
  };
}
function trip(
  days: number,
  costs: (number | "" | null)[] = [100],
): EditableTrip {
  return {
    title: "确定性预算测试",
    destination: "稻城亚丁",
    days: Array.from({ length: days }, (_, index) => ({
      dayIndex: index + 1,
      items: costs.map((cost, n) => ({
        itemName: "费用项目" + (n + 1),
        itemType: n === 0 ? "TRAFFIC" : n === 1 ? "FOOD" : "HOTEL",
        costEstimate: cost,
      })),
    })),
  };
}

describe("display-only travel wording", () => {
  it("translates identified technical explanations and enum labels without mutating the source", () => {
    const source = {
      summary:
        "部分 costEstimate 和 startTime 尚待确认。旅行节奏按 RELAXED 规划，预算口径为 PER_PERSON。",
    };
    const original = source.summary;
    const shown = userText(source.summary);
    expect(shown).toContain("活动费用");
    expect(shown).toContain("开始时间");
    expect(shown).toContain("轻松慢游");
    expect(shown).toContain("人均");
    expect(shown).not.toMatch(/costEstimate|startTime|RELAXED|PER_PERSON/);
    expect(source.summary).toBe(original);
    expect(userText("UNKNOWN")).toBe("待确认");
    expect(userText("部分costEstimate和startTime尚待确认。")).toBe(
      "部分活动费用和开始时间尚待确认。",
    );
    expect(userText("budgetScope=PER_PERSON")).toBe("预算口径=人均");
  });
  it("preserves place names, larger identifiers and ordinary prose instead of replacing arbitrary text", () => {
    for (const source of [
      "参观 PER_PERSON 艺术空间，之后打卡 startTime 咖啡馆。",
      "costEstimate咖啡馆尚待确认营业时间。",
      "部分 my_costEstimate 和 costEstimates 尚待确认。",
      "新都桥十里长廊，沿着山路留一段慢下来的时间。",
      "《TOTAL》是今天想带走的旅行读物。",
    ])
      expect(userText(source)).toBe(source);
    expect(
      userText(
        "部分 costEstimate 和 startTime 尚待确认；前往 costEstimate 咖啡馆。",
      ),
    ).toContain("前往 costEstimate 咖啡馆。");
    expect(userText("toString")).toBe("toString");
  });
  it("keeps raw structured model material out of readable descriptions", () => {
    expect(userText('{"costEstimate":100,"startTime":"09:00"}')).not.toContain(
      "costEstimate",
    );
    expect(userText("[沿湖慢走]，等风经过。")).toBe("[沿湖慢走]，等风经过。");
  });
});

describe("all-traveller draft budget", () => {
  it("sums an eight-day seven-person itinerary once, multiplying only the per-person budget ceiling", () => {
    const result = budgetSummary(
      trip(8, [800, 700, 2000]),
      requirement({ days: 8, travelers: 7 }),
    );
    expect(result.total).toBe(28000);
    expect(result.limit).toBe(31500);
    expect(result.remaining).toBe(3500);
    expect(result.over).toBe(0);
    expect(
      result.categories.find((entry) => entry.type === "TRAFFIC")?.total,
    ).toBe(6400);
    expect(result.highCosts[0]).toMatchObject({
      type: "HOTEL",
      amount: 2000,
      dayIndex: 1,
      itemIndex: 3,
    });
  });
  it("retains the ten-day six-person overrun against the confirmed 27000 ceiling", () => {
    const draft = trip(10, [3500]);
    draft.days[9]!.items[0]!.costEstimate = 3860;
    const result = budgetSummary(draft, requirement());
    expect(result.total).toBe(35360);
    expect(result.limit).toBe(27000);
    expect(result.over).toBe(8360);
    expect(result.remaining).toBe(-8360);
    expect(result.highCosts[0]).toMatchObject({
      dayIndex: 10,
      itemIndex: 1,
      amount: 3860,
    });
    expect(draft.days[9]!.items[0]!.costEstimate).toBe(3860);
  });
  it("keeps unknown costs distinct from explicit zero and cannot claim remaining budget", () => {
    const result = budgetSummary(
      trip(1, [0, "", null]),
      requirement({ budgetScope: "TOTAL", budgetMax: 100 }),
    );
    expect(result.total).toBeNull();
    expect(result.knownTotal).toBe(0);
    expect(result.unknownCount).toBe(2);
    expect(result.remaining).toBeNull();
    expect(result.over).toBeNull();
    expect(
      result.categories.find((entry) => entry.type === "TRAFFIC")?.total,
    ).toBe(0);
    expect(
      result.categories.find((entry) => entry.type === "FOOD")?.total,
    ).toBeNull();
    expect(
      budgetSummary(
        trip(1, [200, null]),
        requirement({ budgetScope: "TOTAL", budgetMax: 100 }),
      ).over,
    ).toBe(100);
    expect(budgetSummary(trip(0), requirement()).total).toBeNull();
  });
  it("calculates in cents and refuses an unconfirmed scope, missing group size or currency conversion", () => {
    expect(budgetSummary(trip(1, [0.1, 0.2, 1.005])).total).toBe(1.31);
    expect(
      budgetSummary(trip(1), requirement({ currency: "USD" })).limit,
    ).toBeNull();
    expect(
      budgetSummary(trip(1), requirement({ budgetScope: "UNKNOWN" }))
        .comparable,
    ).toBe(false);
    expect(
      budgetSummary(trip(1), requirement({ travelers: null })).limit,
    ).toBeNull();
    expect(money(null)).toBe("待确认");
  });
});

it("locates final one-based issue positions and formats dates without browser timezone shifts", () => {
  const draft = trip(2, [100, 50]);
  expect(
    issueTarget({ code: "BUDGET_EXCEEDED", message: "超预算" }, draft),
  ).toEqual({ kind: "budget" });
  expect(
    issueTarget(
      { code: "TIME_OVERLAP", dayIndex: 2, itemIndex: 1, message: "时间重叠" },
      draft,
    ),
  ).toEqual({ kind: "activity", dayIndex: 2, itemIndex: 1 });
  expect(
    issueTarget(
      { code: "DAY_EMPTY", dayIndex: 1, itemIndex: 99, message: "检查当天" },
      draft,
    ),
  ).toEqual({ kind: "day", dayIndex: 1 });
  expect(
    issueTarget({ code: "DAYS", dayIndex: 0, message: "检查行程" }, draft),
  ).toEqual({ kind: "trip" });
  expect(dateLabel("2026-10-28")).toBe("10月28日");
  expect(dateLabel("2026-02-30")).toBe("日期待定");
  expect(dateTimeLabel("2026-10-28T12:30:00Z")).not.toBe("时间待确认");
  expect(dateTimeLabel("not a time")).toBe("时间待确认");
  expect(itemTypeLabel("TRAFFIC")).toBe("交通");
  expect(itemTypeLabel("toString")).toBe("其他");
});
