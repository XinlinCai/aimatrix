# AIMatrix Server

基于 **Spring AI Alibaba** 构建的 AI Agent 后端服务，聚焦于企业级 AI 应用编排与执行。

## 项目定位

AIMatrix 是一个面向 AI 智能体的后端服务平台，深度集成阿里云 DashScope 大模型服务，提供 Agent Framework 框架支持，实现任务分解、工具调用、记忆管理等智能体核心行为。当前核心业务为 **智能任务规划助手**。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 运行时语言 |
| Spring Boot | 3.5.9 | 基础框架 |
| Spring AI Alibaba | 1.1.2.0 | AI Agent 框架 + DashScope 集成 |
| PostgreSQL | 16+ | 关系数据库 |
| PgVector | - | 向量存储（支持语义记忆） |
| Project Reactor | - | 响应式流支持（Flux/Mono） |
| Maven | - | 项目构建工具 |

## 项目结构

```
src/main/java/com/vectrans/aimatrix/
├── AimatrixApplication.java      # 应用入口
├── config/
│   └── AgentConfig.java          # ReactAgent 配置（系统指令、模型、工具）
├── controller/
│   └── AgentController.java      # REST API 端点
├── dto/
│   ├── AgentRequest.java         # 请求体：消息 + 会话ID
│   └── AgentResponse.java        # 响应体：回复 + 会话ID
├── entity/
│   ├── DailyPlan.java            # 每日计划实体
│   ├── TaskItem.java             # 任务实体
│   └── enums/
│       ├── PlanStatus.java       # 计划状态（PENDING/COMPLETED）
│       └── TaskStatus.java       # 任务状态（UNCOMPLETED/COMPLETED/DELETED）
├── repository/
│   ├── DailyPlanRepository.java  # 每日计划 JPA 仓库
│   └── TaskItemRepository.java   # 任务 JPA 仓库
├── service/
│   ├── AgentService.java         # Agent 核心服务接口
│   ├── TaskPlanService.java      # 任务规划业务接口（5条业务线）
│   └── impl/
│       ├── AgentServiceImpl.java # Agent 流式和阻塞式对话实现
│       └── TaskPlanServiceImpl.java # 任务规划业务实现
└── tool/
    └── TaskTools.java            # Agent 工具集（@Tool 注解暴露给 LLM）
```

## 核心业务：智能任务规划助手

Agent 通过 ReAct 模式（思考→行动→观察循环）与用户交互，覆盖 **5 条核心业务线**：

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

## 快速开始

### 环境要求

- JDK 17+
- PostgreSQL 16+（需启用 pgvector 扩展）
- Maven 3.8+

### 配置

1. 复制 `.env` 文件并配置环境变量（已预填默认值）
2. 关键配置项：

```bash
# 数据库
DB_HOST=localhost
DB_PORT=5432
DB_NAME=aimatrix
DB_USERNAME=xinlin
DB_PASSWORD=***

# DashScope 大模型
AI_DASHSCOPE_API_KEY=sk-***
AI_DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com
AI_DASHSCOPE_CHAT_MODEL=glm-5.2
AI_DASHSCOPE_EMBEDDING_MODEL=text-embedding-v3

# Agent
AGENT_NAME=aimatrix-agent
AGENT_MAX_ITERATIONS=10
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

```bash
# 运行所有测试
./mvnw test

# 运行指定测试类
./mvnw test -Dtest=TaskPlanServiceTest
./mvnw test -Dtest=AgentE2ETest
```

测试覆盖：
- **TaskPlanServiceTest**: 5 条业务线的完整集成测试（18 个测试用例）
- **AgentE2ETest**: 端到端核心流程测试（7 个有序步骤）
- **Repository 测试**: JPA 数据访问层测试

## 设计要点

- **ReAct Agent**: 基于 `spring-ai-alibaba-agent-framework` 的 ReactAgent，支持思考→行动→观察循环
- **@Tool 注解**: 通过 Spring AI 的 `@Tool` 注解将 Java 方法暴露为 Agent 可用工具，LLM 自动判断调用时机
- **状态不可逆**: COMPLETED 状态不允许回退，违反该规则时 Agent 会引导用户重新创建任务
- **单用户模式**: 当前 userId 固定为 1，后续可对接认证体系后从请求中动态获取
- **流式响应**: 支持 `Flux<String>` 流式输出，适配前端实时展示需求
