# maibao-technology-application

> 一个 AI 应用平台的关键技术最小实现：多厂商模型适配、SSE 流式、工作流循环、异步任务、发布状态机与并发控制、密钥加密。每个点都拆成可运行、可单独验证的代码。

技术栈：Java 17 · Spring Boot 3.2 · Spring Data JPA · H2 · RabbitMQ(可选) · Maven

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

每个技术点的设计取舍与常见疑问见 [`docs/01-技术点与代码对照.md`](docs/01-技术点与代码对照.md)。

---

## 快速开始

```bash
# 1. 跑起来（默认走 RabbitMQ；本地没有 broker 就把 app.mq.enabled 改成 false）
mvn spring-boot:run

# 2. 打开功能测试台（按 Tab 覆盖全部接口）
# http://localhost:8080/
```

### 常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat?prompt=...&model=mock:echo` | 同步对话 |
| GET | `/api/chat/stream?prompt=...` | **SSE 流式**（浏览器直接打开看） |
| POST | `/api/workflow/run` | 跑一个「LLM 节点 + 循环 3 轮」的工作流 |
| POST | `/api/workflow/validate` | 图校验：传邻接表，返回环上的边 |
| POST | `/api/task/submit?payload=...` | 提交异步任务（MQ 开启走队列，关闭时同步执行） |
| POST | `/api/publish?nodeId=n1&delaySeconds=10` | 建定时发布记录 |
| POST | `/api/publish/{id}/race?threads=20` | **并发验证**：20 线程抢一条，应返回 1 |
| POST | `/api/keys?...` | 写入密钥（密文入库，返回脱敏串） |
| GET | `/api/keys/{id}?ownerId=other` | **越权校验**：返回 4030 |

### 数据库（默认 H2，零安装）

默认用 **H2 文件库**：`jdbc:h2:file:./data/maibao`，启动时按实体自动建表，`data/` 目录自动生成。
控制台：<http://localhost:8080/h2-console>（JDBC URL 填 `jdbc:h2:file:./data/maibao;AUTO_SERVER=TRUE`，用户 `sa`，密码空）

想验证 MySQL 的执行计划与索引效果时：

1. 建库：`CREATE DATABASE maibao DEFAULT CHARSET utf8mb4;`
2. 打开 `pom.xml` 里 `mysql-connector-j` 那段注释
3. 启动加参数：`mvn spring-boot:run -Dspring-boot.run.profiles=mysql`

`publish_record` 上已声明联合索引 `idx_status_scheduled_at (status, scheduled_at)`，
对应"按 status + scheduled_at 扫到期任务"的查询，可以拿它验证执行计划。

### RabbitMQ（默认开启）

`application.yml` 里默认是 `app.mq.enabled: true`。需要本地有 RabbitMQ：

```bash
docker run -d --name rabbit -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

没有 broker 时把它改成 `false`，`TaskDispatcher` 会自动降级为同步执行（`InlineTaskDispatcher`），接口行为不变；`/actuator/health` 也不会因为 broker 不可达而变 DOWN。

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
├── mq/           RabbitMQ 生产/消费、消费幂等
├── publish/      发布状态机、乐观锁抢占、并发验证
├── security/     AES-GCM 加密、密钥归属校验
├── config/       线程池等基础配置
└── common/       统一响应、异常处理
```

---

## 测试

```bash
mvn test
```

10 个用例全部通过（2026-10-23）。

覆盖的关键点：
- `SseFrameParserTest` — 半包 / 粘包 / 多帧切分
- `LoopNodeTest` — 终止条件、轮次上限、非法参数
- `ChunkNormalizerTest` — OpenAI delta / result / reasoning_content 归一
- `AesGcmCipherTest` — 加解密往返 + 脱敏

---

## 说明

- 这是一个**技术验证型项目**，业务是"调 API Key 做 AI 对话"，重点在机制而不是功能完整度。
- 发布、灰度、调度在真实系统里通常由独立服务承担，这里只保留**业务对象的状态与并发控制**这一层。
- 没有 API Key 也能跑：`MockAdapter` 会兜底生成回复。
