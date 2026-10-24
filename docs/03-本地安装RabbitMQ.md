# 本地安装 RabbitMQ（Windows）

> 异步任务链路**没有同步降级**：投递失败就是失败，不会悄悄改成同步执行，所以必须先在本地装好 broker。
>
> 官方文档：[Installing on Windows Manually](https://www.rabbitmq.com/docs/install-windows-manual)

---

## 0. 先看版本对应关系

RabbitMQ 依赖 Erlang/OTP，**两者版本必须匹配**，装错会启动失败。

- 查表：<https://www.rabbitmq.com/docs/which-erlang>
- Erlang 下载：<https://erlang.org/download/otp_versions_tree.html>
- RabbitMQ 下载（GitHub Releases）：<https://github.com/rabbitmq/rabbitmq-server/releases>

原则：**先定 RabbitMQ 版本，再照表选 Erlang**。不要去装"最新 Erlang + 旧 RabbitMQ"。

---

## 1. 安装 Erlang/OTP

1. 打开 <https://erlang.org/download/otp_versions_tree.html>
2. 找到对应版本（64 位），下载 **Windows installer**（形如 `otp_win64_27.x.exe`）
3. 双击安装，全部默认下一步即可（默认装到 `C:\Program Files\erl27.x`）

**必须确认 `ERLANG_HOME` 环境变量**：

- 打开「系统属性 → 高级 → 环境变量」
- 新建**系统变量** `ERLANG_HOME`，值为 Erlang 的实际安装目录，例如 `C:\Program Files\erl27.2`
  （这个目录下要能直接看到 `bin\erl.exe`）
- 检查 `Path` 里有 `%ERLANG_HOME%\bin`

验证（**新开**一个 PowerShell）：

```powershell
$env:ERLANG_HOME
erl -version
```

---

## 2. 安装 RabbitMQ Server

1. 打开 <https://github.com/rabbitmq/rabbitmq-server/releases>，找对应版本的
   `rabbitmq-server-windows-x.y.z.zip`（Windows 版是 **zip**，不是 exe）
2. 解压，把里面的 `rabbitmq_server-x.y.z` 整个文件夹放到 `C:\Program Files\`
   最终路径形如 `C:\Program Files\rabbitmq_server-x.y.z`
3. 建两个环境变量（方便命令行直接用）：
   - 系统变量 `RABBITMQ_SERVER` = `C:\Program Files\rabbitmq_server-x.y.z`
   - 在 `Path` 追加 `%RABBITMQ_SERVER%\sbin`

验证（新开的 PowerShell）：

```powershell
rabbitmqctl.bat --version
```

---

## 3. 启用管理台插件

以**管理员身份**打开 PowerShell：

```powershell
rabbitmq-plugins.bat enable rabbitmq_management
```

成功会看到 `management`, `management_agent`, `management_metrics_collection` 三个插件被启用。

---

## 4. 启动 broker

两种方式，**二选一，不要同时用**：

### 方式 A：装成 Windows 服务（推荐，开机自启）

```powershell
# 管理员 PowerShell
rabbitmq-service.bat install
rabbitmq-service.bat start
```

之后可以用 `services.msc` 管理，服务名默认就是 `RabbitMQ`。

### 方式 B：当普通程序跑（关窗口就停）

```powershell
rabbitmq-server.bat -detached
```

---

## 5. 验证

```powershell
# 1. 服务状态
Get-Service RabbitMQ

# 2. 节点状态（管理员）
rabbitmqctl.bat status

# 3. 管理台
Start-Process http://localhost:15672
```

管理台账号：**guest / guest**（默认只允许本机访问，正好符合本地开发）。

---

## 6. 创建 vhost 与用户（本项目默认用独立 vhost）

项目默认连接 **vhost `/maibao`**、用户 `maibao`（见 `application.yml`）。注意 vhost 名字本身带前导斜杠，不是 `maibao`。

命令行建（管理员权限的终端）：

```powershell
rabbitmqctl add_vhost /maibao
rabbitmqctl add_user maibao maibao
rabbitmqctl set_permissions -p /maibao maibao ".*" ".*" ".*"
```

管理台建：`Admin → Virtual Hosts → Add a new virtual host`，Name 填 `/maibao`；再到 `Users` 建 `maibao`，在该用户上 `Set permission` 选 `/maibao`，三个正则都填 `.*`。

> 不建 vhost 也不会挡住应用启动（应用照常起来），但交换机/队列声明会失败、消费者注册不上，表现为接口返回 taskId 之后没有 `handle task` 日志。

---

## 7. 在本项目里验证链路

```powershell
# 启动应用
mvn spring-boot:run
```

启动日志里应该能看到队列/交换机声明成功（`RabbitConfig` 里的 `maibao.task.exchange` / `maibao.task.queue`）。

```powershell
# 提交一条异步任务
Invoke-RestMethod -Method Post "http://localhost:8080/api/task/submit?payload=hello"
# 返回 taskId
```

然后看：

- **应用日志**：`sent task xxx to mq` → `[mq] handle task xxx`（消费者手动 ack 后打的）
- **管理台 Queues**：能看到 `maibao.task.queue`，消息数在投递与消费之间跳动
- **幂等验证**：用同一个 `taskId` 再提交一次，消费者应打 `duplicate message, ack and skip`

---

## 8. 常见坑

| 现象 | 原因 / 处理 |
|---|---|
| `rabbitmqctl` 报 `erlang not found` | `ERLANG_HOME` 没设或路径不对（要指向包含 `bin\erl.exe` 的目录） |
| 服务装上了起不来 | 看日志 `%APPDATA%\RabbitMQ\log\*.log`；多半是 Erlang 版本不匹配 |
| 端口被占 | `4369`（epmd）/ `5672`（AMQP）/ `15672`（管理台）；先 `Get-NetTCPConnection -LocalPort 5672` 查占用 |
| 装了两个 Erlang | 官方明确要求**只保留一个**；升级前先卸载 RabbitMQ 服务再换 Erlang 位数 |
| 管理台打不开但 5672 通 | 插件没启用：`rabbitmq-plugins.bat list` 看 `rabbitmq_management` 是否 `[E*]` |
| 数据/日志想挪位置 | 设环境变量 `RABBITMQ_BASE`，默认在 `%APPDATA%\RabbitMQ` |

---

## 9. 卸载 / 重装

```powershell
# 管理员
rabbitmq-service.bat stop
rabbitmq-service.bat remove
```

然后删目录、删环境变量（`ERLANG_HOME` / `RABBITMQ_SERVER`）即可。

---

## 10. 没有 broker 时会怎样

应用**仍然能启动**（监听容器会在后台重连），但：

- `/api/task/submit` 投递失败，返回 500；
- `/actuator/health` 变 DOWN（Rabbit 健康检查失败）；
- 日志里会出现连接失败的重试记录。

这是预期行为：异步链路不降级，缺 broker 就是缺依赖。要用异步任务，先把 broker 装好并建好 vhost（见第 6 节）。
