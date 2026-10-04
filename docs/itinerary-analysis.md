# 行程诊断与智能调整方案

交付整理：2026-10-05。基线 3caffbb，分支 feat/itinerary-analysis。采用已确认的六项选择：当前一天默认、可选多天；基础检查仍可用；先调整已有节点；约束随路书保存；整份确认应用；外部查询由用户点击触发。

## 产品流程

已保存路书 → 选择分析日期与步行/驾车 → 基础检查 → 按需补充天气/路线 → AI 生成提案 → 查看差异与依据 → 整份确认保存。查看者可以诊断，所有者和编辑者可以生成与应用提案。未保存时先保存，版本变化后重新分析。

## 第一版边界

- 只允许调整已有节点的开始时间、顺序、所选日期内的归属。不新增或删除节点、日期；不修改停留时长、费用、POI、名称、地址、备注、室内外标记或固定安排标记。
- environmentType 为 INDOOR / OUTDOOR / UNKNOWN，由用户确认；scheduleLocked 为智能调整时是否固定。旧数据默认 UNKNOWN / false；固定节点不能跨天或改开始时间。
- 本轮 AI、地图、天气开关继续关闭。真实产品可用基础检查，联动通过测试替身验证，不把固定响应当成真实供应商结果。

## 诊断与数据

检查时间重叠、顺序与开始时间不一致、缺少开始时间/时长。跨午夜只提示人工确认，不自动拆成两天。日天气只能说明整日风险；结合用户标记的户外活动提醒，不推断具体降雨小时。未知活动环境不猜测。

路线仅采用有效已测路段预计耗时；显式交通节点避免重复计入交通耗时。缺失地点、无预报、失败和部分路线均显示无法完整判断。调整后只复用同交通方式、相同起终点的已测路段，新路段待复核，不承诺更短路线或实时交通。

## 接口与实现

- POST /api/workbench/{tripId}/analyses：baseVersion、dayIds、mode。返回 report，包含问题、来源状态、revision 和 expiresAt。
- GET /api/workbench/{tripId}/analyses/{id}：重新读取有效报告。
- POST /api/workbench/{tripId}/analyses/{id}/sources：dayId、kind=WEATHER/ROUTE、refresh（可省略，默认 false）。同条件复用结果，显式 refresh 才重查；失败可重试。
- POST /api/ai/tasks：type=ADJUST、requestKey、tripId、analysisId。任务使用原队列、配额、SSE、幂等；GET 与 POST /{id}/apply 保持原接口。result 保持完整 Trip，新增 proposal 元数据。

分析会话绑定用户、路书版本、日期和方式；30 分钟失效，同一用户保留最新会话，服务端最多 200 份。重启或释放返回 410，版本变化返回 409。会话只保留诊断所需摘要与路段耗时，不保留供应商原始响应、路线几何或新增坐标。报告 revision 避免独立天气/路线响应互相覆盖。

模型只输出所选日期的 itemId 排列与开始时间，以及关联已有证据的理由。后端从原快照构建 Trip，校验所有节点完整、唯一、归属合法；拒绝固定安排变更、新增确定性时间冲突、未知字段及虚构证据 ID。由后端计算实际差异。

应用重新校验权限、版本及外部依据有效期；冲突保留提案，要求重新分析。没有变更时不提供应用按钮。成功保存复用差异持久化、历史记录、乐观锁，节点 ID 和记账关联保持。

## 迁移与兼容

V8 只增加 trip_item.environment_type、trip_item.schedule_locked、ai_task.proposal_meta_json。只迁移 smart_trip_demo / smart_trip_test。旧写请求未传新属性时保留已有值；旧历史快照缺少属性时使用默认值。广场克隆保留环境标记、清除固定安排。旧 GENERATE / MODIFY 与分享接口继续兼容。

## 验收与交付

见 [模块验收报告](itinerary-analysis-verification.md)。只保存本地提交，不推送。供应商在线能力和模型实际效果需要在免费权益确认后另行验收。
