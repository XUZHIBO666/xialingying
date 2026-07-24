# 天气模块目标架构设计

> 状态：已确认
>
> 目标方案：方案 B——统一 Tool 路由，业务服务与天气供应商解耦
>
> 范围：天气模块架构，不包含本次代码实现

## 1. 背景与目标

当前天气能力分散在以下位置：

- `BotController` 通过“天气”关键词和 `extractCity()` 执行快速路由。
- `WeatherTool` 通过 Spring AI `@Tool` 暴露给 `ReactAgent`。
- `WeatherController` 直接调用静态 `WeatherUtil`。
- `WeatherUtil` 同时承担参数校验、HTTP 调用、JSON 解析、翻译、格式化和异常转换。
- 旧 `Tool`/`ToolRegistry` 与当前 Spring AI Tool 体系并存。
- 独立 `weather-cli` 目录包含另一套 Open-Meteo 天气实现。

这些路径会产生重复查询、行为差异、测试困难和工具协议混乱。目标架构将天气定位为 AI 助手的一项确定性业务能力：

1. LLM 负责理解自然语言、决定是否调用天气工具和生成最终回复。
2. 天气模块负责校验查询、调用供应商并返回结构化事实。
3. Tool、REST 等入口共享同一个天气应用服务。
4. 外部天气供应商可替换，但 MVP 只启用一个供应商。
5. 支持当前天气和未来三天逐日预报。

## 2. 范围

### 2.1 本期包含

- 按城市或地区查询当前天气。
- 查询今天、明天、后天或未来三天逐日预报。
- Spring AI Alibaba `ReactAgent` 工具调用。
- REST API 复用同一业务服务。
- 城市缺失、城市不明确、城市不存在、超时、供应商错误和解析错误的分类。
- 结构化领域结果、可配置超时和短时缓存。
- 确定性单元测试与少量外部集成测试。

### 2.2 本期不包含

- 天气预警、空气质量、生活指数和分钟级降水。
- 地图、GPS 定位和微信地理位置消息。
- 多供应商自动故障切换。
- 独立微服务、MCP Server 或分布式缓存。
- 流式天气响应和复杂监控平台。

## 3. 架构原则

### 3.1 单一路由

普通文本和 ASR 转写统一进入 `AIService`/`ReactAgent`。天气意图由 LLM 根据 Tool 描述识别，不再由 `BotController` 维护天气关键词分支。

### 3.2 Tool 是适配器

`WeatherTool` 只负责：

- 定义 LLM 可见的工具名称、描述和参数。
- 将工具参数转换为 `WeatherQuery`。
- 调用 `WeatherService`。
- 将 `WeatherReport` 转换为结构化工具结果。

它不直接执行 HTTP 请求，不拼接面向用户的自然语言，也不吞掉所有异常并返回普通字符串。

### 3.3 业务与供应商解耦

`WeatherService` 不依赖 wttr.in、Open-Meteo 等具体 JSON 结构，只依赖 `WeatherProvider`。外部响应必须在 Provider 内转换为统一领域模型。

### 3.4 结构化事实优先

WeatherService 和 Provider 返回结构化对象，不返回用竖线拼接的天气字符串。只有 Tool 适配层或 REST 序列化层负责协议转换，最终自然语言由 LLM 生成。

### 3.5 MVP 控制

只启用一个天气供应商。允许定义 `WeatherProvider` 接口以保持边界，但不实现运行期供应商编排、自动切换或复杂策略。

## 4. 核心组件

| 组件 | 类型 | 职责 |
|---|---|---|
| `WeatherTool` | Tool 适配器 | 暴露 Spring AI Tool Schema，转换请求和结果 |
| `WeatherService` | 应用服务 | 校验查询、解析相对日期、调用 Provider、统一错误 |
| `WeatherProvider` | 出站端口 | 定义天气供应商能力 |
| `OpenMeteoWeatherProvider` | 出站适配器 | 调用 Open-Meteo 地理编码和天气 API，完成字段映射 |
| `LocationResolver` | 领域服务/适配器 | 地点标准化、地理编码、同名地点判断 |
| `WeatherQuery` | 请求模型 | 地点、目标日期、查询类型和单位 |
| `WeatherReport` | 结果模型 | 规范地点、时区、更新时间、当前天气、预报 |
| `DailyForecast` | 结果模型 | 日期、天气码、最高/最低温、降水概率等 |
| `WeatherException` | 业务异常 | 表达可判定的天气失败类别 |
| `WeatherController` | REST 适配器 | HTTP 参数绑定和响应映射 |
| `WeatherProperties` | 配置 | URL、连接/读取超时、缓存时长、供应商配置 |

### 4.1 WeatherQuery

MVP 字段：

- `location`：必需，城市或地区名称。
- `date`：可选，规范化后的 ISO 日期；为空表示当前天气。

LLM Tool Schema 可接受自然语言日期，例如“明天”，但 WeatherTool 必须将其交给 WeatherService 结合目标地点时区解析。后续如果模型稳定性不足，可要求 LLM 直接生成 ISO 日期。

### 4.2 WeatherReport

至少包含：

- 用户输入地点和供应商规范化地点。
- 国家或一级行政区，用于同名地点消歧。
- 地点时区。
- 数据观测或更新时间。
- 当前温度、体感温度、湿度、风速、风向和天气码。
- 最多三天的逐日预报：日期、最高/最低温、天气码、降水概率。
- 数据来源标识。

数值保留类型和单位，不在领域对象中拼接 `"23°C"` 一类展示字符串。

## 5. 数据流

```text
微信文字/ASR
  → Bot 消息入口
  → AIService / ReactAgent
  → LLM 产生 query_weather Tool Call
  → WeatherTool 校验协议参数
  → WeatherService 解析地点和日期
  → WeatherProvider 调用外部 API
  → WeatherReport
  → WeatherTool 返回结构化 Tool Result
  → ReactAgent 基于事实生成自然语言
  → 微信文字回复或 TTS 语音回复
```

REST 数据流：

```text
HTTP Request
  → WeatherController
  → WeatherService
  → WeatherProvider
  → WeatherReport
  → HTTP Response DTO
```

REST Controller 与 WeatherTool 不互相调用，它们只共享 WeatherService。

## 6. LLM 与 Tool 交互

### 6.1 Tool 定义

推荐工具名称：`query_weather`。

工具描述必须明确：

- 用户询问当前温度、天气、降雨、是否带伞或未来天气时使用。
- 涉及实时或未来天气时不得依靠模型记忆回答。
- 缺少地点时不要猜测，应请求用户补充。
- 必须区分当前实况与天气预报。

MVP 参数：

- `location`：城市或地区，必需。
- `date`：查询日期，可选；支持 ISO 日期或“今天/明天/后天”。

### 6.2 成功流程

以“明天杭州会下雨吗”为例：

1. LLM 选择 `query_weather`。
2. Tool Call 参数包含 `location=杭州`、`date=明天`。
3. WeatherService 根据杭州时区解析目标日期。
4. Provider 返回对应逐日预报。
5. Tool Result 返回规范地点、日期、降水概率、天气码和温度。
6. LLM 用简洁中文生成最终回复，不修改数值和日期。

### 6.3 缺少地点

“明天会下雨吗”没有可从会话上下文可靠获得的地点时：

1. LLM 不应调用带猜测地点的工具。
2. LLM 应询问“你想查询哪个城市？”
3. 用户补充地点后再调用工具。

如果历史消息中存在最近一次明确的天气地点，允许 ReactAgent 使用该地点完成“那后天呢？”等追问。

### 6.4 工具结果协议

Tool Result 应包含机器可辨识状态：

- `SUCCESS`
- `LOCATION_REQUIRED`
- `LOCATION_AMBIGUOUS`
- `LOCATION_NOT_FOUND`
- `INVALID_DATE`
- `PROVIDER_TIMEOUT`
- `PROVIDER_UNAVAILABLE`
- `PROVIDER_RESPONSE_INVALID`

失败结果只提供适合 LLM 判断的安全信息，不返回供应商响应正文、内部 URL、堆栈或密钥。

### 6.5 调用约束

- 单条用户消息最多允许两轮天气工具调用。
- 天气 Tool 的执行时间计入 Agent 总体超时。
- Tool 成功后，系统提示要求 LLM 直接基于结果回答，避免无理由重复查询。
- 最终回复不得编造 Tool Result 中不存在的降水概率、温度或预警信息。

## 7. 路由调整

目标状态：

- 删除 `BotController` 中天气关键词判断和 `extractCity()`。
- 文本天气、隐式天气意图和上下文追问统一交给 ReactAgent。
- `AIService.chatWithTools()` 与 `chat()` 若保持同义，应收敛为一个清晰入口，避免调用方误以为存在两套行为。
- 没有配置 LLM 时，REST 天气 API 仍可工作；微信自然语言天气能力返回统一的 AI 未配置提示。

不保留天气关键词快速路径。快速路径与 Tool Calling 并存会造成重复规则、重复查询和会话行为差异。

## 8. 天气供应商选择

本期固定使用 Open-Meteo：

- Geocoding API 将地点名称解析为经纬度、规范地点和时区。
- Forecast API 获取当前天气和未来三天预报。
- 请求显式指定温度、风速和时区参数，避免依赖供应商默认单位。

仓库 `weather-cli` 中的实现只作为字段和行为参考，不能直接复制；主项目需要按 Spring Bean、异常分类、安全日志和可测试性要求重新适配。

`WeatherProvider` 接口用于隔离外部协议，但本期不实现 wttr.in 适配器、供应商选择器或自动故障切换。迁移完成后，主项目不再通过 WeatherUtil 访问 wttr.in。

## 9. 缓存、超时与并发

- 连接超时与读取超时通过 `WeatherProperties` 配置。
- 当前天气按“规范地点”缓存 5 至 10 分钟。
- 逐日预报按“规范地点 + 日期”缓存 30 至 60 分钟。
- MVP 可使用进程内有界缓存，不引入 Redis 依赖。
- 只对连接失败、部分 5xx 等瞬时错误执行至多一次短退避重试。
- 参数错误、地点不存在和响应解析错误不重试。
- REST 批量查询必须限制城市数量；建议最多 10 个。
- 不允许无界并发创建天气请求。

## 10. 错误处理

WeatherService 将外部错误转换为稳定的业务分类：

| 分类 | 用户体验 |
|---|---|
| 地点缺失 | 请求用户补充城市 |
| 地点不明确 | 给出少量候选，要求用户确认 |
| 地点不存在 | 提示检查城市名称 |
| 日期超出范围 | 说明当前只支持未来三天 |
| 超时/供应商不可用 | 提示暂时无法查询，稍后重试 |
| 供应商响应异常 | 使用通用失败提示并记录内部错误类型 |

LLM 只能看到安全错误状态和用户可理解的简短说明。日志可以记录错误类型、供应商和耗时，不记录完整用户消息、完整城市列表或外部响应正文。

## 11. 测试策略

### 11.1 单元测试

- WeatherService：当前天气、相对日期、超出范围、地点缺失和异常映射。
- Provider：使用固定 JSON 或 MockWebServer 验证字段映射。
- WeatherTool：Tool 注解、Schema、参数转换和结构化错误结果。
- WeatherController：使用 MockMvc 验证请求绑定和 HTTP 响应。

### 11.2 Agent 集成测试

Mock ChatModel 或工具调用边界，覆盖：

- “杭州天气”触发一次天气 Tool。
- “杭州明天会下雨吗”查询预报而非当前实况。
- “今天热不热”能够触发 Tool。
- “明天会下雨吗”在无地点上下文时追问城市。
- “北京天气”后再问“那后天呢？”能够沿用北京。
- Tool 超时后 LLM 不编造天气。

### 11.3 外部集成测试

真实天气 API 测试不进入普通 CI。保留少量手工或定时测试，用于发现供应商协议变化。普通单元测试不得依赖公网、实时温度或供应商可用性。

## 12. 安全与可观测性

- API URL、密钥和超时从配置读取。
- 不记录完整 Tool 参数、外部响应正文和用户消息。
- 城市日志采用必要最少原则；批量请求不输出完整城市列表。
- 记录成功率、耗时、缓存命中和错误分类，不记录天气内容。
- Tool 返回内容设置长度上限。
- REST 批量接口设置数量和字符串长度限制。

## 13. 迁移边界

实现时按以下顺序迁移，确保每一步均可验证：

1. 引入领域模型、WeatherProvider 和 WeatherService。
2. 将现有供应商查询迁入 Provider，并完成确定性测试。
3. WeatherController 改为调用 WeatherService。
4. WeatherTool 改为调用 WeatherService，并返回结构化结果。
5. 增加预报查询和相对日期解析。
6. 删除 BotController 天气关键词分支和 `extractCity()`。
7. 确认无引用后删除静态 WeatherUtil、旧 Tool/ToolRegistry。
8. 明确 `weather-cli` 为归档、独立项目或删除对象，不与主模块共享实现。

迁移期间不升级 Java、Spring Boot、Spring AI Alibaba 或 iLink SDK，除非现有版本明确阻塞 Tool 返回对象或 Agent 调用。

## 14. 验收标准

- 所有微信天气自然语言查询统一通过 ReactAgent 的 Spring AI Tool 调用。
- `BotController` 不再包含天气关键词路由和城市正则提取。
- WeatherTool、WeatherController 不直接访问外部天气 API。
- WeatherService 不包含具体供应商 JSON 字段。
- 当前天气和未来三天预报均返回结构化领域结果。
- “今天热不热”“明天会下雨吗”“那后天呢？”具有明确、可测试的行为。
- Tool 失败时 LLM 不生成虚构天气。
- 普通测试不访问公网，真实供应商测试与 CI 单元测试分离。
- 日志不包含用户完整消息、外部响应正文、密钥或完整批量城市列表。
- 同一查询可命中有界短时缓存，超时和错误分类可配置并可测试。

## 15. 明确决策

- 采用模块化单体，不拆分天气微服务。
- 使用 Spring AI Alibaba 原生 `@Tool` 作为唯一工具体系。
- 不保留天气关键词快速路径。
- 业务层返回结构化结果，不返回自然语言天气字符串。
- MVP 只启用 Open-Meteo 一个天气供应商。
- MVP 支持当前天气和未来三天预报。
- REST 与 LLM Tool 共用 WeatherService。
