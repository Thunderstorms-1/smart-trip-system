# V3 正式工作台迁移记录：Phase 2A

日期：2026-10-07。分支：`feat/ui-v3-production`。第五轮基线：`a41b1b7`；本轮起点 / 当前 HEAD：`1f59b85`（正式 Home 已迁移）。本记录描述当前本地未提交改动；没有部署站点。

## 范围与设计依据

本轮仅完成 Phase 2A：V3 工作台外壳、非地理规划画布、Day Rail、真实行程 Timeline、选中节点行内编辑，以及移动端可展开 / 收起时间轴。真实 Insight 属于 2B，完整 Inspector 属于 2C；2D、2E 也不在本轮完成范围。

根 `DESIGN.md` 继续作为设计权威，已批准的 `smart-trip-frontend/public/design-preview-v3/workbench.html` 继续作为构图参考。保留画布 → 浮动日期导航 / 时间轴 → 功能内容的层次，避免默认展开全部节点表单。当前所有第三方 provider 禁用，因此生产背景明确标识“非地理地图 / 不表示地理位置、真实道路或路线”；没有复制原型中的演示道路、路线、天气、POI 或评分，也不声称完成真实地图与节点联动。

右侧“这段旅程”展示当前路书的天数、节点数和预估费用汇总，并可进入已有行程检查工具。这是当前数据概览，不是 Phase 2B 的真实 Insight。

## 实际接入与保留行为

- Timeline 直接读取当前 `trip.days[dayIndex].items`，呈现时间、活动名称、类型、停留时长、预估费用与固定状态；缺失信息显示待填写，不生成假值。
- 仅选中节点展开原字段编辑。保留费用小数、小时小数、固定安排、POI 身份、备注和交通提示等数据；新增、删除、排序和跨日移动继续作用于同一行程草稿。持久化节点使用已有 ID，未保存节点采用仅前端维护的稳定身份；选择状态不进入保存 payload。
- 继续使用原 API、权限、登录会话、路由、保存与版本机制，保留冲突提示、草稿、显式重试和历史版本入口。未改后端。原地图天气、分析、旅行工具、成员与预算等功能仍由已有组件提供。
- 移动端重组成紧凑日期导航与可收起时间轴，收起后仍可见规划画布；节点关闭、Escape 和历史弹窗支持焦点回到触发入口。样式限定在工作台及其弹窗，避免客户端跳转后影响其他页面。

实际生产改动：`smart-trip-frontend/src/features/Editor.vue`、`Shell.vue`、`workbench-v3.css`，以及 `workbench/{DayNavigation,PlanEditor,NodeFields,TimelineNode,TimelinePanel,WorkbenchShell}.vue`。

测试改动：新增 `smart-trip-frontend/e2e/workbench-v3.spec.ts` 五项回归；六份已有 E2E（analysis、home-v3、provider、quality、round2、travel）适配显式节点选择或 Home 等待真实加载完成。

## 验证证据与限制

证据集中在 `.impeccable/review/phase2a/`：`verification.json`、`browser-check.json`、`detector.json`、`review.md`、8 张最终生产截图和 2 张已批准原型参考图。生产截图通过真实前后端读取已有演示路书 14，截图过程只读；新增 E2E 的 API fixture 仅用于测试，不进入生产数据。

| 验证 | 结果与实际覆盖时点 |
| --- | --- |
| 前端类型检查、生产构建 | 最终代码通过，包括删除装饰眉题及对应 CSS 后的版本 |
| 前端单元测试 | 53 项通过 |
| 全量前端 E2E | 31 项通过，发生于最终布局 / 装饰收尾之前，未声称全量在最后一次 CSS 清理后重跑 |
| 新增工作台 E2E | 布局修正后 5 项通过；覆盖字段保存与重载、草稿排序身份、跨日移动、删除、冲突重试、只读权限、禁用 provider、移动收起与焦点，以及跨路由样式隔离 |
| 后端现有测试 | 131 项，0 失败、0 错误；未改后端 |
| 最终真实浏览器截图 | 删除装饰眉题后重新采集并验证全部 8 张；1440、768、390、350、700 px 五种宽度无横向溢出、浏览器错误 |

后端测试原始报告：`D:/codex/mission/smarttrip-round5-build/smarttrip-build-24A1ACF5BAE0/output/surefire-reports`。

独立初评将受限 Phase 2A 判定为 `faithful`，处置为 `fix`，唯一实质问题是目的地上方的 P2 装饰眉题。已删除该元素及专属样式，保留非地理声明和无障碍描述。后续 Verdict Pass 确认此项 `resolved`，处置为 `ship`；该 verdict 仅覆盖这一修正，不等于整个项目功能验收、用户视觉批准或完整工作台完成。

## 现有系统摘要与未修复元数据

- 色彩沿用已批准工作台的冷浅色环境、白色工作面与深蓝灰正文 / 操作色；没有建立新的设计方向。
- 字体沿用 Inter 与中文后备字体；行程标题、时间轴标题、节点正文和辅助数据分工保持清晰。
- 日期导航、时间轴、当前节点编辑承担不同层级，非选中节点保持紧凑。
- 密集字段采用稳定可读的内容面；画布与浮动功能层保持空间关系。
- 移动端通过时间轴展开 / 收起重组工作空间，保留焦点与键盘操作。

Detector 仅报告两条 Inter 常见字体 warning；作为已批准的 incumbent 字体予以保留，不借此重新设计。预存元数据漂移：根 `DESIGN.md` 为项目既有原则格式，未采用 Impeccable 规范 YAML / 八章节结构，且 `.impeccable/design.json` 当前不存在。本轮是已批准系统的普通延伸，记录该情况，不改写根设计、创建 sidecar 或将已修复眉题固化为设计规则。`PRODUCT.md` 与静态 V3 原型也不在本次写入范围。
