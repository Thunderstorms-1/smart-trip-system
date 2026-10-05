# SmartTrip 智游伴路

面向游客的旅行路书产品，使用 Vue 3 / Pinia / TypeScript、Spring Boot 4、MyBatis / JDBC、MySQL 和 Redis。当前版本完成个人资料、私密路书编辑、版本恢复、记账、行李清单、同行邀请、公开快照、点赞、收藏、评论和克隆的业务闭环。

AI、地图与天气已具备真实供应商适配器和不可用状态，当前完成分项控制、错误处理与离线验收。AI 账号额度保护已由用户确认，但在线探测受本机外网限制尚未验收；高德 Key/权限未就绪，全部默认关闭。固定响应只存在于测试中，页面不会把它们展示为真实 AI 或气象结果。

## 在这台电脑上启动

要求：PowerShell 7、Java 17+（已验证 Java 21）、Maven 3.9+、Node 22.18+ 或 24.12+（已验证24.14）、MySQL 8、Redis。浏览器测试使用已安装的 Chrome。

1. 原始数据库是 smart_trip，当前应用只允许连接 smart_trip_demo / smart_trip_test。独立库已经建立，启动时 Flyway 自动迁移；迁移前的 DatabaseSafety 会拒绝原库。
2. 本机私有配置已经保存在 .local/application-local.yml，并被 Git 忽略。不要覆盖它，也不要把 .local 目录上传。换电脑时将 application-local.example.yml 复制到该位置，填写本机数据库账号与随机 JWT 密钥。样例不含真实密钥。
3. 前端依赖只在 smart-trip-frontend 安装：npm ci。项目根目录用于启动脚本，不再管理重复前端依赖。
4. 当前 Windows 的中文路径在部分 Maven 编译场景会导致测试类路径错误。Build-Backend.ps1 使用英文路径的目录联接和独立输出目录，保留原代码位置；默认构建目录是 D:/codex/mission/smarttrip-build，可通过 -BuildWorkspace 指定其他英文路径。

在项目根目录分别运行：

~~~powershell
# 构建后端；后端运行时请先停止它，再覆盖 jar
./scripts/Build-Backend.ps1

# 启动后端，默认8080
./scripts/Start-Backend.ps1

# 另开终端：本机已有Redis时仅需启动一次
./scripts/Start-Redis.ps1 -RedisExecutable 'D:/redis/Redis-8.10.2-Windows-x64-msys2/redis-server.exe'

# 另开终端：前端开发服务
./scripts/Start-Frontend.ps1
~~~

默认访问 http://127.0.0.1:5173 。启动脚本会识别同项目已有服务并提示复用；其他程序占用端口时会说明问题，不会终止它。需要另一个端口可执行 `./scripts/Start-Frontend.ps1 -Port 5174` 并访问对应地址。前端代理 /api 到 localhost:8080，使用同源请求。Redis启动脚本只监听本机，采用无持久化的独立开发实例，不读取原Redis数据。

本轮构建使用隔离的 Maven 缓存；可用 Build-Backend.ps1 的 -MavenRepository 参数选择已有缓存目录。项目提供独立空 settings.xml，不改系统 Maven 的 alimaven 配置。首次缺少依赖会从 Maven Central 下载。Windows构建脚本需要PowerShell 7。

## 演示账号和数据

本机演示账号：demo_traveler / demo_companion，密码均为 DemoTrip2026!。这两个账号只用于 smart_trip_demo。

演示路书是明确标注的手动示例，没有虚构路线、评分或实时天气。复现时先启动后端，再运行：

~~~powershell
$env:SMARTTRIP_DEMO_PASSWORD='你为演示账号设置的8位以上密码'
node scripts/seed-demo.mjs
Remove-Item Env:SMARTTRIP_DEMO_PASSWORD
~~~

脚本通过真实接口注册、填写资料、创建私密路书、发布快照和添加基础行李清单；同名演示路书已存在时跳过。不会导入或修改原库。

## 验证

~~~powershell
# 真实MySQL测试库 + Redis + 本地固定AI响应，不访问外部供应商
./scripts/Build-Backend.ps1 -Integration

# 前端
cd smart-trip-frontend
npm run typecheck
npm run test
npm run build
npm run e2e

# 后端正在运行时的业务接口烟测：会在演示库创建验收账号/路书
cd ..
node scripts/api-smoke.cjs
~~~

普通 mvn test 默认跳过依赖本机数据库的集成测试。显式集成测试固定连接 smart_trip_test，绝不连接 smart_trip。Playwright需要后端运行，会自动启动或复用5173前端服务；使用其他端口时先设置 `$env:SMARTTRIP_BASE_URL='http://127.0.0.1:5174'`，Chrome的 channel 可在 playwright.config.ts 按本机情况调整。

## 如何启用免费外部能力

目前没有在线调用已获验证的供应商Key，不能把“适配器完成”理解为“本机已接通”。保持 enabled 和 free-quota-verified 为 false，直到账号后台确认免费额度、有效期、模型/接口权限和余额耗尽停用设置。

- AI：同一 AiProvider 调用 OpenAI 兼容流式接口。确认所用平台的免费额度、匹配的 Key/地址及耗尽停用策略后填写模型/Key；再同时打开两个开关。生成保存为私密草稿；修改先预览，确认时检查版本。
- 高德：分别配置 Web服务Key、浏览器JS Key及JS安全密钥，确认免费许可与调用配额后按需打开总开关、免费权限确认与对应的 poi-enabled / route-enabled / weather-enabled / map-enabled 分项开关（默认均关闭）。大陆坐标使用GCJ02，底图也使用高德。POI搜索需由用户选择候选，行程只存用户选择的地点引用，不存全量搜索/路径/天气响应。供应商对地点引用的许可也应在实际启用时确认。
- 天气：当前高德适配器查询实际返回的近期日期，较远日期明确显示暂无预报，不依据城市名构造气温。没有实时交通、公交规划或付费回退。

官方文档：[百炼免费额度](https://help.aliyun.com/zh/model-studio/new-free-quota)、[高德许可与额度](https://lbs.amap.com/upgrade)、[路径规划2.0](https://lbs.amap.com/api/webservice/guide/api/newroute)、[天气查询](https://lbs.amap.com/api/webservice/guide/api/weatherinfo)。

## 实现边界

当前AI任务采用单实例、两线程、有界队列；任务、提案和配额在MySQL持久化，Redis提供用户级执行锁。断开进度连接不取消任务。应用重启将未完成任务标记失败并退回预扣配额；重新提交使用新请求标识。多实例调度与自动恢复执行尚未实现。

分享与邀请都使用随机口令的SHA-256摘要，分享不公开口令列表，只在创建时返回完整链接；分享快照与社区发布各自独立。邀请24小时有效，可撤销，有编辑/只读角色。协作是共享数据和版本冲突保护，没有宣称实时多人协作。

手机号、邮箱验证、找回密码、关注/私信、足迹、个性化推荐、公交/骑行、实时交通和管理后台属于后续范围。正式前端只有 src/features 一套实现；25 个旧文件已经移除，历史内容可从 Git 基线 e17b5de 查阅。旧节点/天数/排序写接口返回 410，旧 AI 直接修改返回 409；旧详情/列表/整份删除委托工作台服务。

历史Git提交曾包含旧密钥，本轮只从当前源码移出并保存在本机私有配置，没有重写历史或推送远端。将来公开仓库前应先轮换历史密钥并处理历史记录，不能把当前源码无密钥当作历史无密钥。

更多说明见 docs/architecture.md、docs/interview-guide.md。

## 第二轮优化验收（2026-10-04）

保留完整路书 PUT，后端按差异写入：20 天、400 个已有节点中改一个名称，实测为 3 条写语句；无变化保存为 0 条写语句、版本与历史均不增长；所有者读取完整路书固定 3 次 SELECT。恢复历史复用本路书原 Day/Item ID，账单继续保留。没有做 QPS 或响应耗时提升的推断。

前端草稿与查询分离，保存期间锁定输入，同组件切换路书会取消旧加载；广场使用游标，失败重试保留原游标并按 ID 去重。路书写入采用字段白名单 DTO，公开列表返回 `{ items, nextCursor, hasMore }`，旧调用方必须同步迁移。

本轮验证：后端 41 项（含真实测试库集成）、前端 11 项、浏览器 5 项、类型检查、生产构建及干净安装通过；另完成 85 次真实 HTTP 检查和社交/头像/桌面手机页面回归。迁移 V7 已仅应用于演示与测试库。详见 [第二轮变更与验收](docs/round2-verification.md)。

## 2.5 轮收尾（2026-10-04）

已完成地点请求内去重与坐标校验、路线 30 秒总期限、地图/天气独立查询和取消、SDK 10 秒共享加载与重试，以及迟到 401 的会话身份保护。普通路线查询复用当前页面结果，“重新查询”才明确刷新；不增加坐标存储或供应商缓存。

本轮重新通过后端 49 项、前端 25 项、浏览器 8 项，以及类型检查、生产构建和 70 次真实接口检查。外部能力仍关闭、在线未验收，没有新迁移。详见 [2.5 轮收尾验收](docs/round25-verification.md)。

## 行程诊断与智能调整（2026-10-05 交付）

工作台增加行程诊断：基础时间检查、室内/室外标记、固定安排；按需补充真实来源后，通过 ADJUST 任务生成受约束提案，预览差异并整份确认保存。V8 增加节点约束与提案元数据，仅应用 demo/test。AI/地图/天气保持关闭，基础诊断可用，联动采用测试替身验证。

本轮后端 79、前端 37、浏览器 11、HTTP 95 项检查通过，类型与构建通过。见 [功能说明](docs/itinerary-analysis.md) 和 [验收报告](docs/itinerary-analysis-verification.md)。

## 第四轮：真实外部能力接通

已开始 `feat/provider-integration`。当前先完成安全接通准备：能力状态可诊断、AI 默认配置统一到百炼 `qwen3.8-flash`，页面区分关闭、免费权限未确认和 Key 缺失。当前 AI 免费额度尚未在账号侧确认，高德 Key 尚未配置，因此不执行真实供应商调用、不强行开启开关。详见 [真实能力接通方案](docs/provider-integration.md)。

## 第四轮阶段验收（2026-10-05）

后端95、前端46、浏览器13、本机接口17项通过，类型与生产构建通过；固定响应只用于测试，真实供应商闭环仍待验收。最小AI探测因EACCES未取得响应，私有地址/开关未改，高德仍待Key/权限。接入顺序、脱敏探测脚本和范围见 [第四轮实施方案](docs/provider-integration.md)。
