(() => {
  const root = document.querySelector(".glass-preview");
  const workbench = root.querySelector(".workbench-v3");
  const context = root.querySelector(".wb-context-region");
  const geo = root.querySelector(".wb-geo-controls");
  const inspector = root.querySelector("#glass-inspector");
  const inspectorHost = root.querySelector("#workbench-inspector-host");
  const detail = root.querySelector("#glass-detail");
  const toast = root.querySelector(".glass-preview-toast");
  let toastTimer,
    trigger,
    zoom = 1;
  function notice(text) {
    toast.textContent = text;
    toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toast.hidden = true), 3200);
  }
  function closeInspector() {
    if (inspector.open) inspector.close();
    inspectorHost.style.display = "none";
    workbench.classList.remove("inspector-open");
    if (!workbench.classList.contains("geo-open")) context.style.display = "";
    document.body.style.overflow = "";
    trigger?.focus();
  }
  function mode(name) {
    closeInspector();
    workbench.classList.remove("geo-open", "tool-open", "insight-open");
    geo.style.display = "none";
    context.style.display = "";
    root
      .querySelectorAll("[data-wb-tab]")
      .forEach((b) => b.classList.toggle("active", b.dataset.wbTab === name));
    if (name === "map") {
      workbench.classList.add("geo-open");
      geo.style.display = "";
      context.style.display = "none";
      geo.focus();
    } else if (name !== "plan") {
      showDetail(
        name === "analysis"
          ? "行程诊断示例"
          : name === "tools"
            ? "记账与行李示例"
            : name === "members"
              ? "同行成员示例"
              : "路书预览示例",
        "<p>这是工作台玻璃材质预览。此处仅展示浮窗与清晰阅读层，不连接正式业务数据。</p>",
      );
    }
  }
  function showDetail(title, html) {
    root.querySelector("#glass-detail-title").textContent = title;
    root.querySelector("#glass-detail-body").innerHTML = html;
    detail.showModal();
  }
  root
    .querySelectorAll("[data-wb-tab]")
    .forEach((b) => b.addEventListener("click", () => mode(b.dataset.wbTab)));
  root.querySelector(".wb-insight-toggle").addEventListener("click", () => {
    closeInspector();
    geo.style.display = "none";
    workbench.classList.remove("geo-open");
    workbench.classList.toggle("insight-open");
    context.style.display = "";
  });
  root
    .querySelector(".wb-geo-controls header button")
    .addEventListener("click", () => mode("plan"));
  root.querySelectorAll(".wb-day-button").forEach((button, index) =>
    button.addEventListener("click", () => {
      root
        .querySelectorAll(".wb-day-button")
        .forEach((b) => b.classList.toggle("active", b === button));
      root.querySelector(".wb-timeline-heading p").textContent =
        `第 ${index + 1} 天 · 演示安排 · 2026-10-${20 + index}`;
      notice(`已切换第 ${index + 1} 天的预览状态，行程条目为演示内容。`);
    }),
  );
  root.querySelectorAll(".wb-node-select").forEach((button) =>
    button.addEventListener("click", () => {
      trigger = button;
      mode("plan");
      inspectorHost.style.display = "";
      workbench.classList.add("inspector-open");
      context.style.display = "none";
      root.querySelector("#glass-node-name").value =
        button.querySelector("strong")?.textContent || "演示安排";
      if (matchMedia("(max-width:700px)").matches) {
        inspector.showModal();
        document.body.style.overflow = "hidden";
      } else {
        inspector.show();
      }
      root.querySelector("#glass-node-name").focus();
    }),
  );
  root
    .querySelectorAll("[data-close-inspector]")
    .forEach((b) => b.addEventListener("click", closeInspector));
  inspector.addEventListener("cancel", (e) => {
    e.preventDefault();
    closeInspector();
  });
  inspector.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      closeInspector();
    }
  });
  root
    .querySelectorAll("[data-close-detail]")
    .forEach((b) => b.addEventListener("click", () => detail.close()));
  root.querySelectorAll("[data-preview-action]").forEach((b) =>
    b.addEventListener("click", () => {
      if (b.dataset.previewAction === "weather") return mode("map");
      if (b.dataset.previewAction === "ai")
        showDetail(
          "AI 建议 · 演示差异",
          "<h3>给行程留一点缓冲</h3><p>这是建议样式示例，未调用 AI。</p><ul><li>原安排：午餐后立即出发。</li><li>建议示例：预留 20 分钟休息，再开始下一段行程。</li></ul><p>此预览不应用建议，也不保存路书。</p>",
        );
      else
        showDetail(
          "行程诊断 · 演示",
          "<p>时间安排 82 / 100、每日节奏 78 / 100 为预览示例。</p><p>“待补充”和“不适用”保留独立状态，不显示为真实 0 分。</p>",
        );
    }),
  );
  root.querySelectorAll(".wb-toolbar > button").forEach((b) =>
    b.addEventListener("click", () => {
      if (b.textContent.includes("诊断")) return mode("analysis");
      if (b.textContent.includes("AI"))
        return root.querySelector('[data-preview-action="ai"]').click();
      notice(
        b.textContent.includes("保存")
          ? "独立预览不保存数据。"
          : "此处仅展示历史版本浮窗的视觉风格。",
      );
    }),
  );
  root.querySelectorAll(".wb-more-content button").forEach((b) =>
    b.addEventListener("click", () => {
      root.querySelector(".wb-more-actions").open = false;
      notice("独立预览：" + b.textContent.trim() + "不会执行正式操作。");
    }),
  );
  root
    .querySelectorAll(".wb-geo-actions button")
    .forEach((b) =>
      b.addEventListener("click", () =>
        notice("查询样式演示：不会请求路线、天气或 AI 服务。"),
      ),
    );
  root
    .querySelectorAll(".wb-add-day,.wb-add-node")
    .forEach((b) =>
      b.addEventListener("click", () => notice("独立材质预览不新增正式安排。")),
    );
  root
    .querySelector(".account button")
    .addEventListener("click", () => notice("当前为独立预览，没有登录会话。"));
  root.querySelector(".account a").addEventListener("click", (e) => {
    e.preventDefault();
    notice("独立预览 · 旅行者身份为示例。");
  });
  root.querySelectorAll("[data-canvas-mode]").forEach((b) =>
    b.addEventListener("click", () => {
      root.querySelectorAll("[data-canvas-mode]").forEach((x) => {
        x.classList.toggle("active", x === b);
        x.setAttribute("aria-pressed", String(x === b));
      });
      workbench.classList.toggle(
        "glass-list-mode",
        b.dataset.canvasMode === "list",
      );
      root
        .querySelector(".wb-timeline-panel")
        .scrollIntoView({ block: "nearest" });
      notice(
        b.dataset.canvasMode === "list"
          ? "当前列表展示演示安排。"
          : "环境画布仅用于查看玻璃材质，不是地理地图。",
      );
    }),
  );
  root
    .querySelector(".glass-place-filter select")
    .addEventListener("change", (e) => {
      root.querySelectorAll(".wb-node-select").forEach((b) => {
        const text = b.textContent;
        b.closest(".wb-timeline-node").hidden =
          e.target.value === "FOOD"
            ? !text.includes("美食")
            : e.target.value === "SCENIC"
              ? !text.includes("景点")
              : false;
      });
    });
  root.querySelectorAll("[data-preview-zoom]").forEach((b) =>
    b.addEventListener("click", () => {
      zoom =
        b.dataset.previewZoom === "0"
          ? 1
          : Math.max(
              0.85,
              Math.min(1.2, zoom + Number(b.dataset.previewZoom) * 0.05),
            );
      root.querySelector(".wb-canvas-title").style.scale = zoom;
    }),
  );
  root.querySelector(".wb-drawer-toggle").addEventListener("click", () => {
    const expanded = workbench.classList.toggle("timeline-expanded");
    root
      .querySelector(".wb-timeline-content")
      .classList.toggle("is-collapsed", !expanded);
    const button = root.querySelector(".wb-drawer-toggle");
    const label = [...button.childNodes].find(
      (node) => node.nodeType === Node.TEXT_NODE,
    );
    if (label) label.textContent = expanded ? "收起时间轴" : "展开时间轴";
    button.setAttribute("aria-expanded", String(expanded));
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !detail.open) {
      if (inspector.open) closeInspector();
      else if (workbench.classList.contains("geo-open")) mode("plan");
      else {
        workbench.classList.remove("insight-open");
        root.querySelector(".wb-more-actions").open = false;
      }
    }
  });
})();
