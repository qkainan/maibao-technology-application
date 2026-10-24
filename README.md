# maibao-technology-application

> 一个 AI 应用平台的关键技术最小实现：多厂商模型适配、SSE 流式、工作流循环、异步任务、发布状态机与并发控制、密钥加密。每个点都拆成可运行、可单独验证的代码。

技术栈：Java 17 · Spring Boot 3.2 · Spring Data JPA · MySQL 8（H2 可选）· RabbitMQ · Maven

---

## 为什么做这个项目

AI 应用平台的骨架高度相似：一次对话要穿过适配层与流式链路，一次发布要经过状态机与并发抢占，一条消息要保证幂等。这些部分在业务代码里往往埋得很深，最后只能停在"用了什么"。

这个项目把它们各自切成最小单元：每个模块只保留一处核心机制，配对应的单测，以及可以直接调用的接口。

| 技术点 | 在这个项目里 |
|---|---|
| 适配器模式统一多厂商出入参 | `adapter/` — `AiProviderAdapter` + `AdapterRegistry` |
| 循环节点（循环变量 / 终止条件 / 轮次上限） | `workflow/LoopNode.java` |
| SSE 流式链路（帧解析 + 增量归一） | `chat/stream/SseFrameParser.java`、`ChunkNormalizer.java` |
| RabbitMQ（消息持久化 + 手动 ack + 消费幂等） | `mq/` — `RabbitTaskDispatcher`、`TaskConsumer`、`ProcessedTask` |
| 多线程与锁（状态抢占、乐观锁） | `publish/` — `PublishRepository.casStatus` |
| 密钥加密（密文入库 + 归属校验） | `security/` — `AesGcmCipher`、`KeyVaultService` |
| 构建与发布（分层缓存 + 多阶段） | `Dockerfile`、`deploy/nginx.conf`、`.github/workflows/ci.yml` |
| 用量与成本（对话埋点 + 按天聚合） | `usage/` — `UsageService`、`TokenEstimator`、`CostCalculator` |

每个技术点的设计取舍与常见疑问见 [`docs/01-技术点与代码对照.md`](docs/01-技术点与代码对照.md)。

---

## 快速开始

```bash
# 0. 建库（MySQL 8）并把密码放本地文件（不进仓库）
mysql -u root -p -e "CREATE DATABASE maibao_technology_application DEFAULT CHARSET utf8mb4;"
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

### 数据库（MySQL 8）

默认连 `maibao_technology_application`，账号 `root`；密码从 `application-local.yml`（已在 `.gitignore`）或环境变量 `DB_PASSWORD` 读取：

```yaml
spring:
  datasource:
    password: 你的密码
```

启动时 Hibernate（`ddl-auto: update`）自动建表：

| 表 | 用途 |
|---|---|
| `publish_record` | 发布状态机（含 `idx_status_scheduled_at`） |
| `processed_task` | MQ 消费去重（唯一约束） |
| `failed_task` | 消费重试耗尽后的失败记录 |
| `api_key` | 密钥密文与脱敏串 |
| `user_query_log` | 每次 AI 对话的流水（token、成本、耗时、状态） |
| `cost_accounting` | 成本按「用户 × 模型 × 天」聚合，唯一键 `uk_cost_user_model_date` |

不装 MySQL 时可以用 H2 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=h2
# H2 控制台 http://localhost:8080/h2-console
```

计费是模拟的：token 由 `TokenEstimator` 估算（中文 1 字 ≈ 1 token，其余 4 字符 ≈ 1 token），单价在 `app.cost.pricing` 里按模型前缀配置。

### RabbitMQ（必需）

异步任务链路没有同步降级：投递失败就是失败，不会悄悄改成同步执行。所以本地需要 RabbitMQ：

```bash
docker run -d --name rabbit -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

项目连的是**独立 vhost `/maibao`**、用户 `maibao`（vhost 名字带前导斜杠）。新环境要先建好并授权，否则队列声明会失败：

```bash
rabbitmqctl add_vhost /maibao
rabbitmqctl add_user maibao maibao
rabbitmqctl set_permissions -p /maibao maibao ".*" ".*" ".*"
```

管理台等价操作见 [docs/03-本地安装RabbitMQ.md](docs/03-本地安装RabbitMQ.md#L113)。

broker 不可用时应用仍能启动（监听容器会在后台重连），但 `/api/task/submit` 会直接返回错误，`/actuator/health` 也会变 DOWN——这是预期信号，不是待修的 bug。

可靠性相关的几层：

| 环节 | 做法 |
|---|---|
| 生产端 | `publisher-confirm-type: correlated` + `publisher-returns`，未确认/未路由的消息记 error 并计数 |
| 投递 | 消息持久化 + 队列持久化 |
| 消费 | 手动 ack；失败时带 `x-retry-count` 头重新投递，最多 `app.mq.max-retries`（默认 3）次 |
| 兜底 | 重试耗尽写 `failed_task` 表并 nack，不把毒消息留在队列里 |
| 幂等 | `processed_task` 的唯一约束；去重记录与业务动作在同一个事务里，失败会一起回滚，重试才有机会重跑 |

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
| `adapter/domain` | `ChatRequest`、`ChatMessage`、`ChatChunk`（统一出入参） |
| `mq/domain` | `TaskMessage`（消息体）、`ProcessedTask`、`FailedTask`（PO） |
| `publish/domain` | `PublishRecord`（PO）、`PublishStatus`（枚举） |
| `security/domain` | `ApiKeyEntity`（PO） |
| `usage/domain` | `UserQueryLog`、`CostAccounting`（PO）、`QueryLogView`、`CostView`、`UsageSummaryView`（VO） |
| `common/domain` | `ApiResponse`（统一响应 VO） |

---

## 测试

```bash
mvn test
```

27 个用例全部通过（2026-10-24）。

覆盖的关键点：
- `SseFrameParserTest` — 半包 / 粘包 / 多帧切分
- `LoopNodeTest` — 终止条件、轮次上限、非法参数
- `ChunkNormalizerTest` — OpenAI delta / result / reasoning_content 归一
- `AesGcmCipherTest` — 加解密往返 + 脱敏
- `PublishServiceTest` — 只对抢到的记录提交执行、成功/失败收尾、收尾落空、卡死回收
- `TaskConsumerTest` — 重复消息 ack 跳过、失败重投（重试次数 +1）、重试耗尽落失败表

---

## 说明

- 这是一个**技术验证型项目**，业务是"调 API Key 做 AI 对话"，重点在机制而不是功能完整度。
- 发布、灰度、调度在真实系统里通常由独立服务承担，这里只保留**业务对象的状态与并发控制**这一层。
- 没有 API Key 也能跑：`MockAdapter` 会兜底生成回复。
