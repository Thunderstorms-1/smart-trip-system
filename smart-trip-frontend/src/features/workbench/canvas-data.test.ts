import { describe, it, expect } from "vitest";
import {
  coordinate,
  canvasPoints,
  routePaths,
  measured,
  usableWeather,
} from "./canvas-data";
import type { Day, RouteData } from "../types";
const day: Day = {
  items: [
    {
      id: 1,
      itemName: "已关联",
      itemType: "SCENIC",
      costEstimate: 0,
      poiId: "real-reference",
      coordinateSystem: "GCJ02",
      longitude: 104,
      latitude: 30,
    },
    { id: 2, itemName: "只有名称", itemType: "SCENIC", costEstimate: 0 },
    {
      id: 3,
      itemName: "错误坐标系",
      itemType: "SCENIC",
      costEstimate: 0,
      poiId: "reference",
      coordinateSystem: "WGS84",
      longitude: 104,
      latitude: 30,
    },
  ],
};
describe("real canvas data boundaries (synthetic unit fixtures)", () => {
  it("rejects empty, nonfinite and out-of-range coordinates without inventing zero", () => {
    for (const v of [
      undefined,
      "",
      ",",
      "104,",
      "1,NaN",
      "181,30",
      "104,91",
      "1,2,3",
    ])
      expect(coordinate(v)).toBeUndefined();
    expect(coordinate("0,0")).toEqual([0, 0]);
  });
  it("only marks persisted IDs with confirmed GCJ02 references", () => {
    const values = canvasPoints(day);
    expect(values).toHaveLength(1);
    expect(values[0]!.item).toBe(day.items[0]);
    expect(
      canvasPoints({ items: [{ ...day.items[0]!, id: undefined }] }),
    ).toEqual([]);
  });
  it("uses resolved route points for matching real IDs; unresolved overrides stored positions", () => {
    const route = {
      points: [
        { itemId: 1, name: "a", resolved: false },
        { itemId: 2, name: "b", resolved: true, location: "105,31" },
        { itemId: 99, name: "stranger", resolved: true, location: "100,20" },
      ],
    } as RouteData;
    expect(canvasPoints(day, route).map((p) => p.item.id)).toEqual([2]);
  });
  it("never joins pins as routes and drops malformed or unknown endpoint paths", () => {
    const leg = {
      from: 1,
      to: 2,
      available: true,
      polylines: ["104,30;105,31", "104,30;bad", "104,30"],
    };
    expect(routePaths(leg, new Set([1, 2]))).toEqual([
      [
        [104, 30],
        [105, 31],
      ],
    ]);
    expect(routePaths(leg, new Set([1]))).toEqual([]);
    expect(routePaths({ ...leg, available: false }, new Set([1, 2]))).toEqual(
      [],
    );
  });
  it("keeps true zero but does not turn missing measurements into zero", () => {
    for (const v of [null, undefined, "", " ", "unknown", -1, Infinity])
      expect(measured(v)).toBeUndefined();
    expect(measured("0")).toBe(0);
    expect(measured("123.5")).toBe(123.5);
  });
  it("only displays a complete forecast for the requested date, including negative and zero temperatures", () => {
    const w = {
      available: true,
      date: "2026-10-07",
      city: "成都",
      dayWeather: "晴",
      low: "-2",
      high: "0",
    };
    expect(usableWeather(w, w.date)).toBe(true);
    expect(usableWeather(w, "2026-10-08")).toBe(false);
    expect(usableWeather({ ...w, low: "" }, w.date)).toBe(false);
    expect(usableWeather({ ...w, available: false }, w.date)).toBe(false);
  });
});
