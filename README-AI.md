# README-AI.md — 给 AI 助手的项目说明书

> 本文件不是给人"了解项目"用的（那是 `README.md`），而是**给接手这个仓库的 AI 助手**看的：
> 它要说明这个项目为什么存在、面试要证明什么、哪些话不能写、改代码时守什么规矩。

---

## 1. 一句话说清这是什么

这是一个**面试用的技术证据项目**，不是产品。

它的唯一目的是：把简历上写过的技术点（适配器、循环节点、SSE 流式、RabbitMQ、乐观锁、加密、构建发布）做成**能跑起来、能点开看代码、能被追问三层**的东西。业务是"用 API Key 调模型做对话"，业务本身没有价值，**机制才有价值**。

判断任何改动的标准只有一条：

> 它能不能让面试官多问一层，而我能答上来？

如果不能，就不要加。

---

## 2. 面试目标（决定代码长什么样）

| 项 | 内容 |
|---|---|
| 目标岗位 | 后端开发实习生（AI 平台 / 大模型应用方向） |
| 候选人 | 2028 届，华中农业大学 信息管理与信息系统 |
| 真实经历 | 2025.10–2026.08 爱奇艺 奇智大模型平台组 研发实习生 |
| 这个项目的定位 | **辅助证据**，不是主项目。主项目是真实实习经历，这里是把机制"可演示化" |
| 要证明的三件事 | ① 我真的写过并发控制，不是背概念；② 我知道系统的边界在哪；③ 我的代码能让别人读懂 |

面试时的自述顺序应当是：**先讲真实项目的背景与机制 → 再说"这块我做了个可运行的最小复现" → 最后指代码**。

---

## 3. 技术点与代码的对应关系

| 简历技术点 | 代码位置 | 一句话机制 |
|---|---|---|
| 适配器统一多厂商出入参 | `adapter/AiProviderAdapter`、`AdapterRegistry` | 厂商差异收敛在实现类，上层只依赖 `ChatRequest`/`ChatChunk` |
| 循环节点 | `workflow/LoopNode` | 循环变量跨轮写回、条件**轮末**求值、`maxCount` 兜底 |
| 图校验 | `workflow/GraphValidator` | 三色标记 DFS，只裁剪"两端都在环上"的边（避免菱形误判） |
| SSE 流式 | `chat/stream/SseFrameParser`、`ChunkNormalizer` | 传输分片 ≠ 事件边界，自攒 buffer 按空行切帧 |
| RabbitMQ | `mq/` | 持久化 + 手动 ack + `processed_task` 唯一约束做幂等 |
| 乐观锁 | `publish/PublishRepository#casStatus` | `UPDATE ... WHERE id=? AND status=旧值`，影响行数 0 = 被抢先 |
| 抢占 | `publish/PublishService#claim`、`race` | 先查再抢，抢不到说明别人在跑；`race` 用 N 线程验证只成 1 个 |
| 加密 | `security/AesGcmCipher`、`KeyVaultService` | AES-GCM 密文入库、列表脱敏、读取强制校验归属 |
| 构建发布 | `Dockerfile`、`deploy/nginx.conf`、CI | 依赖层与源码层分离；`try_files` 修 404；SSE 关缓冲 |

详细"怎么讲 + 追问怎么答"在 [`docs/01-技术点与代码对照.md`](docs/01-技术点与代码对照.md)。

---

## 4. ⚠️ 事实边界（写任何文案都不能越界）

这个项目是"复刻"，所以凡是在真实项目里**不属于候选人**的部分，在这里也不能暗示成他做的。

| 真实项目里的事实 | 在这个仓库里怎么处理 |
|---|---|
| 灰度切流、实际部署由**调度服务**（正式员工）执行 | 只记录 `grayPercent`，**不能**写成"我实现了灰度切流" |
| 审批流用**公司现成工单系统** | 不实现审批引擎；若要演示，只能叫"审批回调时序"，不能说"我设计了审批流" |
| Nacos 是**公司基础设施** | 本项目**不用** Nacos；文档里提到时只能说"用过它的注册发现能力" |
| 工作流**执行引擎**由 mentor 负责 | 这里的 `WorkflowEngine` 是**简化复刻**，文档必须标注这一点 |
| 成本 / QPS / 限流策略候选人无权限改 | 本项目不涉及；不要编写这些模块 |

写 README、注释、commit message、文档时，**默认按"技术演示"措辞，不用"我主导/我设计/我负责"这类归属词**，除非那句话在真实项目里成立。

---

## 5. 当前状态

| 项 | 状态 |
|---|---|
| 代码完整性 | 已完成第一版（43 个主源码 + 4 个测试类），覆盖上表全部技术点；2026-10-23 把 REST 入口统一收进 `controller/` |
| **编译 + 启动验证** | ✅ 已完成（2026-10-23）：JDK 17 编译通过（43 主源码 + 4 测试类），应用启动成功，`/actuator/health` = UP |
| 单元测试 | 4 个测试类（帧解析 / 循环节点 / 增量归一 / 加解密）；❌ **本机未能执行** —— surefire 的 JUnit provider 不在本地仓库，且命令行 Maven 的 localRepository 里没有 Spring Boot 3.2.5。`mvn test` 真的绿之前，不要对外说"已测试" |
| 前端 | 只有一个 SSE 演示页 `static/index.html`，不是产品级前端 |
| RabbitMQ | ✅ 已接通本地 broker 验证：队列 `maibao.task.queue` publish=3 / deliver=3 / consumers=1，交换机 `maibao.task.exchange`(direct) 自动声明成功 |
| 生产化 | 不做。这是演示项目，不做多租户、审计、限流 |

---

## 5.1 已验证清单（可对外宣称）

以下不是"写完了"，而是**实际跑通过**的：

| 能力 | 验证方式 | 结果 |
|---|---|---|
| 应用启动 | `mvn spring-boot:run` | Started in 5.5s，Tomcat 8080 |
| 数据库 | Hibernate DDL 自动建表 | `processed_task` / `publish_record` / `api_key` 建表成功 |
| RabbitMQ 连接 | 启动日志 | `Created new connection: amqp://guest@127.0.0.1:5672` |
| 队列/交换机声明 | 管理台 API | `maibao.task.queue`、`maibao.task.exchange`(direct) 均存在 |
| 异步投递 + 消费 | `POST /api/task/submit` | 返回 `async via mq`；队列 publish=3 / deliver=3 / consumers=1 |
| 健康检查 | `GET /actuator/health` | `{"status":"UP"}` |
| 接口冒烟（2026-10-23 复核） | `/api/chat`、`/api/workflow/demo`、`/api/workflow/validate`、`/api/task/submit`、`/api/keys` | 全部 `code=0`；demo 的 `round_executed=3` |

**未验证**：单元测试（`mvn test`）—— 尚未在本机跑过，对外不要宣称"已测试"。

**已知缺口（面试前要么补掉，要么主动交代）**：

1. 发布链路只做了"扫描 + 抢占"，没有执行者：建单后 5 秒被抢占成 `PUBLISHING`，然后永远停在那里（实测）。
2. SSE 的帧解析（`SseFrameParser`）与增量归一（`ChunkNormalizer`）还没接进 `/api/chat/stream`，目前只有单测在调用 —— 演示页看到的是 MockAdapter 逐字符推流。
3. `TaskConsumer` 注释里写的"生产端 confirm"并没有真的配置：`application.yml` 没有 `publisher-confirm-type`，也没有 Confirm/Returns 回调。

## 6. 改代码时的约定

1. **Java 17 + Spring Boot 3.2**，不要降级到 8/11，也不要引入 Lombok（保持零注解处理器依赖）。
2. **新增一个厂商适配器**：只加一个 `@Component implements AiProviderAdapter`，**不要改** `AdapterRegistry` 或 `ChatService`。这是本项目的核心设计约束。
3. **状态迁移一律用条件更新**（`casStatus` 这类），不要写成"先 `findById` 判断状态再 `save`" —— 那是有竞态的错误示范。
4. **幂等靠唯一约束**，不要写"先查后插"。
5. 新增跨模块依赖时，优先顺着现有方向：`chat → adapter`、`workflow → chat`、`publish/mq → repository`。**不要**让 `adapter` 反向依赖 `chat`。REST 入口统一放 `controller/`，它只依赖各模块 service，不允许 service 反向依赖 controller；参数绑定与响应包装留在 controller，业务规则下沉到模块内。
6. 每个技术点都要有对应的**单元测试**，测试名要能当文档读（`should_xxx_when_yyy`）。
7. 注释只写"为什么"，不写"是什么"。示例：
   - ✅ `// 传输分片不等于事件边界，必须自攒 buffer`
   - ❌ `// 把 chunk 追加到 buffer`

---

## 7. 不要做的事

- ❌ 不要为了让 demo 好看而编造数据（假 QPS、假用户量、假耗时）
- ❌ 不要引入 Redis / Nacos / K8s 来"显得完整" —— 这个项目只复刻简历里写到的技术点，多一个没讲过的组件只会增加被问倒的面积
- ❌ 不要把真实公司的内部接口、字段名、业务规则搬进来
- ❌ 不要在文档里写"生产级"、"高可用" —— 它是单机演示
- ❌ 不要删掉 `docs/01` 里的"和真实项目的差异"那张表，那是用来**主动交代边界**的

---

## 8. 如果 AI 要接着做，优先级建议

1. **让 `mvn test` 真正跑起来**（编译已通过）：补上 surefire 的 JUnit provider，或修正 Maven 的 `localRepository` —— 这是当前唯一的硬阻塞
2. 补一个 `@SpringBootTest` 覆盖"发布状态机 + 并发抢占"的集成测试（现在只有单元测试）
3. RabbitMQ 的失败路径：消费重试、死信队列、积压处理（简历里说的是"可靠性三件套"，现在只覆盖了持久化 + 手动 ack + 唯一约束幂等；注释里提的生产端 confirm 也还没配）
4. 循环节点的**取消**能力（`CancellationToken` 或状态位 + 轮前检查）
5. SSE 的**断线重连与游标恢复**（对应简历"幂等合并与游标恢复"，现在是简化版）

---

## 9. 相关文件索引

| 文件 | 用途 |
|---|---|
| `README.md` | 给人看的项目介绍 |
| `docs/01-技术点与代码对照.md` | **面试主武器**：每个技术点对应哪行代码 + 怎么讲 + 追问怎么答 |
| `docs/02-接口清单.md` | 接口表 + PowerShell 验证脚本 |
| `AGENTS`/本文件 | 给 AI 的上下文（本文件） |
