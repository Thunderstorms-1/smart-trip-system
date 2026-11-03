# SmartTrip RAG 本地运维

当前正常本机开发入口为 `scripts/Start-Backend.ps1`（Windows PowerShell 5.1），最新运行接线结果见文末 2026-11-01 记录。此前 2026-10-28～30 的离线、诊断、Redis 不可达描述属于对应历史阶段；不能据此判断当前环境。

RAG 默认关闭；没有启动自动扫描、自动下载模型或匿名知识写入接口。生成仍使用现有 Spring AI OpenAI ChatClient。知识管理命令强制关闭 ChatModel，禁止付费探测。

## 版本与资源

- 沿用项目 Spring Boot 4.0.8、Java 17、Spring AI 2.0.1；仅增加 Ollama Embedding、Markdown/PDF Reader 模块。
- Elasticsearch 8.19.23，官方免费 Basic 功能；CJK 分析器 + BM25 + cosine HNSW；RRF 在 Java 中计算。
- Elasticsearch 通过受限 HTTP 接口通信，无 Java 客户端主版本冲突。
- Ollama 独立运行，模型默认 `bge-m3`。不设置假定向量维度：初始化与导入以真实 `/api/embed` 输出建立映射。
- 独立 Compose 限制 ES 为 2GB/1GB heap；Ollama 模型另需内存。2026-10-28 的历史验证仅临时运行过 ES，验证容器随后移除；该轮未安装 Ollama 或下载 Embedding 模型。2026-10-29 早期只做离线审查；同日后续已完成真实 BGE-M3、导入与只读 API 验收，详见最终记录。先确认 Docker 分配资源与本机剩余内存。

官方依据：[Spring AI 2.0.1 Ollama 源码](https://github.com/spring-projects/spring-ai/tree/v2.0.1/models/spring-ai-ollama)、[Elasticsearch 8.19 Docker](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/docker.html)。

## 可选服务启动

在项目根目录执行，密码仅放当前终端环境，不放命令行或版本库：

```powershell
$env:SMARTTRIP_RAG_ES_PASSWORD = Read-Host '本地ES强密码' -MaskInput
$env:SMARTTRIP_RAG_ES_USERNAME = 'elastic'
docker compose -f compose.rag.yaml up -d
# Ollama 已安装后，由操作者主动下载本地免费模型：
ollama pull bge-m3
ollama list
```

ES 只绑定 `127.0.0.1:9200` 并强制鉴权，HTTP 仅适用于这份本机配置；不要将端口转发到外网。Compose 与现有 MySQL/Redis 完全独立。停止时 `docker compose -f compose.rag.yaml stop`；正常 `down` 保留知识卷，不要加 `-v`。

## 本机 JVM 连接方式

当前推荐让后端直接在 Windows 本机 JVM 运行，连接本机 ES/Ollama，继续使用原有 MySQL、Redis 配置。当前业务 `compose.yaml` 的 backend 没有透传任何 `SMARTTRIP_RAG_*` 变量；仅在宿主机设置变量不会自动传入容器。容器中的 `127.0.0.1` 也不指向宿主机。业务 Compose 未因此修改，后端容器与独立 RAG 服务的网络互联尚未验收。

在启动服务、导入和评测的同一终端设置配置，密码沿用前文的当前进程环境：

```powershell
$env:SMARTTRIP_RAG_ENABLED = 'false'
$env:SMARTTRIP_RAG_ES_URL = 'http://127.0.0.1:9200'
$env:SMARTTRIP_RAG_ES_USERNAME = 'elastic'
$env:SMARTTRIP_RAG_EMBEDDING_URL = 'http://127.0.0.1:11434'
$env:SMARTTRIP_RAG_EMBEDDING_MODEL = 'bge-m3'
$env:SMARTTRIP_RAG_EMBEDDING_VERSION = 'v1'
# 保留原有数据库/Redis/JWT配置，并明确本地配置路径：
if (-not $env:SMARTTRIP_CONFIG) {
  $env:SMARTTRIP_CONFIG = (Join-Path (Get-Location) '.local/application-local.yml').Replace('\','/')
}
# 完成下述导入和评测后，再显式开启RAG并启动本机后端：
# $env:SMARTTRIP_RAG_ENABLED = 'true'
# ./scripts/Start-Backend.ps1
```
## 初始化、导入、修改和删除

先按现有构建方式生成最新后端 jar，并准备原有 MySQL/Redis 连接及 JWT 配置。Flyway V10 只新增 `rag_knowledge_document` 与 `rag_knowledge_import`，不改路书业务表。以下命令显式启用 `rag-admin` profile 和 `admin-enabled` 双开关；普通应用启动不执行任何知识管理。

```powershell
# 显式选择本轮最新可执行 JAR；不要默用旧 target 产物。
$latestRagJar = (Resolve-Path './.local/rag-validity-target/smart-trip-backend-0.0.1-SNAPSHOT.jar').Path
./scripts/Invoke-RagKnowledge.ps1 -Jar $latestRagJar -Command inspect
./scripts/Invoke-RagKnowledge.ps1 -Jar $latestRagJar -Command init
./scripts/Invoke-RagKnowledge.ps1 -Jar $latestRagJar -Command import
# 编辑 Markdown 或清单元数据（包括有效期）后，import 自动识别变化，无须改正文：
./scripts/Invoke-RagKnowledge.ps1 -Jar $latestRagJar -Command import
./scripts/Invoke-RagKnowledge.ps1 -Jar $latestRagJar -Command delete -DocumentId travel-suzhou-gardens-v1
# 全量重建会用清单替换整个语料；也用于Embedding模型/版本变更：
./scripts/Invoke-RagKnowledge.ps1 -Jar $latestRagJar -Command rebuild
```

`-Manifest` 可指定另一份经授权的本地清单。清单为 `{"documents":[...]}`，文档路径必须位于清单目录内，仅接受 Markdown/PDF，不请求来源 URL、不爬取网站。默认语料来源与人工复核范围见 `knowledge/travel-rag/README.md`。无更新时间保留 null；不会把核对日期伪造成来源更新日期。授权未知、私人归属、非公开、来源不明资料在向量化前拒绝。

`inspect` 的业务动作只解析和切片、不连接 ES/Ollama；脚本仍会启动完整 Spring 应用，使用现有数据库配置，并可能由 Flyway 执行尚未应用的迁移。因此它不是本轮离线审查可执行的纯无数据库命令；离线检查应使用显式的 mock 单元测试白名单。PDF 无可提取文本会明确失败，扫描件需人工 OCR。

## 索引与一致性

`smarttrip-travel-v1` 是读取别名，实体索引名称为 `smarttrip-travel-v1-g-<随机代号>`。不要自行创建同名实体索引。所有文档/片段保留来源、地区、季节、授权、版本、哈希和 Embedding 版本；实体 `_meta` 保存模型名称、版本及实测维度。检索前校验向量与映射，禁止混入不同模型/维度。

每次导入在 MySQL 跨进程锁下建立完整新快照：复制未变化文档、写入新片段、刷新并验证数量，最后原子切换读取别名；读者只看到一份完整语料。重复且未变化的导入不产生新片段。修改、删除会排除旧文档的全部片段。失败不会提前删除旧索引；提交前的工作失败会在锁内补偿别名，且必须确认恢复响应后才丢弃新快照；COMMIT 已发出但返回异常时，数据库提交结果不确定，保留新旧快照、不擅自反向发布。若返回 `INDEX_RECOVERY_REQUIRED`，停止新的管理操作，核对别名与导入记录后处理，不要删除任何快照。已确认 COMMIT 成功后的连接关闭异常不会把成功导入改为失败。

旧实体快照为回滚保留，不由应用自动删除，因此管理员应在验收新别名后清理不再需要的旧实体索引。历史快照不参与公共检索；删除生效范围是当前读取别名。模型更换需先设置新的 `embedding-model` 和 `embedding-version`，再完整 `rebuild`；不要在旧索引覆盖维度。

## 启用与降级

完成服务、导入及回归后显式设置 `SMARTTRIP_RAG_ENABLED=true`。`fallback-to-original=true` 时，检索服务故障会记录实际原因并允许继续生成；若关闭降级，服务故障且无可用结果会在模型调用前明确失败。单路检索失败可保留另一支结果，精排失败保留 RRF；空库或无可信命中明确标记知识缺失，不编造引用。RAG 关闭时不访问知识服务。Rerank 默认关闭，只在真实本地服务可用时启用，不伪造分数。

常用配置前缀为 `smarttrip.rag`：`elasticsearch-url/username/password`、`embedding-base-url/model/version`、`chunk-tokens`(384)、`chunk-overlap-tokens`(48)、`retrieval-timeout-millis`(6000)、`embedding-timeout-millis`(4000)、`ingestion-timeout-millis`(120000)。冷启动 Embedding 的超时由 `SMARTTRIP_RAG_EMBEDDING_TIMEOUT_MS` 单独控制；`SMARTTRIP_RAG_INGESTION_TIMEOUT_MS` 只控制索引初始化、复制和写入等 ES 请求，不会延长 Embedding 请求。交互检索应预热模型并保持短超时。账号密码使用环境配置，不写入文档或日志。

常见诊断码：`ES_UNAVAILABLE`、`ES_AUTH_FAILED`、`OLLAMA_UNAVAILABLE`、`EMBEDDING_MODEL_MISSING`、`EMBEDDING_MODEL_MISMATCH`、`DIMENSION_MISMATCH`、`INDEX_EMPTY`、`RETRIEVAL_TIMEOUT`、`DOCUMENT_ACCESS_DENIED`、`INGESTION_BUSY`。日志只记录任务/导入标识、数量与状态，不记录私人需求或供应商响应全文。

## 原生成任务的接入位置

用户确认结构化需求后，原任务通过幂等键创建/复用并成功领取执行权（claim/start），再检索知识，最后调用原 AI Provider。检索结果先持久化为任务私有上下文及可展示的来源快照，保存失败会阻止模型调用。实际提供给模型的来源与候选来源分别记录；任务响应的RAG部分展示状态、数量和公开知识的来源元数据，不返回私有上下文全文。

同一任务复用已保存的检索快照。GET、SSE 重连和页面恢复只读取任务，不重新执行检索或模型，也不新增额度消耗。原结构化解析、预算/时间校验、草稿编辑、版本冲突和保存链路继续执行。

## 重复执行检索评测

默认语料是 8 份公开来源短摘记、16 个实际切片；`knowledge/travel-rag/evaluation/cases.json` 有 26 条文档级相关性标注（qrels），由实施助手逐条整理，尚未由业务人员独立审核。标注与程序测试都不能直接证明推荐质量达标。

完成真实本地模型安装、索引导入并核对模型版本后，可从项目根目录运行下列离线命令。它显式关闭付费 AI 与知识管理命令，仅运行检索并输出 JSON 报告；不要求开启在线 RAG，也不会进入业务任务恢复。保留当前终端的 ES/Ollama 连接及原有数据库配置。

```powershell
$dataset = (Resolve-Path './knowledge/travel-rag/evaluation/cases.json').Path
$report = Join-Path (Get-Location) '.local/rag-evaluation-report.json'
# 仅检索评测/只读 RAG 验收，关闭 ChatModel；不是完整 AI 应用启动命令。
$jar = (Resolve-Path './.local/rag-validity-target/smart-trip-backend-0.0.1-SNAPSHOT.jar').Path
& java -jar $jar `
  '--spring.main.web-application-type=none' `
  '--ai.llm.enabled=false' `
  '--smarttrip.rag.enabled=false' `
  '--smarttrip.rag.admin-enabled=false' `
  '--smarttrip.rag.evaluate=true' `
  '--smarttrip.rag.rerank-enabled=false' `
  "--smarttrip.rag.evaluation-dataset=$dataset" `
  "--smarttrip.rag.evaluation-report=$report"
```

报告记录数据集版本/哈希、索引、Embedding 模型/版本、逐条命中和失败原因，以及各策略的 Recall@5、Precision@5、MRR@10、平均延迟、混入率与异常情况。Rerank 关闭会明确跳过；若要评估真实精排，先独立配置可用服务，再显式启用该参数。服务不可用、无可靠向量或未实际精排时，不把降级结果当作完整模型效果。

## 历史验证边界（2026-10-28）

自动测试中的 HTTP 固定响应验证真实 SDK 序列化、维度/鉴权/超时错误及不重试；模拟索引与元数据验证幂等和失败保留旧快照。这些不能代替真实 Elasticsearch、真实本地 Embedding、真实 Rerank 或真实生成保存闭环验收。最终以本轮验证报告为准，服务未部署时不得宣称已通过在线验收。

2026-10-28 历史轮次使用 Elasticsearch 8.19.23 验证了 CJK/BM25、kNN、别名切换/更新/删除及鉴权；涉及向量的在线检查使用明确标记的固定测试向量。真实 BGE-M3 尚未安装或验证，真实 Rerank 未配置，也未调用付费 ChatModel。因此这些结果证明相关接口和数据流程可运行，不能宣称真实语义召回、精排或整体旅行推荐质量已经通过验收。
## 历史可复核结果（2026-10-28）

- 实际 ES 8.19.23：3 项隔离测试通过，包括真实 BM25/kNN 协议、快照发布/更新/删除/回滚、401 鉴权，以及在混入私人、带 owner、未授权、过期记录的索引中验证服务端预过滤。向量明确使用 3 维固定测试值，不代表真实 BGE-M3。
- 同一 26 条小型数据集的最终 BM25 实测：Recall@5=0.984127、Precision@5=0.346154、MRR@10=1.0、平均 50.19ms、不相关混入率=0.150943；5 个预期无结果样本全部无结果。向量/混合/精排质量尚未测量，不能据此宣称 RAG 优于原方案。原始报告保存在 `.local/rag-real-es-target/rag-corpus-bm25-evaluation.json`。
- 独立临时 MySQL 8.0 / Redis：RAG 关闭与不可用降级的真实 HTTP→预览→编辑→并发确认保存→工作台重读均通过；知识元数据 4 项真实 SQL/锁/事务检查通过。生成服务使用固定测试 Provider，真实 ChatModel 调用为 0。相关结果保存在 `.local/rag-real-sql-result.json`，临时数据库已移除。
- 前端 17 项来源/状态 SSR 检查通过，未重做规划页面。真实 Embedding、真实 Rerank、付费生成质量及容器间网络仍需部署后另行验收。

## 2026-10-29 早期离线审查边界（历史阶段）

本次未连接真实 Ollama、ES、MySQL、Redis 或 ChatModel，也未启动/修改 Docker 容器、下载模型或改动密码/运行配置。Maven `-o` 指定白名单编译与 61 项 mock/固定响应测试通过，其中包含新增 8 项边界回归；预览、编辑、保存和退款检查中的数据库/Redis均为 mock，不能冒充真实持久化验收。上面的 2026-10-28 数据是历史记录，不代表当前环境可用或本次真实服务验收通过。

修复内容：别名恢复响应必须确认；COMMIT 结果不确定时保留快照；确认提交成功后关闭连接异常不反写失败；无效搜索/计数响应不能冒充正常空结果；单条损坏知识隔离并保留其他有效命中；Rerank 索引拒绝超过 int 范围的值。真实服务行为与检索质量仍待联调。

## 早期源码补齐记录：登录后只读诊断（历史阶段）

本节记录后续真实验收之前的源码开发阶段；该阶段没有执行测试、编译、打包、接口调用、服务启动、Docker 命令或模型下载；旧节的测试结果属于历史记录，不适用于本轮新增代码。用户已确认本机 Ollama 0.40.13、bge-m3:latest 约 1.2GB 且 /api/embed 返回 1024 维；此信息由用户提供，本轮没有复测。默认模型名 bge-m3 与 Ollama 的 latest 用法一致。导入仍以实际输出确定映射，源码没有固定 1024。导入和查询必须使用同一配置字面值与版本；bge-m3 和 bge-m3:latest 即使在 Ollama 中指向同一模型，也不要在同一索引的元数据中混用，改名或换版本后应按原机制受控重建。

新增两个只读 API，沿用现有 /api/** JWT 拦截器，并在 Controller 再检查 ResourceAccess.userId()。它们只读取明确授权的公开知识，不提供导入、重建或删除 HTTP 入口。项目没有全局管理角色，本轮不虚构 ADMIN，也不向普通登录用户开放管理写操作。导入/重建/删除仍复用服务器维护人员持有文件系统访问权的现有 rag-admin profile + admin-enabled 受控命令，普通应用启动不自动导入。

- GET /api/rag/status：只返回开关、索引别名、Embedding 模型/版本和 NOT_CHECKED 状态，不访问 ES/Ollama，不返回地址、用户名、密码或 Token。
- GET /api/rag/status?probe=true：显式只读检查 ES 别名、映射模型版本和向量维度，并统计当前模型版本下授权 PUBLIC、无 owner 的知识。publicChunkCount 是精确片段数；publicDocumentCountEstimate 来自 ES cardinality，明确是文档数量估算，不能当作 MySQL 元数据行数。映射就绪不代表 Ollama 或生成链路已验证；embeddingStatus 仍为 NOT_CHECKED。该显式探测可在在线 RAG 关闭时排查 ES，但不会开启在线 RAG。
- POST /api/rag/preview：请求体为 {"requirement":{"destinations":["稻城亚丁"],"origin":"苏州","days":8,"pace":"RELAXED","interests":["自然风光"]}}。填入结构化需求，复用 review(extract=false) 校验与原检索流水线，不用模型解析描述。无需先配置对话模型，但会遵循 smarttrip.rag.enabled；关闭时返回 DISABLED，不请求 Embedding。响应 rag 内分开列出候选与实际选入上下文的 sources、真实排名/分数、失败原因；snippets 仅给出选中公开片段最多 400 字符的摘录及截断标记，不暴露任务私有 Prompt、向量或模型诊断。预览不确认需求、不创建任务、不扣生成额度，也不调用 ChatClient。fallback=false 且原检索策略要求失败时，返回带诊断码的 503，不伪造成功结果。

调用以上 API 必须带现有登录获得的 Authorization: Bearer 令牌。此处不提供或记录任何实际令牌。来源、引用与交通、费用、天气等实时信息的核验边界仍适用。结构化需求及其回显只属于发起该预览的登录用户，不读取其他用户任务。

常用排查码包括 ES_AUTH_FAILED、ES_UNAVAILABLE、INDEX_EMPTY、EMBEDDING_MODEL_MISSING、OLLAMA_UNAVAILABLE、EMBEDDING_MODEL_MISMATCH、DIMENSION_MISMATCH、RETRIEVAL_TIMEOUT、ES_OUTPUT_INVALID；索引恢复不确定仍为 INDEX_RECOVERY_REQUIRED。无凭据、无模型或空库时状态和计数保持未确认/失败，不补假数据。

### 容器环境变量示例（不自动应用）

新增 compose.rag-backend.example.yaml 作为可选覆盖示例，未修改实际 compose.yaml、compose.rag.yaml、.env、application-local.yml 或 application.yml。它只透传后端现有 SMARTTRIP_RAG_* 变量，不改变 MySQL/Redis、网络、卷或密码。ES 与 Spring Boot 共用 SMARTTRIP_RAG_ES_PASSWORD；其他属性名仍与 application.yml 的映射一致，没有新增运行配置项。SMARTTRIP_RAG_ENABLED 默认 false，Rerank 默认关闭。

后续容器部署时，应由操作者明确提供后端容器可访问的 SMARTTRIP_RAG_ES_URL、SMARTTRIP_RAG_EMBEDDING_URL 和现有 ES 凭据；容器 127.0.0.1 不指向宿主机，不要直接照抄宿主 JVM 地址。host.docker.internal、ES 本机回环绑定与网络可达性须实际验证，示例不自动改变 Ollama/ES 的监听地址或暴露端口。若启用 Rerank，其 URL 同样必须从后端容器可达，默认关闭值不变。示例合并方式为 docker compose -f compose.yaml -f compose.rag-backend.example.yaml，是否部署与执行何种子命令由后续验收决定，本轮没有执行。

### 历史阶段的待验收清单（后续完成情况以下文最终记录为准）

1. 编译与针对新增状态/预览入口、共享权限过滤、片段摘录边界的单元检查；确认原生成、关闭 RAG、SSE/刷新快照复用不受影响。
2. 未登录访问两个新 API 应被现有 JWT 拦截为 401；直接调用 Controller 缺 UserContext 也应被拒绝。预览只能返回公开、授权、无 owner 的片段，不能返回私有任务上下文。
3. 默认 status 不调用外部服务；probe=true 对实际 ES 返回真实别名、映射和公共计数，异常计数不能被解释为成功；映射维度与实际 BGE-M3 输出一致。
4. 在核对数据库及迁移范围后，运行现有 init/import，检查重复导入、更新、删除、重建、别名恢复与 COMMIT 结果不确定的保留策略。
5. 对同一结构化需求预览 BM25/向量/RRF/可选精排，核对 sources 与摘录、地域季节过滤、模型/版本/维度一致性及超时降级；Rerank 未部署时不得声称精排已验收。
6. 最后按现有 AI 配置与调用授权，验收确认需求→真实检索快照→原 ChatClient→解析校验→草稿编辑→确认保存→工作台重读。未做真实联调前，不认定本轮已上线成功。

## 最终本机接线与验收记录（2026-10-29）

本轮在保留原有工作区和技术版本的基础上补齐了显式真实混合检索、状态与预览接口测试。没有重建或重启 Docker 应用栈，没有修改 Compose 网络、端口、卷、密码、API Key、JWT 密钥、.env 或 .local/application-local.yml，也没有调用付费 ChatModel。

### 实际结果

- 使用项目原有 Maven 仓库和版本完成正常 package，下载了此前缺失的插件依赖；最终产物位于 .local/rag-final-wiring-target/smart-trip-backend-0.0.1-SNAPSHOT.jar。已核对 Main-Class=org.springframework.boot.loader.launch.JarLauncher、Start-Class、BOOT-INF/classes 和 128 个嵌套依赖；不是普通 JAR。没有更新旧 Docker 镜像。
- 新增 RagDiagnosticsTests 11 项、RagControllerTests 5 项，另有 34 项相关 RAG/任务回归通过。新增 RagHybridLiveIntegrationTests 1 项真实集成测试通过：68 次真实 Ollama Embedding，16 个语料切片加 6 个隔离负向权限/版本/过期夹具，实测 1024 维；测试使用随机独立索引并且已经清理。没有使用固定向量来冒充真实语义检索。
- 原 26 条标注未修改。BM25 / VECTOR / HYBRID 分别执行 26 条：Recall@5 均为 0.984127，Precision@5 均为 0.346154，MRR@10 均为 1.0；平均延迟分别约 38.73 / 120.35 / 124.50ms；5 个预期空结果全部正确，异常和降级为 0。本小型语料中混合检索未表现出质量指标收益，延迟更高。标注仍由实施助手整理，未独立业务复核；这不是普遍旅行推荐质量承诺。真实 Rerank 未运行。
- 写入前重新核对本机 127.0.0.1:3306/smart_trip_demo、V1～V9 成功历史、RAG 两表不存在、正式 ES 索引和别名不存在；确认 V10 仅含两个 CREATE TABLE。受控命令以 Flyway target=10 只新增 V10 两表，随后 init/import/重复 import。正式别名 smarttrip-travel-v1 当前指向 smarttrip-travel-v1-g-a27a0ff881f846fabe2671bbf19b06ad；8 文档、16 片段、bge-m3/v1、实测映射 1024 维。重复导入 UNCHANGED，没有重复切片或别名切换。初始化空快照按原恢复设计保留，没有擅自删除。
- 数据库正式元数据位于 smart_trip_demo；没有把 smart_trip_test 的空测试表当知识库。业务表行数保持原值。现有配置文件 SHA-256 校验一致。
- 最新源码 JAR 在独立本机回环端口 18081 启动。使用用户明确授权、仅内存保存的 5 分钟令牌和既有测试账号验证：匿名 GET/POST 均 401，认证 status/probe 返回 8 文档/16 片段/1024 维，稻城亚丁需求 preview 返回 SUCCESS 和 3 条实际来源。任务数、额度及业务行数不变，不返回私有 Prompt 或向量。验收结束已停止且核对该端口关闭，旧 Docker 后端仍在 8081。

初始本机 RAG 凭据返回 401；经用户明确授权，只读取得当前 ES 容器已有凭据并通过当前进程注入后接通。没有重置密码或回写配置，因此新终端仍需从已授权来源提供正确的现有凭据。实际令牌、密码和密钥没有写入源码或报告。

### 新增测试的显式条件

RagHybridLiveIntegrationTests 只有 SMARTTRIP_RAG_HYBRID_LIVE=true 才运行，还需 SMARTTRIP_RAG_REAL_ES_URL、SMARTTRIP_RAG_REAL_ES_USERNAME、SMARTTRIP_RAG_REAL_ES_PASSWORD；本地模型地址可由 SMARTTRIP_RAG_LIVE_OLLAMA_URL 指定，模型由 SMARTTRIP_RAG_LIVE_MODEL 指定（默认 bge-m3）。报告写到当前 smarttrip.build-dir 下的 rag-hybrid-live-evaluation.json。测试只清理精确随机命名空间 smarttrip-rag-hybrid-live-<UUID>-g-<UUID>，不读取或修改正式别名的文档。

新增测试覆盖：默认 status 零外部访问、ES 探测/空库/鉴权/异常响应、真实 JWT 匿名拒绝及 tokenVersion 校验、公开/授权/无 owner 过滤、摘录长度和私有字段不泄漏、关闭 RAG、缺模型/ES 故障/不可信来源、严格失败与降级，以及预览不调用任务、数据库、Redis 或 ChatClient Bean。

### RAG 只读验收专用启动（不是完整 AI 应用启动）

本轮没有修改默认配置。最新 JAR 为上述隔离目录产物，受控管理脚本新增可选 -Jar 参数，可以显式指定它，避免误用旧的 target JAR。默认行为不变。当前正式知识库已初始化，不要重复运行本轮的初次初始化流程；有新知识时才按原受控 import 流程处理。

本机连接所需环境名称：SMARTTRIP_CONFIG、SMARTTRIP_DB_URL、SMARTTRIP_DB_USER、SMARTTRIP_DB_PASSWORD、SMARTTRIP_RAG_ES_URL、SMARTTRIP_RAG_ES_USERNAME、SMARTTRIP_RAG_ES_PASSWORD、SMARTTRIP_RAG_EMBEDDING_URL、SMARTTRIP_RAG_EMBEDDING_MODEL、SMARTTRIP_RAG_EMBEDDING_VERSION、SMARTTRIP_RAG_INDEX；按需使用 SMARTTRIP_RAG_EMBEDDING_TIMEOUT_MS、SMARTTRIP_RAG_RETRIEVAL_TIMEOUT_MS。JWT 沿用既有配置，不轮换。

如果本地 YAML 已直接设置相同 Spring 属性，仅设置应用里的别名变量不一定覆盖它。可由操作者把现有凭据安全放到本进程环境，并用 SPRING_APPLICATION_JSON 精确覆盖 datasource/RAG 属性。下例只引用环境名称，不含实际凭据；不要把生成的 JSON 打印或保存：

```powershell
# 仅供只读 RAG 验收：ai.llm.enabled=false 会禁用生成，不能用于证明完整 AI 可用。
# flyway.target=10 仅为 2026-10-29 受控验收范围，日常开发不得永久固定此值。
# 在项目根目录，当前进程已有正确的现有 DB/ES 用户和密码环境变量。
$env:SMARTTRIP_CONFIG = (Join-Path (Get-Location) '.local/application-local.yml').Replace('\','/')
$localRagSettings = @{
  spring = @{
    datasource = @{
      url = 'jdbc:mysql://127.0.0.1:3306/smart_trip_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
      username = $env:SMARTTRIP_DB_USER
      password = $env:SMARTTRIP_DB_PASSWORD
    }
    flyway = @{ target = '10' }
  }
  smarttrip = @{ rag = @{
    'elasticsearch-url' = 'http://127.0.0.1:9200'
    'elasticsearch-username' = $env:SMARTTRIP_RAG_ES_USERNAME
    'elasticsearch-password' = $env:SMARTTRIP_RAG_ES_PASSWORD
    'embedding-base-url' = 'http://127.0.0.1:11434'
    'embedding-model' = 'bge-m3'
    'embedding-version' = 'v1'
    'index-name' = 'smarttrip-travel-v1'
    'embedding-timeout-millis' = 60000
    'retrieval-timeout-millis' = 90000
    'rerank-enabled' = $false
  } }
}
$env:SPRING_APPLICATION_JSON = $localRagSettings | ConvertTo-Json -Depth 8 -Compress
$latestRagJar = (Resolve-Path './.local/rag-validity-target/smart-trip-backend-0.0.1-SNAPSHOT.jar').Path
& java -Xmx384m -jar $latestRagJar '--server.address=127.0.0.1' '--server.port=18081' '--ai.llm.enabled=false' '--smarttrip.rag.enabled=true' '--spring.flyway.target=10'
```

环境变量及 SPRING_APPLICATION_JSON 仅属于启动它的进程环境，不修改本地 YAML。验收结束应退出该终端；日常开发必须移除 SPRING_FLYWAY_TARGET、SPRING_APPLICATION_JSON 中 spring.flyway.target 和命令行 --spring.flyway.target=10，恢复项目正常迁移策略，不能把版本永久锁在 V10。完整 AI 启动还需可用 Redis 和既有模型权限配置，不应照抄禁用 ChatModel 的只读命令。以上超时是本地冷启动验收使用的有界上限，不代表正常检索耗时；按实际硬件预热后再评估。

### 仍存在的阻塞与未验收内容

Windows 127.0.0.1:6379 无 Redis 监听，现有 Redis 容器也没有宿主端口映射；本轮没有安装 Redis、调整 Compose、增加端口转发或重建容器。恢复普通登录和完整 AI 任务生命周期前，需要另行确定本机可达的安全 Redis 服务，并通过进程级 SMARTTRIP_REDIS_HOST / SMARTTRIP_REDIS_PORT 接入。

当前只读接口验收使用了授权短期测试令牌，不能代替普通登录验收。真实付费生成、完整任务锁/额度生命周期、草稿编辑、确认保存与工作台重读没有在本轮完整联调，需恢复 Redis 后另行授权。Rerank 仍关闭，未声称真实精排通过。前端未修改，也未把旧 Docker 后端或旧前端视为最新源码验收目标。

完整原始证据保存在当前 Codex 工作区 .validation/rag-final-wiring：Maven 打包日志、单测日志、真实混合检索日志、初始化前置条件、导入结果、实际 status/preview 响应、本机服务日志和最终只读状态。真实混合评测 JSON 位于项目 .local/rag-final-wiring-target/rag-hybrid-live-evaluation.json。

## 年度性资料有效期（2026-10-30 收尾）

validFrom / validUntil 是可选、闭区间的 ISO 日期，必须成对出现且前者不晚于后者；sourceUpdatedAt 仍只表示来源发布时间。大兴安岭防火资料限定 2026-09-15 至 2026-11-15，不据此推断其他年份。普通长期资料不设置有效期。

确认需求的 startDate + days（业务旅行天数）计算结束日 startDate + days - 1；日期或天数未知时，不假定一年或一天的有效性，年度性资料不进入上下文。ES 两条检索分支共享日期交集过滤，Java 再校验配对、顺序及交集。跨有效期时来源快照和模型上下文包含 PARTIALLY_APPLICABLE、applicableFrom、applicableUntil 及说明；来源面板单独显示适用期和部分适用提醒。发布时间 freshness 保持独立。

导入 fingerprint 包含有效期元数据；只改清单也会替换该文档全部片段。空有效期不参与序列化，避免普通未变更文档的历史 fingerprint 被动改变。MySQL 沿用 metadata_json，不新增迁移。更新只使用 import，复制未变化文档到新快照并切换原别名，旧索引保留；本轮不执行 rebuild 或删除。

原 26 条 qrels 保持不变。Q07/Q08/Q10 没有旅行天数，Q14 没有明确日期；启用严格适用期后其中的年度资料被排除，因此旧标注下 Recall/MRR 会下降，不通过修改标注或放宽过滤追平旧分数。独立日期边界回归与旧数据集评测分别记录，不能混为一项质量承诺。实测数字、当前实体索引及包校验见本轮验收结果。

### 2026-10-30 实际验收结果（取代早期未验证描述）

- 后端 89 项定向离线回归（其中有效期 12 项）和 1 项真实 BGE-M3/ES 集成测试通过，90 项合计零失败、零错误、零跳过。新映射首先在独立随机索引验证；7 组日期情境分别检查原始 ES 过滤及 BM25/VECTOR/RRF，结束仅清理该测试自己的随机索引。
- 前端仅为原来源列表增加有效期与部分适用文字，18 项来源展示测试、类型检查及生产构建通过，没有重写页面。
- 原 26 条评测与 SHA-256 保持不变（d21f27d8f0a679c47343f04b74c5aaf84809c359d3aac71578b7e22b55ae9418）。实际结果：

| 策略 | Recall@5 | Precision@5 | MRR@10 | 平均延迟(ms) | 预期空结果正确率 | 异常/降级 |
| --- | --- | --- | --- | --- | --- | --- |
| BM25 | 0.863492 | 0.315385 | 0.904762 | 18.58 | 100%（5/5） | 0/0 |
| VECTOR | 0.863492 | 0.315385 | 0.904762 | 112.35 | 100%（5/5） | 0/0 |
| HYBRID | 0.863492 | 0.315385 | 0.904762 | 119.46 | 100%（5/5） | 0/0 |

这些指标低于 2026-10-29 旧规则结果，原因是缺少完整旅行日期范围的年度知识查询现在被安全排除；未修改 qrels 来消除差异，也不声称混合质量提升。真实 Rerank 仍未验收。

正式别名 smarttrip-travel-v1 已用最新 JAR 的受控 import 切换到 `smarttrip-travel-v1-g-ad14b9d168dd492db7c85cd1d17cb3dc`；保留旧索引 `smarttrip-travel-v1-g-a27a0ff881f846fabe2671bbf19b06ad`。8 份文档、16 个切片、bge-m3/v1、1024 维；7 份未变文档的片段及指纹完整保留。大兴安岭两个切片的 validFrom=2026-09-15、validUntil=2026-11-15；sourceUpdatedAt 仍为 2026-03-16。MySQL metadata_json 也已核对。第二次 import=UNCHANGED，不再切换别名。

对正式别名额外做了实际检索→RRF→上下文→只读 preview 服务组装验证：两个边界可用、2026-11-20/2027-10-15/未知日期排除、跨期明确标记部分适用，普通无日期苏州知识仍可检索。这是直接只读服务链验证，本轮未重新启动 HTTP 应用做端点验收，也没有调用 ChatModel。

本次导入在已完成 V10 的开发库禁用 Flyway，不执行迁移。业务表/任务/额度及既有配置文件均未变化。Redis 6379 仍不可达，普通登录、完整 AI 生成/编辑/确认保存/工作台闭环仍未验收；不把只读 RAG 或离线测试解释为完整 AI 可用。

最终可执行包：`.local/rag-validity-target/smart-trip-backend-0.0.1-SNAPSHOT.jar`，Spring Boot 4.0.8 重封装成功、127 个嵌套依赖，SHA-256 `64c526e7656d70308abf32bd1827abf1e04196dfe97849d24a958fd6fcda66cb`。原始测试日志、正式库更新、只读来源和 Git 对照保存在 Codex 工作区 `.validation/rag-validity-20261030`。本轮不执行 Git 提交。


## 正常本机开发接线（2026-11-01）

在项目根目录的 Windows PowerShell 5.1 执行唯一正常启动命令：

```powershell
.\scripts\Start-Backend.ps1
```

启动入口会复用原 Build-Backend.ps1，读取既有本地数据库/JWT 配置，并协调 127.0.0.1:3306 MySQL、127.0.0.1:6379 Redis、127.0.0.1:9200 ES 和 127.0.0.1:11434 Ollama。后端只监听 127.0.0.1:8080。端口占用时退出，不结束现有进程。-CheckOnly 可只读核对依赖，不创建 Redis 或启动应用。

旧入口只装载 AI 的 .env 配置，当前本地 RAG 密码未配置，导致空凭据访问 ES 返回 401。正常入口使用现有 compose.rag.yaml 找到 ES 容器，只读取得其已有 ELASTIC_PASSWORD，通过当前进程 SPRING_APPLICATION_JSON 的实际 Spring 属性覆盖缺失/旧值；不重置密码，不写 .env/YAML 或命令行参数。RAG 默认启用，Rerank 和管理导入入口保持关闭。直接绕过脚本从 IntelliJ Run，不会自动继承这些临时环境值。

AI 保留原有配置意图：当前进程 SMARTTRIP_AI_* 优先，其次 .env，缺省字段仍由原本地 YAML 提供；脚本不会自行把禁用/未确认额度改为启用。当前已配置 ai=true/READY，仅表示本地配置完整，未探测供应商权限、额度或在线状态，也未调用付费模型。正常入口不强制 ai.llm.enabled=false，不使用固定 Provider，也不永久固定 Flyway target=10；本机 demo 已为 V10，实际启动只验证，无待执行迁移。

本机 6379 原无监听，旧 Docker Redis 只有内部端口。新入口为宿主 JVM 使用独立容器 `smarttrip-jvm-redis-24a1acf5bae0`，仅发布 127.0.0.1:6379，专用新卷 `smarttrip-jvm-redis-24a1acf5bae0-data` 与旧 redis_data 分离，AOF 持久化并在 Docker 重启后复用。不会重建/清空旧 Redis 或挂载其数据卷。若已有回环 Redis 可用则复用；未知占用或凭据要求不匹配时退出，不覆盖服务。停止后端时保留开发 Redis，后续启动复用同一实例及新卷。

实际验证：Windows PowerShell 5.1 解析、环境恢复、依赖预检与 Redis 复用通过；正常 8080 后端成功连接 demo MySQL，Flyway 验证 10 个迁移且无需执行。为遵守不改真实业务数据，登录验证使用相同原生 JAR 和原 AiProvider、test 数据库临时账号及开发 Redis DB14：登录 200、RAG READY、公开 8 文档/16 切片/1024 维，真实 BM25 命中10、向量命中10、选入2来源，AI 本地配置 READY。未调用 /api/ai/tasks 或 ChatModel。临时账号与测试缓存已清理；demo/test 业务数据全表指纹与初始值一致。

当前后端 8080 和开发 Redis 保留运行。验证中的临时 18082 JVM 已停止，未停止原有应用栈。验收记录位于 Codex 工作区 .validation/local-runtime-20261101。当前没有已知的本机接线阻塞；真实供应商生成仍因本轮禁止付费调用而未验收。
