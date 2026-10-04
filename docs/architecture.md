# 智游伴路：业务与架构

## 模块联动

~~~mermaid
flowchart LR
  P[个人资料与旅行偏好] --> A[AI任务/手动创建]
  A --> T[私密路书 Trip/Day/Item]
  T --> G[真实地点引用/路线/天气]
  T --> E[预估费用与实际记账]
  T --> R[版本记录与恢复]
  T --> S[显式发布公开快照]
  T --> H[独立可撤销分享快照]
  S --> I[点赞/收藏/评论]
  S --> C[克隆为新的私密路书]
  C --> T
~~~

前端 session Pinia store 管理身份/资料和能力开关；features/types.ts 描述跨页面数据。api.ts 是新页面唯一请求边界，页面动作有加载、失败提示及重复点击保护。编辑器只向 WorkbenchService 保存一次完整路书树，日期、排序、预算与版本在后端同一事务处理。

保留MyBatis基础实体查询；新增领域事务使用JdbcTemplate和明确SQL。混合访问时 WorkbenchService.load 清理MyBatis本地缓存，避免同一事务内返回旧预算/版本。未来可进一步统一Repository层，当前没有把事务期间的缓存一致性留给页面猜测。

## 主要服务

| 服务 | 责任 |
| --- | --- |
| ResourceAccess | 所有者、编辑者、只读成员；Trip/Day/Item归属 |
| WorkbenchService | 路书树校验、稳定ID、日期/排序、预算、版本与恢复 |
| TravelToolsService | 实际账单CRUD、请求幂等、费用预填来源、清单CRUD |
| CommunityService | 公开快照、互动记录、评论、克隆、分享摘要/撤销 |
| MembershipService | 随机邀请、过期/撤销、成员角色与移除 |
| AiTaskService / AiTaskStore | 排队/锁/任务状态、事务落库、配额补偿、确认应用 |
| AiProvider | 唯一上游AI实现，SSE组帧、终止/长度校验、免费开关 |
| GeoService | 高德POI候选、GCJ02路线、近期预报，真实不可用状态 |
| DatabaseSafety | Flyway运行前拒绝原数据库 |

## 数据关系与不变量

Trip由用户所有，Day属于Trip，Item属于Day和同一个Trip。完整保存先检查所有已存在ID确属当前路书，再写树；节点跨天移动保留ID。修改不会接受作者、归属或删除标记。删除的节点和日期保留软删除记录，新建和排序通过实际行程数组决定。

每次完整保存读取/锁定Trip，检查version，保存旧快照到trip_revision，再更新版本与树。恢复旧版本也走完整保存，先保存当前版本。版本历史保存在数据库，界面展示最近50条。费用按人民币两位小数存储；预算是节点预估之和，实际支出是独立账单之和。

公开作品通过square_post保存不可变视图；只有显式发布/更新操作替换快照。公开作者数据来自sys_user的公开投影，不包括邮箱、登录名、密码或令牌版本。private Trip API不会因为路书已发布就允许陌生人读取。clone只能读有效公开快照，新ID、作者与私密状态通过create统一产生。

post_interaction以(user_id,post_id,action_type)为主键；设置“点赞/取消点赞”状态可重复调用。计数从真实关系表汇总，避免写死like_count。评论仅能由作者删除。trip_share存独立snapshot_json和随机token摘要，公开作品下架不撤销分享，整份路书删除会撤销分享。

## AI事务边界

~~~mermaid
sequenceDiagram
  participant U as 游客
  participant Q as AI任务服务
  participant M as 免费确认后的模型
  participant D as 数据库
  U->>Q: requestKey + 规划要求
  Q->>D: QUEUED (唯一请求标识)
  Q->>D: RUNNING + 预扣应用配额
  Q->>M: 外部流式调用（无数据库长事务）
  M-->>Q: 完整JSON + DONE
  alt 生成
    Q->>D: 校验后一次性保存私密草稿 + SUCCEEDED
  else 修改
    Q->>D: 预览 + baseVersion
    Q-->>U: 当前/建议安排
    U->>Q: 确认
    Q->>D: 版本检查 + 历史记录 + 保存 + APPLIED
  end
~~~

网络错误、截断、解析/结构校验失败时没有部分行程落库，并通过事务退回预扣应用配额。应用配额不是供应商免费余额的替代品，供应商开关必须先核实。任务状态通过具名SSE事件输出jobId；前端使用TextDecoder+帧缓冲处理跨块文本，连接失败可查持久任务状态。修改提案不会在确认前更新路书。

## 地图与天气

没有位置的节点保留“待确认”，没有路线的路段保留“暂无路线”，不补城市圆形散点或直线。当日点序依据路书，步行/驾车逐段请求实际polyline、距离和时间。查询返回tripVersion；前端在日程/方式/版本/未保存状态变化时使旧结果失效，过期响应不更新当前界面。

POI由用户核对候选后关联ID，运行时再从供应商读取坐标；不保存整份供应商响应、不建立全量POI缓存。使用高德坐标与高德底图。天气按实际预报日期匹配，日期超限显示未知，天气调整仍走AI预览/确认。当前没有启用真实供应商凭证，适配器契约测试不代表在线服务已验收。

## 验证与部署边界

JWT密钥来自本机配置/环境，BCrypt存储密码，tokenVersion使改密/全设备退出后的旧令牌失效。头像只接受实际PNG/JPEG，检查尺寸后重编码为256px PNG，随机文件名，不采用用户上传路径。HTTP错误使用401/403/404/409等真实状态。

独立Flyway迁移可复现演示/测试库；原库从未迁移。当前按单实例本地演示设计。公开部署还需要HTTPS、外部服务免费授权确认、数据库备份与历史凭证处理，以及实际线上运维方案；本轮未部署公网或推送Git远端。
