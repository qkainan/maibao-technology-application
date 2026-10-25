# maibao-technology-application

> 一个 AI 应用平台的关键技术最小实现：多厂商模型适配、SSE 流式、工作流循环、异步任务、发布状态机与并发控制、密钥加密、用量与成本。每个点都拆成可运行、可单独验证的代码。

技术栈：Java 17 · Spring Boot 3.2 · Spring Data JPA · MySQL 8（H2 可选）· RabbitMQ · Maven

---

## 为什么做这个项目

AI 应用平台的骨架高度相似：一次对话要穿过适配层与流式链路，一次发布要经过状态机与并发抢占，一条消息要保证幂等。这些部分在业务代码里往往埋得很深，最后只能停在"用了什么"。

这个项目把它们各自切成最小单元：每个模块只保留一处核心机制，配对应的单测，以及可以直接调用的接口。

| 技术点 | 在这个项目里 |
|---|---|
| 适配器模式统一多厂商出入参 | `adapter/` — `AiProviderAdapter` + `AdapterRegistry`，带 `capabilities()` 与错误码归一 |
| 循环节点（循环变量 / 终止条件 / 轮次上限） | `workflow/LoopNode.java` |
| SSE 流式链路（真流式 + 帧解析 + 心跳 + seq + 取消） | `chat/stream/` — `OpenAiSseDecoder`、`SseStreamSession` |
| RabbitMQ（outbox + confirm/returns + 重试 + 死信 + 对账） | `mq/` — `OutboxPublisher`、`TaskConsumer`、`TaskProcessor` |
| 多线程与锁（状态抢占、乐观锁、卡死回收） | `publish/` — `PublishRepository.claim`、`PublishExecutor` |
| 密钥加密（密文入库 + 归属校验） | `security/` — `AesGcmCipher`、`KeyVaultService` |
| 构建与发布（分层缓存 + 多阶段） | `Dockerfile`、`deploy/nginx.conf`、`.github/workflows/ci.yml` |
| 用量与成本（对话埋点 + 按天聚合） | `usage/` — `UsageService`、`TokenEstimator`、`CostCalculator` |

每个技术点的设计取舍见 [`docs/01-设计取舍.md`](docs/01-设计取舍.md)。

---

## 快速开始

```bash
# 0. 建库并建表（MySQL 8）
mysql -u root -p -e "CREATE DATABASE maibao_technology_application DEFAULT CHARSET utf8mb4;"
mysql -u root -p maibao_technology_application < docs/schema.sql
cp application-local.yml.example application-local.yml   # 填 spring.datasource.password

# 1. 跑起来（需要本地 MySQL 与 RabbitMQ，见下面两节）
mvn spring-boot:run

# 2. 打开功能测试台（按 Tab 覆盖全部接口）
# http://localhost:8080/
```

### 常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat?prompt=...&model=mock:echo&userId=u1` | 同步对话（`userId` 用于用量统计） |
| GET | `/api/chat/stream?prompt=...` | **SSE 流式**（浏览器直接打开看） |
| POST | `/api/workflow/run` | 跑一个「LLM 节点 + 循环 3 轮」的工作流 |
| POST | `/api/workflow/validate` | 图校验：传邻接表，返回环上的边 |
| POST | `/api/task/submit?payload=...` | 提交异步任务（投递到 RabbitMQ，返回 taskId） |
| POST | `/api/publish?nodeId=n1&delaySeconds=10` | 建定时发布记录；到点后自动抢占并执行 |
| POST | `/api/publish/{id}/finish?success=true` | 手动收尾；返回 `false` 表示记录已不在 `PUBLISHING` |
| POST | `/api/publish/{id}/race?threads=20` | **并发验证**：20 线程抢一条，应返回 1 |
| POST | `/api/keys?...` | 写入密钥（密文入库，返回脱敏串） |
| GET | `/api/keys/{id}?ownerId=other` | **越权校验**：返回 4030 |
| GET | `/api/usage/queries?userId=u1&limit=20` | 最近对话流水 |
| GET | `/api/usage/cost?userId=u1` | 成本明细（模型 × 天） |
| GET | `/api/usage/summary?userId=u1` | 累计请求数 / token / 成本 |

### SSE 流式

- 控制器只建 `SseEmitter`，读取在独立线程池里跑，容器线程立刻释放；
- 上游字节流按行喂给 `OpenAiSseDecoder`：自攒 buffer 按空行切帧、多行 `data:` 拼接、`[DONE]` 结束；
- 每个事件带自增 `id`，前端可用于去重/排查；
- 每 15s 一个 `: keep-alive` 注释帧，避免网关把空闲连接判死；
- 客户端断开 → 写失败 → 取消标记 → 适配器停止读取上游并关闭连接，流水记 `CANCELLED` 且不计费；
- 超时分层：连接 5s / 首字节 30s / 整体 300s。

### 错误响应

| 场景 | HTTP | 响应体 |
|---|---|---|
| 路径/查询参数类型不匹配（例如把 `sk-...` 填进 `{id}`） | 400 | `{"code":4000,"message":"参数 id 需要 Long 类型，实际收到: ..."}` |
| 缺少必填参数 | 400 | `{"code":4001,...}` |
| 请求体不是合法 JSON | 400 | `{"code":4002,...}` |
| 请求方法不支持（如用 GET 调 `POST /api/chat`） | 405 | `{"code":4005,...}` |
| 静态资源或未知路径不存在（含 `/favicon.ico`） | 404 | 空 |
| 业务错误（如密钥归属不匹配） | 200 | `{"code":4030,...}`，业务码优先 |
| 未预期异常 | 200 | `{"code":500,"message":"internal error"}` |

### 异步任务与失败处理

任务提交只写本地消息表（`mq_outbox`），由定时任务投递，消息不会因为"业务提交后 send 失败"而永久丢失：

1. `POST /api/task/submit` → 写 `mq_outbox`（`PENDING`），返回 taskId；
2. 投递器每 2s 扫 `PENDING` → 发送 → confirm `ack` 才标 `SENT`；
3. 消费成功后回执 `CONSUMED`；
4. 失败带 `x-retry-count` 重投（最多 3 次），耗尽写 `failed_task` 并 nack → 进死信队列 `maibao.task.dlq`；
5. 对账任务每分钟跑：`SENT` 超过 5 分钟没有回执的重新投递，重试次数用尽的标 `FAILED`。

指标：`mq.publish.confirm`、`mq.publish.returned`、`mq.consume.success`、`mq.consume.duplicate`、`mq.consume.retry`、`mq.consume.failed`（`GET /actuator/metrics/{name}`）。

### 数据库（MySQL 8）

默认连 `maibao_technology_application`，账号 `root`；密码从 `application-local.yml`（已在 `.gitignore`）或环境变量 `DB_PASSWORD` 读取：

```yaml
spring:
  datasource:
    password: 你的密码
```

表结构以 [`docs/schema.sql`](docs/schema.sql) 为准（应用侧 `ddl-auto: validate`，只校验不改结构），共 7 张表：`publish_record`、`processed_task`、`failed_task`、`mq_outbox`、`api_key`、`user_query_log`、`cost_accounting`。字段、索引与设计说明见 [`docs/03-数据库设计.md`](docs/03-数据库设计.md)。

不装 MySQL 时可以用 H2 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=h2
# H2 控制台 http://localhost:8080/h2-console
```

计费是模拟的：token 由 `TokenEstimator` 估算（中文 1 字 ≈ 1 token，其余 4 字符 ≈ 1 token），单价在 `app.cost.pricing` 里按模型前缀配置。

### RabbitMQ（必需）

异步任务链路没有同步降级：投递失败就是失败，不会悄悄改成同步执行。

```bash
# 最省事：Docker
docker run -d --name rabbit -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

Windows 原生安装的要点（Erlang 与 RabbitMQ 版本必须匹配）：

1. 先按官方 [which-erlang](https://www.rabbitmq.com/docs/which-erlang) 选版本，装 Erlang/OTP 并设置 `ERLANG_HOME`；
2. 解压 `rabbitmq-server-windows-*.zip` 到 `C:\Program Files\`，把 `sbin` 加进 `Path`；
3. `rabbitmq-plugins enable rabbitmq_management`；
4. `rabbitmq-service.bat install` 然后 `rabbitmq-service.bat start`；
5. 管理台 <http://localhost:15672>（guest/guest，默认只允许本机）。

项目连的是**独立 vhost `/maibao`**、用户 `maibao`（vhost 名字带前导斜杠）。新环境要先建好并授权，否则队列声明会失败：

```bash
rabbitmqctl add_vhost /maibao
rabbitmqctl add_user maibao maibao
rabbitmqctl set_permissions -p /maibao maibao ".*" ".*" ".*"
```

管理台等价操作：`Admin → Virtual Hosts → Add`，Name 填 `/maibao`；再建用户并 `Set permission`，三个正则都填 `.*`。

broker 不可用时应用仍能启动（监听容器会在后台重连），但 `/api/task/submit` 会直接返回错误，`/actuator/health` 也会变 DOWN——这是预期信号。

可靠性相关的几层：

| 环节 | 做法 |
|---|---|
| 生产端 | 先写 `mq_outbox`，再由投递器发送；confirm 决定 `SENT` 还是重试 |
| 投递 | 消息持久化 + 队列持久化；未路由消息由 returns 标记失败并告警 |
| 消费 | 手动 ack；失败时带 `x-retry-count` 头重新投递 |
| 兜底 | 重试耗尽写 `failed_task` 表并 nack，消息路由到死信队列 |
| 幂等 | `processed_task` 的唯一约束；去重记录与业务动作在同一个事务里，失败一起回滚 |
| 对账 | `SENT` 超时无回执 → 重新投递；重试次数用尽 → `FAILED` |

---

## 日志

应用日志和访问日志都写到项目根目录的 `logs/`（已在 `.gitignore` 中）：

| 文件 | 内容 | 滚动 |
|---|---|---|
| `logs/app.log` | 应用日志，`com.maibao.tech` 级别为 DEBUG | 10 MB 一份，保留 7 天 |
| `logs/access.yyyy-MM-dd.log` | 访问日志：来源 / 时间 / 请求行 / 状态码 / 字节数 / 耗时(ms) | 每天一份 |

两者同时也输出到控制台；访问日志关闭了缓冲，可以直接 `tail -f`。

> 嵌入式 Tomcat 的 `catalina.base` 是随机临时目录，所以访问日志目录用 `${user.dir}/logs` 指定成绝对路径，否则文件会落在系统临时目录里。

---

## 目录结构

```
src/main/java/com/maibao/tech/
├── controller/   REST 入口：参数绑定 + 响应包装，业务规则一律下沉到各模块
├── adapter/      适配器模式：多厂商模型统一出入参
├── chat/         对话服务 + SSE（含帧解析、增量归一）
├── workflow/     节点模型、循环节点、图校验
├── mq/           RabbitMQ 生产/消费、确认与重试、失败落表
├── publish/      发布状态机、执行器、乐观锁抢占与超时回收
├── security/     AES-GCM 加密、密钥归属校验
├── usage/        对话埋点与成本聚合（流水 + 按天累计）
├── config/       线程池等基础配置
└── common/       统一响应、异常处理
```

**DTO / VO / PO 按模块分开放**，各自的 `domain/` 子包里只有数据载体，逻辑仍在模块根包：

| 模块 | `domain/` 内容 |
|---|---|
| `adapter/domain` | `ChatRequest`、`ChatMessage`、`ChatChunk`（统一出入参）、`Capabilities`、`ProviderError` |
| `mq/domain` | `TaskMessage`（消息体）、`ProcessedTask`、`FailedTask`、`OutboxMessage`（PO） |
| `publish/domain` | `PublishRecord`（PO）、`PublishStatus`（枚举） |
| `security/domain` | `ApiKeyEntity`（PO） |
| `usage/domain` | `UserQueryLog`、`CostAccounting`（PO）、`QueryLogView`、`CostView`、`UsageSummaryView`（VO） |
| `common/domain` | `ApiResponse`（统一响应 VO） |

---

## 测试

```bash
mvn test
```

36 个用例全部通过（2026-10-25）。

覆盖的关键点：
- `SseFrameParserTest` — 半包 / 粘包 / 多帧切分
- `LoopNodeTest` — 终止条件、轮次上限、非法参数
- `ChunkNormalizerTest` — OpenAI delta / result / reasoning_content 归一
- `AesGcmCipherTest` — 加解密往返 + 脱敏
- `PublishServiceTest` — 只对抢到的记录提交执行、成功/失败收尾、收尾落空、卡死回收
- `TaskConsumerTest` — 重复消息 ack 跳过、失败重投（重试次数 +1）、重试耗尽落失败表
- `TokenEstimatorTest` / `CostCalculatorTest` — token 估算与按模型定价
- `OpenAiSseDecoderTest` — 帧切分、`[DONE]` 哨兵、reasoning 归一、坏行跳过
- `OpenAiCompatibleAdapterTest` — 401/429/500 错误码归一、分片流式解析、取消传播（用 JDK HttpServer 起假厂商，不依赖外网）

---

## 边界与现状

**已验证**：36 个单测通过；7 张表由 `schema.sql` 建好并通过 `validate`；发布链路抢占→执行→收尾与卡死回收、SSE 真流式（32 帧带 seq / 15s 心跳 / 断连落 CANCELLED）、outbox 投递（PENDING→SENT→CONSUMED）、MQ 重试与死信、对话/工作流/发布/密钥/用量接口均实测通过。

**已知缺口**（如实标注，不是待修的 bug）：

1. 没有 `@SpringBootTest` 集成测试，状态机与消费重试靠单测 + 手工端到端验证。
2. 成本明细与聚合表没有对账任务（明细是权威，聚合可重算，但没有定时比对）。
3. 重试是"带计数重新投递"，没有延迟重试（死信队列已补，延迟要靠额外插件或延迟队列）。

**范围边界**：定时调度、灰度切流、审批流、服务注册发现、DAG 执行引擎、多租户与限流都不在本项目范围内，完整清单见 [`docs/01-设计取舍.md`](docs/01-设计取舍.md)。

**改代码的约定**：

- Java 17 + Spring Boot 3.2，不降级、不引入 Lombok；
- 新增厂商 = 新增一个 `AiProviderAdapter` 实现，不改 `AdapterRegistry` / `ChatService`；
- 状态迁移一律用条件更新，幂等靠唯一约束，不要"先查后写"；
- REST 入口放 `controller/`，DTO / VO / PO 放模块的 `domain/`，逻辑留在模块根包；
- 注释只写"为什么"，不写"是什么"。

---

## 文档

| 文件 | 内容 |
|---|---|
| [`docs/01-设计取舍.md`](docs/01-设计取舍.md) | 每个技术点为什么这么设计、常见疑问 |
| [`docs/02-时序图.md`](docs/02-时序图.md) | 发布链路与 MQ 链路的时序图（PNG + PlantUML 源） |
| [`docs/03-数据库设计.md`](docs/03-数据库设计.md) | 表结构、索引、计费口径 |
| [`docs/schema.sql`](docs/schema.sql) | 建表脚本（结构唯一来源） |

---

## 说明

- 这是一个**技术验证型项目**，业务是"调 API Key 做 AI 对话"，重点在机制而不是功能完整度。
- 发布、灰度、调度在真实系统里通常由独立服务承担，这里只保留**业务对象的状态与并发控制**这一层。
- 没有 API Key 也能跑：`MockAdapter` 会兜底生成回复；OpenAI key 可以配在 `app.provider.openai.api-key`。
