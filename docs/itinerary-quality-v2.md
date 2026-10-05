# 行程质量诊断 V2：规则与接口

日期：2026-10-05。实现：feat/itinerary-quality-v2 / 968e38b。规则版本 quality-v1，报告 schemaVersion=1；新 ADJUST 输入和质量对比 schemaVersion=2。

## 事实计算与评分

报告覆盖已保存 Trip 的全部日期；用户选中日期只限定 AI 可修改范围。规则引擎不访问供应商，不修改输入。路线、天气未查询时显示未知或不可用，不纳入四项核心评分；实际取得的有效来源用于增强诊断。

| 分项 | 权重 | 可评价条件与主要扣分 |
| --- | --- | --- |
| TIME 时间安排 | 40 | 有效时间齐全；每对重叠扣20，上限60；时序倒置每项10，上限30 |
| PACE 行程节奏 | 25 | 有效时间齐全且无基础 ERROR；活动超过480/600分钟扣15/30；跨度超过660/780分钟并满足活动时长条件扣10/20；密集景点规则扣10 |
| MEAL_REST 餐休 | 20 | 有效时间齐全且无基础 ERROR；连续活动超过180/300分钟扣10/20；午餐/晚餐窗口风险各扣10 |
| BALANCE 每日均衡 | 15 | 单日 NOT_APPLICABLE；多日均可评价时比较占用活动时长；最大差至少240且最忙日超过480分钟扣20，至少360且最忙日超过600分钟扣35 |

前三项先逐日计算，再等权平均。分数取整并限制0–100；总分按可用权重归一化。不适用分项不参与；任何必要分项 UNKNOWN 则总分为 null、状态 INSUFFICIENT。空日、只有酒店、缺时间、零时长和跨午夜不获100分。TIME 可在完整但冲突时评价，其余依赖时间轴的分项保留未知。

时间采用本地单日半开区间，分钟由十进制小时换算并 HALF_UP 取整。计划活动时长与区间并集占用时长分别报告，重叠不重复计时。HOTEL 不作休息事实；OTHER 名称含“休息”也不猜类型。FOOD 的有效不重叠安排可以证明窗口用餐，连续活动只有足够的间隔或不重叠的30分钟餐饮段才能中断。TRAFFIC 单列，不与路线预计时长重复计入负荷。

默认午餐11:30–14:00、晚餐17:30–20:00：已知活动占用至少90分钟，没有有效20分钟餐饮安排，又没有30分钟未分配窗口，提示预留餐休。只是计划启发式提示，不声称用户没有吃饭。景点密集须同时满足至少6景点、活动超过360分钟、未分配时间不足跨度15%；不是仅按景点数量判坏。活动跨度的提示也同时检查占用时长，页面展示复合依据。

相邻节点计划间隔过少是 INFO、不扣分、不猜转场。只有匹配当前同日邻接节点的有效路线，才计算扣除转场后的余量；换序/跨日后不能沿用旧腿。天气只使用确切匹配日的预报，不推断小时雨量、开闭馆或真实交通。Coverage 明示全行程预期段/日和已知数量，0/0不会显示全部来源就绪。

## 复用的接口

- POST /api/workbench/{tripId}/analyses：沿用 baseVersion、dayIds、mode，原 Report 新增可选 quality。
- GET /api/workbench/{tripId}/analyses/{id} 和 POST .../sources：保留会话、权限、revision、版本和30分钟有效期。
- POST /api/ai/tasks：继续 type=ADJUST，不新增任务类型；持久化规则版本、完整 QualityPolicy、来源快照和原 Trip。
- Proposal 新增可选 qualityComparison，包含 policy、before、after、resolved、remaining、added。旧V1输入/旧预览没有该字段时保持旧行为。
- 原应用接口继续整份确认保存，现有历史和乐观锁生效，没有数据库迁移。

QualityReport 含 scope=TRIP、baseVersion、dayIds、四维度、逐日 metrics/coverage/load、稳定ID的 findings、全行程 basicIssues、external。Dimension 的 READY 有真实整数 score；UNKNOWN/NOT_APPLICABLE 的 score=null。Finding 包含 ruleId、severity、dayIds、itemIds、evidence(actual/threshold/unit)、penalty、action；MANUAL 只提供手动建议。

## AI 提案与确认

评分只由规则计算，模型输出仍只允许 days + reasons，不接受自报分数。只给 AI 选中日期的可行动依据，跨日均衡必须相关日期全选；范围外问题和 MANUAL 问题不能驱动自动增删节点。

保持原节点恰好一次、ID/类型/地点引用/费用/时长/备注/环境等不可改字段；固定安排不可移动。使用同一规则与来源分别计算前后报告，不新增确定性 ERROR、不降低覆盖、不新增过载、前后可比较维度不能变差，且须有已知分项改善、减少 ERROR 或补全计划时间。其他未选日缺信息时，可比较的已知日程仍能证明改善，不把全行程 null 当0分。

无变化提案可以显示剩余问题，不能确认保存。有变化但无可验证改善返回422。新V2提案统一受30分钟期限限制，旧V1无外部来源的原有效期行为保留。确认时先做权限/版本检查，再从持久化输入重建、校验候选；篡改费用等拒绝且不写路书。结构对比接受语义相同的JSON数值，避免1和1.0导致误拒。

## 页面与代码

后端 service/quality 下的 TimelineMetrics、QualityPolicy、ItineraryQualityEngine、QualityReport、QualityComparison；ItineraryAnalysisService 接入原报告和AI输入；AiTaskStore 确认时再验证。

前端 analysis 下的 QualityOverview、DayQualityCards、QualityFindings、QualityComparison 与 qualityLabels；ItineraryAnalysis 组合原页面。Editor 按真实 Day/Item ID定位卡片，重复名称不串节点，小屏单列。既有查询取消、版本失效和来源竞态逻辑继续复用，未保存先提示保存。

## 当前边界

分数是解释型产品启发式，不是科学评估、个性化体力判断或真实可行性保证。没有餐饮节点只能手动添加；不提供新地点推荐、营业时间或小时天气。本机AI和高德常用开关关闭；真实AI已通过三例临时验收，高德在线验收继续作为收尾TODO。
