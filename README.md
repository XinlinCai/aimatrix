# AIMatrix Server

基于 **Spring AI Alibaba** 构建的 AI Agent 后端服务，聚焦于企业级 AI 应用编排与执行。

## 项目定位

AIMatrix 是一个面向 AI 智能体的后端服务平台，深度集成阿里云 DashScope 大模型服务，提供 Agent Framework 框架支持，实现任务分解、工具调用、记忆管理等智能体核心行为。当前核心业务为 **智能任务规划助手**。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 运行时语言 |
| Spring Boot | 3.5.9 | 基础框架 |
| Spring AI | 1.1.2 | 模型抽象、工具调用、向量存储 |
| Spring AI Alibaba | 1.1.2.0 | AI Agent 框架（ReactAgent）+ DashScope 集成 |
| PostgreSQL | 16+ | 关系数据库（业务数据） |
| PgVector | - | 向量存储（长期记忆语义检索） |
| Redis | 7+ | 短期记忆（会话状态）存储，本地验证于 8.x |
| Redisson | 3.22.0 | Redis 客户端（框架 RedisSaver 依赖） |
| Project Reactor | - | 响应式流支持（Flux/Mono） |
| Maven | 3.8+ | 项目构建工具 |

## 项目结构

```
src/main/java/com/vectrans/aimatrix/
├── AimatrixApplication.java           # 应用入口
├── config/
│   ├── AgentConfig.java               # ReactAgent 装配（模型、工具、Hook、拦截器、Saver）
│   └── RedisConfig.java               # RedissonClient 装配（框架 RedisSaver 依赖）
├── context/                           # 上下文工程（调用前的裁剪与动态注入）
│   ├── ContextWindowHook.java         # 滑动窗口裁剪 Hook（BEFORE_MODEL，按消息条数裁剪并写回状态）
│   └── DynamicContextInterceptor.java # 动态注入运行时日期与长期记忆（不写回状态）
├── controller/
│   └── AgentController.java           # REST API 端点（阻塞 + SSE 流式）
├── dto/
│   ├── AgentRequest.java              # 请求体：message + sessionId
│   └── AgentResponse.java             # 响应体：reply + sessionId
├── entity/
│   ├── DailyPlan.java                 # 每日计划实体
│   ├── TaskItem.java                  # 任务实体
│   └── enums/
│       ├── PlanStatus.java            # 计划状态（PENDING/COMPLETED）
│       └── TaskStatus.java            # 任务状态（UNCOMPLETED/COMPLETED/DELETED）
├── observability/                     # 进程内轻量观测（采集 → 单行日志 / Micrometer 指标）
│   ├── ObservabilityInterceptor.java  # 模型调用观测拦截器（拦截器链最内层，采集 token/工具/耗时）
│   ├── ObservabilityRecorder.java     # 会话级累加器 + 指标注册 + 事件组装
│   ├── ObservabilitySink.java         # 输出通道接口（采集与输出解耦）
│   ├── LoggingSink.java               # 日志输出通道（单行结构化日志，grep '[OBS]' 检索）
│   └── LlmCallEvent.java              # 一次完整对话的事件快照
├── repository/
│   ├── DailyPlanRepository.java       # 每日计划 JPA 仓库
│   └── TaskItemRepository.java        # 任务 JPA 仓库
├── service/
│   ├── AgentService.java              # Agent 对话服务（阻塞/流式）
│   ├── AgentMemoryService.java        # 长期记忆服务接口（PgVector 语义检索）
│   ├── TaskPlanService.java           # 任务规划业务接口（5 条业务线）
│   └── impl/
│       ├── AgentServiceImpl.java      # Agent 会话与 threadId 管理
│       ├── AgentMemoryServiceImpl.java# 长期记忆读写（按 userId 隔离）
│       └── TaskPlanServiceImpl.java   # 任务规划业务实现
└── tool/
    └── TaskTools.java                 # Agent 工具集（@Tool：任务规划 + remember/recall）

src/test/java/com/vectrans/aimatrix/
├── context/                           # 上下文工程纯单元测试（不启动 Spring 容器）
├── controller/AgentE2ETest.java       # 端到端测试（真实 LLM）
├── observability/                     # 可观测拦截器纯单元测试（不启动 Spring 容器）
├── repository/                        # JPA 数据访问层测试
└── service/                           # 业务与记忆集成测试
```

## 核心业务：智能任务规划助手

Agent 通过 ReAct 模式（思考→行动→观察循环）与用户交互，覆盖 **5 条核心业务线**，并具备**长期记忆**与**通用问答**能力：

### 1. 任务收纳
用户用自然语言描述待办事项，Agent 调用 `collectTask` 自动解析标题、重要性和紧急性并存储。

### 2. 每日计划
- 查询未完成任务列表和近一周计划历史
- 按重要/紧急程度推荐最多 3 件事（含时间段建议和预估工时）
- 用户确认后调用 `createDailyPlan` 写入数据库
- 每日最多安排 3 个任务

### 3. 状态变更
- 标记计划完成时，联动将关联任务状态同步为 COMPLETED
- **不可逆**：已完成的任务不可回退为未完成

### 4. 任务查询
支持按状态筛选查询任务（UNCOMPLETED / COMPLETED），以及按日期查询每日计划。

### 5. 复盘分析
基于近一周的计划数据，统计完成率、每日分布，生成人性化复盘报告和优化建议。

### 6. 长期记忆
用户表达需要长期记住的偏好、习惯或重要事实时，Agent 调用 `remember` 写入 PgVector（按 `userId` 隔离）；
在制定计划或给出个性化建议前，通过 `recall` 按语义相似度召回，亦可由动态拦截器自动注入上下文。

### 7. 通用问答
对与任务规划无关的普通问题（知识问答、概念解释、闲聊等），Agent 直接作答、不触发工具调用。

## 记忆与上下文架构

AIMatrix 将"记忆"分为**短期**与**长期**两层，并在每次模型调用前叠加一层**上下文工程**对输入做动态加工。

### 短期记忆（会话状态）

- **载体**：由框架 `RedisSaver`（基于 Redisson）持久化到 Redis，整段会话状态序列化后存储，**非加密**（序列化 + Base64 编码）
- **入口**：`AgentServiceImpl` 以请求中的 `sessionId` 作为 LangGraph 的 `threadId`，相同 `sessionId` 复用同一会话状态
- **策略**：`ContextWindowHook`（`BEFORE_MODEL` 钩子）在每次模型调用前按**消息条数**（默认 20）裁剪历史，先回退到最近的 `UserMessage` 边界，避免切断"工具调用—工具返回"配对；裁剪结果**写回图状态**并随 Checkpoint 持久化
- **注意**：框架写入的 Redis 键不设 TTL，需在 Redis 侧配置淘汰策略（见 [环境要求](#环境要求)）

### 长期记忆（语义记忆）

- **载体**：PgVector `vector_store` 表，按 `userId` 元数据隔离
- **读写**：`AgentMemoryService.remember` / `recall`，Agent 通过 `@Tool` 暴露的 `remember` / `recall` 主动存取，也可由动态拦截器在调用前自动注入

### 上下文工程（模型调用前加工）

| 组件 | 时机 | 行为 | 是否写回状态 |
|------|------|------|--------------|
| `ContextWindowHook` | 模型调用前 | 滑动窗口裁剪历史消息 | 是（随 Checkpoint 持久化） |
| `DynamicContextInterceptor` | 模型调用前 | 将"运行时日期"与"长期记忆召回"拼接进系统消息 | 否（仅作用于本次请求） |

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/chat` | 阻塞式聊天 |
| POST | `/api/agent/chat/stream` | 流式聊天（SSE，`text/event-stream`） |

### 请求体

```json
{
  "message": "帮我记一个任务：明天下午三点开会",
  "sessionId": "optional-session-id"
}
```

### 响应体

```json
{
  "reply": "任务收纳成功！...",
  "sessionId": "generated-or-provided-session-id"
}
```

### 调用示例

```bash
# 阻塞式对话
curl -s http://localhost:8080/api/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我记一个任务：明天下午三点开会","sessionId":"demo-001"}'

# 流式对话（SSE）
curl -N http://localhost:8080/api/agent/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我制定今日计划","sessionId":"demo-001"}'
```

## 快速开始

### 环境要求

- JDK 17+
- PostgreSQL 16+（需启用 pgvector 扩展）
- Redis 7+（短期记忆会话状态存储）
- Maven 3.8+

> **Redis 运维提示**：框架 `RedisSaver` 写入的会话键**不设置 TTL**。为避免内存无限增长，
> 需在 Redis 侧配置内存上限与淘汰策略，例如 `maxmemory 256mb` + `maxmemory-policy allkeys-lru`。

### 配置

1. 复制 `.env` 文件并配置环境变量（已预填默认值；`.env` 已被 `.gitignore` 忽略）
2. 关键配置项：

```bash
# PostgreSQL（业务数据）
DB_HOST=localhost
DB_PORT=5432
DB_NAME=aimatrix
DB_USERNAME=xinlin
DB_PASSWORD=***

# Redis（短期记忆）
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=0

# PgVector（长期记忆）
PGVECTOR_INDEX_TYPE=HNSW
PGVECTOR_DISTANCE_TYPE=COSINE_DISTANCE
PGVECTOR_DIMENSIONS=1024
PGVECTOR_TABLE_NAME=vector_store

# DashScope 大模型
AI_DASHSCOPE_API_KEY=sk-***
AI_DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com
AI_DASHSCOPE_CHAT_MODEL=qwen3.7-max
AI_DASHSCOPE_EMBEDDING_MODEL=qwen3.7-text-embedding
AI_DASHSCOPE_EMBEDDING_DIMENSIONS=1024

# Agent
AGENT_NAME=aimatrix-agent
AGENT_MAX_ITERATIONS=10
# 短期记忆窗口：单会话保留的最大消息条数（默认 20）
AGENT_MEMORY_WINDOW_SIZE=20
# 长期记忆召回条数（默认 5）
AGENT_MEMORY_RECALL_TOP_K=5

# 可观测（进程内轻量观测）
# 总开关：关闭后不采集、不打印，行为与未接入观测时完全一致
AGENT_OBS_ENABLED=true
# 单次模型调用耗时超过该阈值时，日志中标记 slow=true（毫秒）
AGENT_OBS_SLOW_CALL_THRESHOLD_MS=5000
# 是否在日志中输出提示词/回复原文（默认关闭，避免敏感信息外泄与日志膨胀）
AGENT_OBS_LOG_PAYLOAD=false
```

### 启动

```bash
# 开发模式
./mvnw spring-boot:run

# 构建并运行
./mvnw clean package -DskipTests
java -jar target/aimatrix-server-0.0.1-SNAPSHOT.jar
```

服务默认启动在 `http://localhost:8080`。

### 测试

测试按 **纯单元测试 → 集成测试 → 端到端测试** 三层组织，越靠后外部依赖越重：

```bash
# 全量测试（含 E2E，需可访问 DashScope API）
./mvnw test

# 仅纯单元测试（不启动 Spring 容器，无需数据库 / 大模型）
./mvnw test -Dtest=ContextWindowHookTest,DynamicContextInterceptorTest,ObservabilityInterceptorTest

# 业务与数据访问集成测试（需 PostgreSQL，部分需 PgVector）
./mvnw test -Dtest=TaskPlanServiceTest,AgentMemoryServiceTest,DailyPlanRepositoryTest,TaskItemRepositoryTest

# 端到端测试（真实调用 LLM）
./mvnw test -Dtest=AgentE2ETest
```

| 层次 | 测试类 | 用例数 | 依赖 |
|------|--------|-------:|------|
| 纯单元测试 | `ContextWindowHookTest` | 5 | 无（不启动 Spring 容器） |
| 纯单元测试 | `DynamicContextInterceptorTest` | 3 | 无（桩实现记忆服务） |
| 纯单元测试 | `ObservabilityInterceptorTest` | 9 | 无（桩 `ModelCallHandler`） |
| 启动测试 | `AimatrixApplicationTests` | 4 | Spring 上下文 |
| 业务集成测试 | `TaskPlanServiceTest` | 18 | PostgreSQL |
| 业务集成测试 | `AgentMemoryServiceTest` | 4 | PostgreSQL + PgVector + DashScope |
| 数据访问测试 | `DailyPlanRepositoryTest` | 7 | PostgreSQL |
| 数据访问测试 | `TaskItemRepositoryTest` | 6 | PostgreSQL |
| 端到端测试 | `AgentE2ETest` | 7 | PostgreSQL + Redis + DashScope（真实 LLM） |
| **合计** | — | **63** | `./mvnw -o test` |

## 可观测

采用**进程内轻量观测**，不接入任何外部可观测平台，只用两个互补的出口：

| 出口 | 内容 | 查看方式 | 模式 |
|------|------|----------|------|
| 自研埋点 | 一次对话一行结构化日志（token / 耗时 / 轮次 / 裁剪 / 记忆 / 工具 / 状态） | `grep '[OBS]' 应用日志` | 主动推 |
| 框架指标 | Spring AI 自动注册的 Micrometer 指标 | `curl localhost:8080/actuator/metrics/{name}` | 被动拉 |

单行日志示例：

```text
[OBS] session=demo-001 user=1 model=qwen3.7-max iter=2/10 in=1200 out=180 total=1380 costMs=3200 wallMs=3600 trim=6/26 memInject=3 memRecall=hit tools=queryTasks,remember toolCalls=2 status=OK empty=false slow=false
```

示例中各字段的**实际可达口径**（拿日志做诊断前必读）：

| 字段 | 可达口径 | 说明 |
|------|----------|------|
| `model` | 请求侧显式模型名 > 配置项兜底 | `ReactAgent` 只把 `ChatModel` 交给框架，请求侧 `options.getModel()` 取不到模型名，此时回退配置项 `spring.ai.dashscope.chat.options.model`；两者都为空才渲染为 `-` |
| `trim` | `removed/original`，未裁剪时 `removed=0` | `original` **恒为**本轮模型调用前的上下文消息数（未裁剪时即当前上下文规模），因此 `trim=0/N` 表达「本轮未裁剪、当前 N 条」，不再与「取不到值」的二义混淆 |
| `memInject` | 注入的长期记忆条数 | 命中时的条数由本字段承载，不再拼接进 `memRecall` |
| `memRecall` | `hit` / `miss` / `-` 三态 | `hit` 召回到内容、`miss` 已发起召回但无可用内容、`-` 未发起召回（缺 `user_id` 或 `query`）；三者互斥，不存在 `hit:N` 形态 |
| `tools` / `toolCalls` | 工具名集合 / 调用次数 | 仅名字与次数，**不含参数、返回值与单次耗时** |

自研 `agent.*` 指标（存内存，经 Actuator 暴露）：

| 指标 | 含义 |
|------|------|
| `agent.react.iterations` | ReAct 循环轮次 |
| `agent.react.limit.reached` | 触达最大轮次上限 |
| `agent.context.trimmed.messages` | 滑动窗口裁剪的消息条数（仅 `removed > 0` 时累加，无裁剪轮次不计入，避免把 `0` 条当样本） |
| `agent.memory.injected` | 注入的长期记忆条数 |
| `agent.memory.recall` | 召回结果（tag `result=hit\|miss`）；仅由记忆服务在**真实检索**处上报，缓存命中不重复计数；「已尝试未命中」（日志里的 `miss`）只写事件字段、不打指标 |
| `agent.context.degrade` | 上下文降级（tag `cause=memory_injection\|memory_recall`） |
| `agent.answer.empty` | 空回答次数 |

框架指标（`management.endpoints.web.exposure.include` 暴露 `metrics` 后可见）：

| 指标 | 来源 | 说明 |
|------|------|------|
| `gen_ai.client.operation` | Spring AI | 模型调用耗时与 token 用量。**chat 与 embedding 混在同一条指标里**（`gen_ai.operation.name` 的取值同时含 `chat` 与 `embedding`），取数必须带 `?tag=gen_ai.operation.name:chat` 过滤，否则 embedding 调用会被一并计入 |
| `db.vector.client.operation` | Spring AI | 向量库检索操作 |
| ~~`spring.ai.tool`~~ | — | **本项目不会产出**：自研 `AgentToolNode` 绕过了 Spring AI 的工具观测链路，`/actuator/metrics/spring.ai.tool` 会 404，工具调用一律由自研拦截器采集 |

> **读 `MAX` 的坑**：Micrometer 未开启直方图时，`*_max` 恒返回 `0.0`（`TimeWindowMax` 语义），这不代表「最大耗时为 0」或「无数据」。要回答「单次调用最坏延迟」，需先开启直方图，例如 `management.metrics.distribution.percentiles-histogram.gen_ai.client.operation=true`。当前只关心总量与均值，不开也够用。

> **工具观测边界**：本项目的工具观测是**降级版**——只有工具名与调用次数（日志 `tools` / `toolCalls`），没有入参、返回值与单次耗时。要定位工具级性能问题，需自行在 `AgentToolNode` / `TaskTools` 补埋点。

设计约束：

- **采集与输出解耦**：采集层只依赖 `ObservabilitySink` 接口，本期仅实现日志通道 `LoggingSink`；后续接入平台只需新增一个 Sink 实现，埋点代码零改动
- **拦截器位置**：`ObservabilityInterceptor` 必须位于拦截器链**最内层**（`AgentConfig` 中注册在最后），这样其 `handler.call()` 才等价于一次真实模型调用，不会把记忆检索耗时误计入模型耗时
- **会话级累加器**：以 `sessionId` 为键的 `ConcurrentHashMap` 累加（流式跨线程，**不能用 ThreadLocal**）；由 `AgentServiceImpl` 控制 `startSession` / `finish` 生命周期，拦截器只负责填充
- **流式 token 取值**：DashScope 流式返回的是**累计值**，故只取**最后一个非零 Usage**，严禁逐 chunk 累加；流终止（`doFinally`）时回填一次，与「一次模型调用」一一对应
- **高基数红线**：`userId` / `sessionId` / 工具名仅作日志字段，**严禁用作指标 tag**，避免指标基数爆炸
- **默认脱敏**：日志仅记录消息长度；提示词与回复原文需 `AGENT_OBS_LOG_PAYLOAD=true` 才输出，且严格渲染为单行
- **开关可关断**：`AGENT_OBS_ENABLED=false` 时零采集、零输出、零副作用，行为与未接入观测时完全一致
- **三态优先于冗余**：`memRecall` 只表达召回结果（`hit`/`miss`/`-`），条数交给 `memInject`；`trim` 的 `original` 一律上报，用 `removed=0` 表达「本轮未裁剪」，从字段设计上消除「取不到值」与「值为 0」的二义

## 设计要点

- **ReAct Agent**: 基于 `spring-ai-alibaba-agent-framework` 的 ReactAgent，支持思考→行动→观察循环
- **@Tool 注解**: 通过 Spring AI 的 `@Tool` 注解将 Java 方法暴露为 Agent 可用工具，LLM 自动判断调用时机
- **状态不可逆**: COMPLETED 状态不允许回退，违反该规则时 Agent 会引导用户重新创建任务
- **单用户模式**: 当前 userId 固定为 1，后续可对接认证体系后从请求中动态获取
- **分层记忆**: 短期记忆（会话状态）存 Redis，由框架 RedisSaver 维护；长期记忆存 PgVector，按 userId 隔离
- **裁剪对齐语义边界**: 滑动窗口按消息条数裁剪时，先回退到最近的 `UserMessage` 边界，避免切断"工具调用—工具返回"配对导致模型报错
- **运行时上下文与持久状态解耦**: 日期、长期记忆等每次都可能变化的上下文由拦截器在调用前临时拼接，不写回会话状态，避免污染 Checkpoint
- **降级容错**: 动态上下文注入失败时自动降级为原始请求，记录 warn 日志但不阻断主链路
- **流式响应**: 支持 `Flux<String>` 流式输出，适配前端实时展示需求
