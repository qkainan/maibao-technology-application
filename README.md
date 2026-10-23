# maibao-technology-application

> 一个**为面试准备**的后端复刻项目：把简历里写过的技术点，做成可以跑起来、可以点开看代码的东西。

技术栈：Java 17 · Spring Boot 3.2 · Spring Data JPA · H2 · RabbitMQ(可选) · Maven

---

## 为什么做这个项目

简历上的每一句话都应该能被追问三层。这个项目的作用是：**当面试官问"你具体怎么做的"，可以直接打开代码指给他看。**

| 简历里的技术点 | 在这个项目里 |
|---|---|
| 适配器模式统一多厂商出入参 | `adapter/` — `AiProviderAdapter` + `AdapterRegistry` |
| 循环节点（循环变量 / 终止条件 / 轮次上限） | `workflow/LoopNode.java` |
| SSE 流式链路（帧解析 + 增量归一） | `chat/stream/SseFrameParser.java`、`ChunkNormalizer.java` |
| RabbitMQ（可靠性三件套 + 消费幂等） | `mq/` — `RabbitTaskDispatcher`、`TaskConsumer`、`ProcessedTask` |
| 多线程与锁（状态抢占、乐观锁） | `publish/` — `PublishRepository.casStatus` |
| 密钥加密（密文入库 + 归属校验） | `security/` — `AesGcmCipher`、`KeyVaultService` |
| 构建与发布（分层缓存 + 多阶段） | `Dockerfile`、`deploy/nginx.conf`、`.github/workflows/ci.yml` |

详细的"代码位置 → 怎么讲"对照见 [`docs/01-技术点与代码对照.md`](docs/01-技术点与代码对照.md)。

---

## 快速开始

```bash
# 1. 跑起来（不需要 RabbitMQ，默认走 inline 模式）
mvn spring-boot:run

# 2. 打开演示页
# http://localhost:8080/index.html
```

### 常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat?prompt=...&model=mock:demo` | 同步对话 |
| GET | `/api/chat/stream?prompt=...` | **SSE 流式**（浏览器直接打开看） |
| POST | `/api/workflow/demo` | 跑一个「LLM 节点 + 循环 3 轮」的工作流 |
| POST | `/api/workflow/validate` | 图校验：传邻接表，返回环上的边 |
| POST | `/api/task/submit?payload=...` | 提交异步任务（MQ 关闭时同步执行） |
| POST | `/api/publish?nodeId=n1&delaySeconds=10` | 建定时发布记录 |
| POST | `/api/publish/{id}/race?threads=20` | **并发演示**：20 线程抢一条，应返回 1 |
| POST | `/api/keys?...` | 写入密钥（密文入库，返回脱敏串） |
| GET | `/api/keys/{id}?ownerId=other` | **越权演示**：返回 4030 |

### 打开 RabbitMQ（可选）

```yaml
# application.yml
app:
  mq:
    enabled: true
```

需要本地有 RabbitMQ（`docker run -p 5672:5672 rabbitmq:3-management`）。
不打开时 `TaskDispatcher` 自动降级为同步执行，接口行为不变。

---

## 目录结构

```
src/main/java/com/maibao/tech/
├── adapter/      适配器模式：多厂商模型统一出入参
├── chat/         对话服务 + SSE（含帧解析、增量归一）
├── workflow/     节点模型、循环节点、图校验
├── mq/           RabbitMQ 生产/消费、消费幂等
├── publish/      发布状态机、乐观锁抢占、并发演示
├── security/     AES-GCM 加密、密钥归属校验
├── config/       线程池等基础配置
└── common/       统一响应、异常处理
```

---

## 测试

```bash
mvn test
```

覆盖的关键点：
- `SseFrameParserTest` — 半包 / 粘包 / 多帧切分
- `LoopNodeTest` — 终止条件、轮次上限、非法参数
- `ChunkNormalizerTest` — OpenAI delta / result / reasoning_content 归一
- `AesGcmCipherTest` — 加解密往返 + 脱敏

---

## 说明

- 这是一个**技术验证型项目**，业务是"调 API Key 做 AI 对话"，目的是展示机制而不是功能完整度。
- 真实的发布/灰度/调度在公司走的是独立服务，这里只复刻**业务对象的状态与并发控制**这一层。
- 没有 API Key 也能跑：`MockAdapter` 会兜底生成回复。
