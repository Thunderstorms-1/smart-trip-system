# 第四轮真实外部能力接入方案

日期：2026-10-05。当前分支：feat/provider-integration；实施基线：cbaae28，沿用已有分支，没有覆盖第三轮基线 0ae614d。

## 目标与范围

复用 AiTaskService、GeoService 和第三轮行程诊断，形成真实地点 → 路线/天气 → 诊断 → AI 提案 → 差异预览 → 用户确认 → 保存的闭环。保留已有版本校验、固定安排约束和原节点 ID，不另建 AI 业务体系。本轮无数据库迁移，不修改原 smart_trip，不推送远端。

## 五个阶段与验收

1. 接入基础：AI、POI、路线、天气、JS 地图分别报告 DISABLED、QUOTA_UNVERIFIED、KEY_MISSING、BROWSER_KEY_MISSING 或 READY。高德总开关与各功能开关共同生效；JS 地图不依赖 Web 服务 Key。READY 仅表示配置门槛满足，不能等同在线已验收。
2. AI：禁用思考、请求 JSON 对象、显式输出上限、流式用量。关闭自动重试和跳转。接收完整 stop + DONE 后才交给已有结构校验；输出截断、限流、鉴权错误或额度耗尽都不保存调整。供应商鉴权失败不作为应用用户 401。
3. POI/地图：只有用户主动搜索才调用；取消、超时、上下文变化丢弃旧结果。候选必须有有效 POI ID、名称与坐标。选择后仍只持久化地点引用，清空旧地址/经纬度，不新增供应商缓存或坐标存储。路线请求内复用详情解析；无引用时仅用有效 GCJ02 坐标，引用失败不拿无关联的旧坐标替代。
4. 路线/天气：步行与驾车，不扩展公交/实时交通。校验真实距离、时长、轨迹，不生成替代点或直线。保留路线 30 秒总期限与单调用最多 15 秒。天气只接受供应商实际返回的匹配日期；过去或较远日期保留零调用预筛选，缺日期或温度即不可用，不编造小时级天气。
5. 闭环在线验收：最小 AI 探测通过后再走生成、诊断调整、预览、确认、重载和版本冲突；随后依次验收高德搜索、步行/驾车、近期天气、JS 地图及移动端。每阶段记录实际结果，离线固定响应与真实在线结果分别列示。

## 账号与当前阻塞

用户已确认：Qwen3.8-Flash，地址 https://maas.qianwenaiapi.com/compatible-mode/v1，现有 Key 属于该平台和免费额度账号，额度仍有、到期无、“用完即停”已打开。此为用户控制台确认，未当作供应商调用成功证据。

本机最小 AI 探测设 64 输出 Token、30 秒上限、无重试；当前执行环境外网被拒绝 EACCES，未取得 HTTP 响应或用量。因此 Key、模型权限、免费保护实际生效和真实 JSON/SSE 兼容仍待在线验收。未改私有配置地址或打开开关。高德 Key/对应权限仍待配置及核实。

## 后续启用顺序

在有网络权限的本机终端先运行已交付脚本（不要把 Key 放到命令行）：

~~~powershell
node scripts/Probe-Ai.cjs --confirm-free-tier-only --base-url https://maas.qianwenaiapi.com/compatible-mode/v1
~~~

只有用户确认该 Key 与账号、额度和停止保护匹配时才使用确认参数。脚本读取已有本机 Key，结果写入被 Git 忽略的 .local/provider-probe.json，只保存脱敏元数据。64 是输出上限，输入也可能计费；本脚本不能替代供应商用量核算。

探测通过后先用临时配置覆盖匹配的地址和 AI 开关做少量真实验收；失败恢复关闭。高德按 POI → 路线 → 天气 → 地图逐项开启，其他功能继续关闭。应用布尔开关只是调用门槛，不构成供应商免费额度保证。

## 接入依据

- [用户提供的平台 Key 与 BaseURL 文档](https://platform.qianwenai.com/docs/api-reference/preparation/api-key)
- [平台思考参数](https://platform.qianwenai.com/docs/developer-guides/text-generation/thinking)
- [平台额度错误](https://platform.qianwenai.com/docs/api-reference/preparation/error-messages)
- [高德天气查询](https://lbs.amap.com/api/webservice/guide/api/weatherinfo)
- [高德错误信息](https://lbs.amap.com/api/webservice/guide/tools/info)

平台实际模型参数、权限与额度最终以该账号在线响应及控制台记录验收，不用其他平台规则替代。

## 本轮阶段结果

后端95、前端46、浏览器13、本机接口17项通过；类型检查及生产构建通过。真实AI因本机EACCES未取得响应，高德仍待Key/权限，所有私有开关保持关闭。完整验收报告归档到项目旁的 SmartTrip项目笔记/99-开发日志。
