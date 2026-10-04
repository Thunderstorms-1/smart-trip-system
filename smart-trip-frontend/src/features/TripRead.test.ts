import { describe, it, expect } from "vitest";
import { createSSRApp } from "vue";
import { renderToString } from "@vue/server-renderer";
import TripRead from "./TripRead.vue";
import type { Trip } from "./types";
describe("Public trip presentation", () => {
  it("labels estimates and safely renders user notes", async () => {
    const trip: Trip = {
      title: "旅行",
      destination: "成都",
      totalBudget: 30,
      days: [
        {
          items: [
            {
              itemType: "SCENIC",
              itemName: "<script>alert(1)</script>",
              costEstimate: 30,
            },
          ],
        },
      ],
    };
    const html = await renderToString(createSSRApp(TripRead, { trip }));
    expect(html).toContain("预估");
    expect(html).toContain("日期待定");
    expect(html).toContain("&lt;script&gt;");
    expect(html).not.toContain("<script>alert");
    expect(html).not.toContain("4.8");
  });
});
