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
| RabbitMQ 可靠性（confirm / returns / DLX / 重试 / 幂等 / outbox，**备用**） | `mq/outbox/` — `OutboxPublisher`、`OutboxService` |
| 成本记账（Redis 累加 + 明细批量 + 用户配额） | `usage/` — `CostRedisStore`、`DetailBuffer`、`CostFlushJob`、`CostQuotaService` |
| 多线程与锁（状态抢占、乐观锁、卡死回收） | `publish/` — `PublishRepository.claim`、`PublishExecutor` |
| 密钥加密（密文入库 + 归属校验） | `security/` — `AesGcmCipher`、`KeyVaultService` |
| 构建与发布（分层缓存 + 多阶段） | `Dockerfile`、`deploy/nginx.conf`、`.github/workflows/ci.yml` |
| 用量查询（明细翻页 + 按天聚合报表） | `usage/` — `UsageService`、`TokenEstimator`、`CostCalculator` |

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

### 成本记账与配额（Redis 累加 + 明细批量）

Agent 场景下**一次用户操作会打出上百次模型调用**，对话 QPS 上千。所以成本链路不能"一次调用一条消息、一条消息一次 `UPDATE`"——那是 6 次 DB 写/条，`cost_accounting` 单行更新会变成热点。现在把高频写成两级：

1. `UsageService.record` 里**明细只入本地队列**（`DetailBuffer`），成本只打一次 Redis pipeline —— 对话线程**零 DB 往返**；
2. Redis 按天分桶两个 Hash：`maibao:cost:acc:{date}` 存 `userId|model|cost|pt|ct|cnt`，`maibao:cost:user:{date}` 存该用户当日已用（给配额校验 O(1) 读）；金额用 `long × 1e8` 存整数，避免浮点漂移；
3. `DetailBuffer` 每 200ms 用 `JdbcTemplate.batchUpdate` 批量落 `user_query_log`（JPA + `IDENTITY` 主键会关掉 JDBC 批量，`saveAll` 实际是逐条插）；队列满时**丢弃并计数**，绝不阻塞对话；
4. `CostFlushJob` 每分钟 `HGETALL` → **覆盖写** `cost_accounting`：`ON DUPLICATE KEY UPDATE cost_amount = VALUES(cost_amount)`。Redis 里存的就是"当日累计"，落盘只是同步，**重跑任意多次结果一致 → 幂等不需要去重表**；
5. 每天 00:05 日结：最后落盘 → 删当日 Redis key → 清理 7 天前明细。

**用户配额**：`CostQuotaService.check(userId)` 在 `ChatService` 入口执行，读 Redis 的当日已用，超限抛 `QuotaExceededException`（业务码 **4029**，message 带"额度 X 已用 Y"），**不调模型、不产生费用**。Redis 不可用时**放行** —— 配额是成本控制，不是安全边界，不能因为缓存故障阻断业务。查询接口 `GET /api/quota?userId=u1`。

```yaml
app:
  cost:
    zone: Asia/Shanghai
    quota: { enabled: true, daily-per-user: 100.0 }
    redis: { key-prefix: maibao:cost, ttl-days: 3 }
    flush-ms: 60000            # 落盘间隔
    settle-cron: "0 5 0 * * *"
    detail: { batch-size: 2000, queue-capacity: 20000, retain-days: 7 }
```

> **为什么成本不走 MQ**：一条消息一次累加是"不可合并"的做法，而成本累加天然可合并（`+= sum`）。1000 QPS 下 outbox 逐条投递要 6 次 DB 写/条（约 6000 写/秒），Redis 只要一次 O(1) 内存操作。**可合并的计数走 Redis，必须逐条持久化、要跨系统的才走 MQ。**
>
> **outbox 保留但默认关闭**（`app.mq.outbox-enabled=false`）：代码已收进 `mq/outbox/`，等出现"必须强投递"的消息（通知、扣费、跨系统事件）再启用。任务链路同理：`/api/task/submit` 已删除，`TaskConsumer/TaskProcessor` 由 `app.task.enabled=false` 关闭，表和队列资产保留。

指标：`cost.reconcile.lag`、`mq.publish.confirm`、`mq.publish.returned`（`GET /actuator/metrics/{name}`）。

### 数据库（MySQL 8）

默认连 `maibao_technology_application`，账号 `root`；密码从 `application-local.yml`（已在 `.gitignore`）或环境变量 `DB_PASSWORD` 读取：

```yaml
spring:
  datasource:
    password: 你的密码
```

表结构以 [`docs/schema.sql`](docs/schema.sql) 为准（应用侧 `ddl-auto: validate`，只校验不改结构），共 7 张表：`publish_record`、`processed_task`、`failed_task`、`mq_outbox`（备用）、`api_key`、`user_query_log`、`cost_accounting`。字段、索引与设计说明见 [`docs/03-数据库设计.md`](docs/03-数据库设计.md)。

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

broker 不可用时应用仍能启动（监听容器会在后台重连），对话接口照常返回。**成本链路已经不走 MQ**，所以 broker 挂了不影响记账；outbox 组件默认关闭，不产生流量。`/actuator/health` 会因 rabbit 健康检查变 DOWN——这是预期信号，可用 `management.health.rabbit.enabled=false` 关掉。

可靠性相关的几层：

| 环节 | 做法 |
|---|---|
| 生产端 | 先写 `mq_outbox`，再由投递器发送；confirm 决定 `SENT` 还是重试 |
| 投递 | 消息持久化 + 队列持久化；未路由消息由 returns 标记失败并告警 |
| 消费 | 手动 ack；失败时带 `x-retry-count` 头重新投递 |
| 兜底 | 重试耗尽写 `failed_task` 表并 nack，消息路由到死信队列 |
| 幂等 | `processed_task` 的唯一约束；去重记录与业务动作在同一个事务里，失败一起回滚 |
| 对账 | `SENT` 超时无回执 → 重新投递；重试次数用尽 → `FAILED` |

### 消费并发与预取

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: manual
        concurrency: 4
        max-concurrency: 8
        prefetch: 2
```

- `prefetch` 是**每个消费者允许的未确认条数**，不是并发数。单线程 + `prefetch: 10` 只是把 10 条消息堆在客户端，吞吐仍被单条耗时钉死；
- 任务消费者在调模型，属于 IO 密集，吞吐靠 `concurrency`：4 个线程起步，忙时扩到 8；
- 每线程 `prefetch: 2`，未确认窗口约 8 条，进程崩溃时最多重投这 8 条（`processed_task` 幂等兜底）；
- 实测 20 条任务：单线程 `0.40s`（≈50 条/秒），4 并发 `0.13s`（≈155 条/秒）。

> 成本累加已改为 Redis，`cost_accounting` 不再是并发热点：落盘是每分钟一次的覆盖写，与对话 QPS 解耦。（改造前同一 `(user, model, date)` 是单行热点，4 并发只拿到约 3 倍提升。）

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
├── mq/           RabbitMQ 可靠性 + outbox（outbox/ 独立成包，默认关闭）
├── publish/      发布状态机、执行器、乐观锁抢占与超时回收
├── security/     AES-GCM 加密、密钥归属校验
├── usage/        对话埋点、Redis 成本累加、明细批量落库、用户配额
├── config/       线程池等基础配置
└── common/       统一响应、异常处理
```

**DTO / VO / PO 按模块分开放**，各自的 `domain/` 子包里只有数据载体，逻辑仍在模块根包：

| 模块 | `domain/` 内容 |
|---|---|
| `adapter/domain` | `ChatRequest`、`ChatMessage`、`ChatChunk`（统一出入参）、`Capabilities`、`ProviderError` |
| `mq/domain` | `TaskMessage`（消息体）、`ProcessedTask`、`FailedTask`（PO） |
| `mq/outbox/domain` | `OutboxMessage`（PO，outbox 独立包） |
| `publish/domain` | `PublishRecord`（PO）、`PublishStatus`（枚举） |
| `security/domain` | `ApiKeyEntity`（PO） |
| `usage/domain` | `UserQueryLog`、`CostAccounting`（PO）、`QueryLogView`、`CostView`、`UsageSummaryView`、`QuotaView`（VO） |
| `common/domain` | `ApiResponse`（统一响应 VO） |

**数据访问也按模块分开放**：各自的 `repository/` 子包只放 Spring Data 接口，条件更新、唯一约束这类"并发语义"直接写在接口的 `@Query` 上：

```
usage/repository/UserQueryLogRepository.java
usage/repository/CostAccountingRepository.java
mq/repository/{ProcessedTask,FailedTask}Repository.java
mq/outbox/repository/OutboxRepository.java
publish/repository/PublishRepository.java
security/repository/ApiKeyRepository.java
```

---

## 测试

```bash
mvn test
```

33 个用例全部通过（2026-10-25）。

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

**已验证**：33 个单测通过；7 张表由 `schema.sql` 建好并通过 `validate`；发布链路抢占→执行→收尾与卡死回收、SSE 真流式（32 帧带 seq / 15s 心跳 / 断连落 CANCELLED）、成本 Redis 累加 + 分钟级覆盖写落盘、配额拒绝（业务码 4029）、发布/密钥/用量接口均实测通过。

**已知缺口**（如实标注，不是待修的 bug）：

1. 没有 `@SpringBootTest` 集成测试，状态机与消费重试靠单测 + 手工端到端验证。
2. 成本链路按 **1000 QPS** 设计（Redis 累加 + 分钟级落盘），但没有压测脚本；明细表还没做按天分区，现在用 `DELETE` 清理。
3. 任务链路停用中：`task_result` 表、适配器 `capabilities()` 的异步能力、长任务"发起 + 回调"语义都还没做。
4. 重试是"带计数重新投递"，没有延迟重试（死信队列已补，延迟要靠额外插件或延迟队列）。

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
| [`docs/02-时序图.md`](docs/02-时序图.md) | 发布 / 成本 / SSE 链路的时序图（PNG + PlantUML 源） |
| [`docs/03-数据库设计.md`](docs/03-数据库设计.md) | 表结构、索引、计费口径 |
| [`docs/04-缺陷记录.md`](docs/04-缺陷记录.md) | 已修复的并发/可靠性问题：复现、根因、回归 |
| [`docs/schema.sql`](docs/schema.sql) | 建表脚本（结构唯一来源） |

---

## 说明

- 这是一个**技术验证型项目**，业务是"调 API Key 做 AI 对话"，重点在机制而不是功能完整度。
- 发布、灰度、调度在真实系统里通常由独立服务承担，这里只保留**业务对象的状态与并发控制**这一层。
- 没有 API Key 也能跑：`MockAdapter` 会兜底生成回复；OpenAI key 可以配在 `app.provider.openai.api-key`。
