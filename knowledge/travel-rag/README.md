# SmartTrip 旅行知识种子资料

本目录含 8 篇逐条核对来源的新写短摘记，覆盖苏州园林、平江街区、稻城亚丁自然景观与步行强度、大兴安岭 2026 年秋季森林防火、高海拔风险、旅行节奏与交通原则。它用于验证检索和来源流转，不是完整旅游指南。

## 来源和授权范围

所有来源通过官方或第一方页面由实施助手逐项核对；核对日期为 2026-10-28。正文用自己的文字简短转述必要事实，不批量抓取，不保存网站全文、图片或付费资料。`authorized=true` 仅表示允许本项目导入这些新写的摘记，不声称源网站所有内容均获转载授权。UNESCO 页面注明简介的 CC-BY-SA IGO 3.0 许可，本文仅转述基本事实并保留出处。

| 文档 | 来源 | 源页面日期 | 适用边界 |
| --- | --- | --- | --- |
| 苏州园林 | [UNESCO](https://whc.unesco.org/en/list/813/) | 未注明，保存 null | 稳定文化事实，无实时票价 |
| 平江街区 | [苏州市地方志工作办公室](https://dfzb.suzhou.gov.cn/dfzb/szdq/201606/ebcb2291909b45a09e0e1142af036d31.shtml) | 2016-06-17 | 历史地理，不证明当前营业 |
| 亚丁景观、亚丁强度 | [稻城县人民政府](https://www.daocheng.gov.cn/dcyw/article/727365) | 2026-08-19 | 2 篇不同主题；不复用限时优惠 |
| 大兴安岭秋季 | [大兴安岭地区行政公署](https://dxal.gov.cn/dxal/c100019/202603/c13_331278.shtml) | 2026-03-16 | validFrom=2026-09-15、validUntil=2026-11-15（含边界）；不能套用其他地区或年份 |
| 高海拔 | [CDC Yellow Book](https://www.cdc.gov/yellow-book/hcp/environmental-hazards-risks/high-altitude-travel-and-altitude-illness.html) | 2025-04-23 | 一般风险提醒，无药物或剂量 |
| 旅行节奏 | [文化和旅游部](https://zwfw.mct.gov.cn/cxts/cxtsDetail?uuid=1983) | 2026-09-23 | 长期原则；编排建议另行明确标注 |
| 旅行交通 | [应急管理部](https://www.mem.gov.cn/xw/xwfbh/2026n8y24xwfbh/wzsl_4260/202609/t20260924_717597.shtml) | 2026-09-24 | 无实时班次、价格、具体耗时 |

`sourceUpdatedAt` 使用源页面实际发布日，不用核对日伪装更新时间；未注明日期的源保存 null。`sourceCheckedAt` 记录核对日（当前导入器可忽略此扩展字段，正文仍保留），`ingestedAt` 则由导入器生成。正文中“项目编排建议”是基于用户约束的保守编排原则，不伪称原站结论或实际运行结果。

## 导入边界

默认唯一清单为本目录的 `manifest.json`，相对路径限定在清单目录内。只导入其中明确公开、无 owner、授权且非测试的 8 篇文档。不要对整个目录递归导入 Markdown，否则会误带测试夹具和本说明。已用真实 Spring AI Markdown reader 与章节/token splitter 验证：8 篇文档共 16 个片段，每篇 2 个片段（chunkTokens=384，chunkOverlapTokens=48）。2026-10-29 已通过真实 BGE-M3 → ES 导入及重复导入验收：正式别名 smarttrip-travel-v1，8 文档/16 切片，bge-m3/v1，实测 1024 维。状态与只读预览已验收；Redis 仍阻塞普通登录及完整 AI 业务，不代表生成、确认保存已可用。

`evaluation/fixtures-manifest.json` 的 3 篇文档为合成负向测试：私人、过期和提示注入；不含真实私人资料，也没有虚构真实票价。全部 `authorized=false`、`testOnly=true`，不得进入默认公共索引。管理导入器应拒绝该清单。污染索引的测试只能在独立测试索引中通过受控夹具执行，不能放宽生产导入校验。

## 检索评测标注

`evaluation/cases.json` 有 26 条由实施助手逐条整理的文档级相关性标注（尚未由业务人员独立复核），涵盖精确地名、同义需求、多目的地、季节、轻松或高强度、长辈同行、相近目的地、知识缺失、过期信息、私人隔离与注入。相关性按稳定 `documentId` 标注，评估时对同文档多个片段去重。空相关集用来测错误非空返回，不将其作为满分 Recall/MRR 样本。

运行时比较 BM25、向量、Java RRF，以及实际可用时的模型 Rerank；不得把离线排序单元测试充当 Elasticsearch/Ollama 的实测，也不得在服务不可用时生成假的性能指标。命令和报告路径以项目 RAG 部署说明为准。该数据集不调用 ChatModel；真实本地 Embedding 允许，收费生成禁止。

标注本身不是评测结果。本文件没有预设任何质量指标。默认生产语料不含负向夹具，因此仅“未命中禁止 ID”还不能证明污染条件下的权限或过期过滤；必须结合受控夹具测试判断。Q20、Q24 也不能靠检索指标证明当前票价正确，须检查上下文是否明确缺少实时资料。

## 适用期和安全更新

validFrom / validUntil 可同时省略或同时为 null；填写时必须为 YYYY-MM-DD 且不倒序。与 sourceUpdatedAt（来源发布时间）分别保存。没有明确旅行日期范围时不引用年度性资料；完全不相交排除，部分相交只适用于快照标出的日期，不描述为全程有效。普通长期资料保持既有检索行为。

2026-10-30 收尾只为大兴安岭文档补充适用期；不改正文或原 26 条评测 qrels。只改这些元数据也会触发 import 更新。沿用 MySQL metadata_json 和 ES 新快照/别名切换，不新增迁移、不清理旧索引。

知识管理须指定最新 JAR（已有正式知识库只做受控 import，不重复初始化）：

```powershell
$latestRagJar = (Resolve-Path './.local/rag-validity-target/smart-trip-backend-0.0.1-SNAPSHOT.jar').Path
./scripts/Invoke-RagKnowledge.ps1 -Jar $latestRagJar -Command import
```

凭据仅使用既有进程环境；不要打印或落盘。完整操作与分日期验收边界见 docs/RAG-OPERATIONS.md。含 ai.llm.enabled=false 的示例属于禁用生成的 RAG 验收/管理命令，不是完整 AI 启动方法；flyway.target=10 仅限此前受控验收，不得永久固定日常开发迁移版本。

2026-10-30 真实更新验收：仍为 8 文档/16 切片，旧快照保留；大兴安岭有效期已写入 ES 与 metadata_json，其他 7 份资料完整不变，重复导入 UNCHANGED。边界、跨年、无日期及部分适用的真实 ES/BGE 回归通过；原 26 条 qrels 未修改，指标变化和完整证据见运维文档最新一节。Redis/完整 AI 生成链路仍未验收。
