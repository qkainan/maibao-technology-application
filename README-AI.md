# README-AI.md — 项目说明（给接手的人与 AI 助手）

> `README.md` 回答"怎么跑起来"，本文件回答"为什么这么设计、边界在哪、现在做到哪一步"。
> 接手这个仓库时先读这一份，再按第 9 节的索引看具体代码。

---

## 1. 一句话

把 AI 应用平台里几条最容易停留在概念上的链路 —— 多厂商适配、SSE 流式、工作流循环、异步任务、发布状态机与并发控制、密钥加密 —— 拆成**能跑、能读、能单独验证**的最小实现。

业务（用 API Key 调模型做对话）只是载体，**机制**才是这个项目的产出。

判断一个改动该不该进这个仓库，只看一条：

> 它是否让某个机制更清楚，或者被验证得更扎实？

如果不能，就不要加 —— 这个项目追求的是"每一部分都能解释清楚"，不是功能完整度。

---

## 2. 设计约束

| 项 | 约束 |
|---|---|
| 定位 | 技术验证型项目，不是产品；不做多租户、审计、限流 |
| 技术栈 | Java 17 + Spring Boot 3.2，不降级；不引入 Lombok（保持零注解处理器依赖） |
| 依赖面 | Spring Boot + JPA + H2 + RabbitMQ(可选)；不引入与本项目无关的中间件 |
| 可验证 | 每个机制至少有一个可直接调用的接口，以及一个名字能当文档读的单测 |
| 可读 | 模块间单向依赖；REST 入口只做参数绑定与响应包装；注释只写"为什么" |

---

## 3. 技术点与代码的对应关系

| 技术点 | 代码位置 | 一句话机制 |
|---|---|---|
| 适配器统一多厂商出入参 | `adapter/AiProviderAdapter`、`AdapterRegistry` | 厂商差异收敛在实现类，上层只依赖 `ChatRequest`/`ChatChunk` |
| 循环节点 | `workflow/LoopNode` | 循环变量跨轮写回、条件**轮末**求值、`maxCount` 兜底 |
| 图校验 | `workflow/GraphValidator` | 三色标记 DFS，只返回构成环的回边（避免菱形误判） |
| SSE 流式 | `chat/stream/SseFrameParser`、`ChunkNormalizer` | 传输分片 ≠ 事件边界，自攒 buffer 按空行切帧 |
| RabbitMQ | `mq/` | 持久化 + 手动 ack + `processed_task` 唯一约束做幂等 |
| 乐观锁 | `publish/PublishRepository#casStatus` | `UPDATE ... WHERE id=? AND status=旧值`，影响行数 0 = 被抢先 |
| 抢占 | `publish/PublishService#claim`、`race` | 先查再抢，抢不到说明别人在跑；`race` 用 N 线程验证只成 1 个 |
| 加密 | `security/AesGcmCipher`、`KeyVaultService` | AES-GCM 密文入库、列表脱敏、读取强制校验归属 |
| 构建发布 | `Dockerfile`、`deploy/nginx.conf`、CI | 依赖层与源码层分离；`try_files` 修 404；SSE 关缓冲 |

更细的设计取舍与常见疑问见 [`docs/01-技术点与代码对照.md`](docs/01-技术点与代码对照.md)。

---

## 4. 范围边界

这个项目只实现"机制层"。下面这些属于外部系统或平台侧职责，**不在本项目范围内**，文档与注释里也不写成这个项目的能力：

| 外部系统承担的部分 | 本项目的处理 |
|---|---|
| 定时调度（XXL-Job 一类） | 用 `@Scheduled` 等价实现"扫描 + 抢占"这一层 |
| 灰度切流、实际部署 | 只记录 `grayPercent`，不做切流 |
| 审批流 | 不实现审批引擎 |
| 服务注册发现（Nacos 一类） | 不使用 |
| 工作流执行引擎（DAG 调度、并行分支） | `WorkflowEngine` 是简化实现，只做线性节点 + 循环节点 |
| 多租户、审计、权限体系 | 只做密钥归属校验 |
| 成本、QPS、限流策略 | 不涉及，不编写相关模块 |

写 README、注释、commit message 时保持客观描述，不用"生产级""高可用"这类词。

---

## 5. 当前状态

| 项 | 状态 |
|---|---|
| 代码完整性 | 已完成第一版（43 个主源码 + 4 个测试类），覆盖上表全部技术点；2026-10-23 把 REST 入口统一收进 `controller/` |
| **编译 + 启动验证** | ✅ 已完成（2026-10-23）：JDK 17 编译通过（43 主源码 + 4 测试类），应用启动成功，`/actuator/health` = UP |
| 单元测试 | 4 个测试类（帧解析 / 循环节点 / 增量归一 / 加解密）；❌ **本机尚未执行成功** —— surefire 的 JUnit provider 不在本地仓库，命令行 Maven 的 localRepository 里也没有 Spring Boot 3.2.5。在 `mvn test` 真正跑通之前，文档里不标注"已测试" |
| 前端 | 单页功能测试台 `static/index.html`：按 Tab 覆盖全部接口，含并发 PASS/FAIL 判定、SSE 帧计数与已知缺口提示；无构建、无外部依赖 |
| RabbitMQ | ✅ 已接通本地 broker：队列 `maibao.task.queue` publish=3 / deliver=3 / consumers=1，交换机 `maibao.task.exchange`(direct) 自动声明成功 |
| 生产化 | 不做。单机运行，不涉及集群与多租户 |

---

## 5.1 已验证清单（实际跑通过的）

以下不是"写完了"，而是**实际跑通过**的：

| 能力 | 验证方式 | 结果 |
|---|---|---|
| 应用启动 | `mvn spring-boot:run` | Started in 5.5s，Tomcat 8080 |
| 数据库 | Hibernate DDL 自动建表 | `processed_task` / `publish_record` / `api_key` 建表成功 |
| RabbitMQ 连接 | 启动日志 | `Created new connection: amqp://guest@127.0.0.1:5672` |
| 队列/交换机声明 | 管理台 API | `maibao.task.queue`、`maibao.task.exchange`(direct) 均存在 |
| 异步投递 + 消费 | `POST /api/task/submit` | 返回 `async via mq`；队列 publish=3 / deliver=3 / consumers=1 |
| 健康检查 | `GET /actuator/health` | `{"status":"UP"}` |
| 接口冒烟（2026-10-23 复核） | `/api/chat`、`/api/workflow/run`、`/api/workflow/validate`、`/api/task/submit`、`/api/keys` | 全部 `code=0`；`/api/workflow/run` 返回 `round_executed=3` |

**未验证**：单元测试（`mvn test`）尚未在本机跑通。

**已知缺口**（要么补掉，要么在文档里如实标注）：

1. 发布链路只做了"扫描 + 抢占"，没有执行者：建单后 5 秒被抢占成 `PUBLISHING`，然后停在那里（实测）。
2. SSE 的帧解析（`SseFrameParser`）与增量归一（`ChunkNormalizer`）还没接进 `/api/chat/stream`，目前只有单测在调用 —— 页面上的流式效果来自 MockAdapter 逐字符推流。
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

- ❌ 不要为了让页面好看而编造数据（假 QPS、假用户量、假耗时）
- ❌ 不要为了"看起来完整"引入 Redis / Nacos / K8s —— 多一个没被真正用起来的组件，就多一块解释不清的面积
- ❌ 不要复制任何真实业务系统的内部接口、字段名或业务规则
- ❌ 不要在文档里写"生产级"、"高可用" —— 这是单机运行
- ❌ 不要删掉第 4 节的范围边界表，它是这个项目对外的承诺

---

## 8. 后续计划

1. **让 `mvn test` 真正跑起来**（编译已通过）：补上 surefire 的 JUnit provider，或修正 Maven 的 `localRepository` —— 这是当前唯一的硬阻塞
2. 把 `SseFrameParser` / `ChunkNormalizer` 接进 `/api/chat/stream`，让流式链路真正跑在请求链路上（见 5.1 节缺口 2）
3. 补一个 `@SpringBootTest` 覆盖"发布状态机 + 并发抢占"的集成测试（现在只有单元测试）
4. RabbitMQ 失败路径：生产端 confirm、消费重试、死信队列、积压处理
5. 循环节点的**取消**能力（`CancellationToken` 或状态位 + 轮前检查）
6. 发布链路的执行闭环：抢占之后要有人把记录推进到 `SUCCESS` / `FAILED`（见 5.1 节缺口 1）

---

## 9. 相关文件索引

| 文件 | 用途 |
|---|---|
| `README.md` | 快速开始、接口清单、目录结构 |
| `docs/01-技术点与代码对照.md` | 每个技术点的设计取舍与常见疑问 |
| `docs/02-接口清单.md` | 接口表 + PowerShell 验证脚本 |
| `docs/03-本地安装RabbitMQ.md` | Windows 本地安装 RabbitMQ |
| 本文件 | 设计约束、范围边界、当前进度 |
