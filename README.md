# MCP Gateway

[English](README.en.md) | 简体中文

[![CI](https://github.com/kurukurumi-33/mcp-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/kurukurumi-33/mcp-gateway/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![MCP 2026-07-28](https://img.shields.io/badge/MCP-2026--07--28-blue)
![Conformance 96/96](https://img.shields.io/badge/conformance-96%2F96-brightgreen)
![License Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue)

面向 **MCP 2026-07-28** 无状态协议的多后端网关框架，Java 实现。

一个端点位于多个后端之前。每个后端只需实现一个小型 `BackendAdapter`；协议、工具目录、健康上报和多轮调用由网关负责，新增后端不需要改协议引擎。

**可以验证的结果**

- **协议一致性**：官方 MCP 一致性套件 draft 场景 19 个、96 项检查全部通过。
- **测试与覆盖**：98 个 JUnit 用例在 CI 全量通过，核心模块行覆盖率 86%。
- **多后端适配**：同一套 SPI 接入 S3、DuckDB、Spark，新增后端不改协议引擎。
- **无状态故障切换**：双实例演练中停掉发起节点，requestState 仍可在另一节点完成调用。

先跑起来：

```bash
docker compose up --build -d
curl -s http://127.0.0.1:8931/actuator/health
```

完整步骤见 [Docker](#docker)。

## 为什么需要它

官方 MCP Java SDK 尚未支持这一修订。截至 **2026-10**，`2.1.0`（稳定版）和 `2.2.0-M1`（里程碑版）最高只到协议 `2025-11-25`：

```
$ javap -constants io.modelcontextprotocol.spec.ProtocolVersions
  MCP_2024_11_05 = "2024-11-05"
  MCP_2025_03_26 = "2025-03-26"
  MCP_2025_06_18 = "2025-06-18"
  MCP_2025_11_25 = "2025-11-25"      <-- 没有 2026-07-28
```

新修订删除的会话类型，`McpSession`、`InitializeRequest`、`McpServerSession`，在 SDK 里仍然存在。没有 `server/discover`，也没有多轮请求支持。因此，Java 服务端若要支持 `2026-07-28`，只能自带协议层。

另外，现有叫 "MCP gateway" 的项目聚合的是其他 MCP server。这个项目的方向相反：直接适配普通后端，中间不经过 MCP server。

## 2026-07-28 改了什么，以及本项目实现了什么

| 变更 | 状态 |
|---|---|
| 没有会话，没有 `Mcp-Session-Id`，任意节点可服务任意请求 | 已实现 |
| 移除 `initialize` 握手；版本、客户端信息、能力放在每次请求的 `_meta` 里 | 已实现 |
| `server/discover` 为必需方法 | 已实现 |
| 每个结果都必须带 `resultType`（`complete` / `input_required`） | 已实现 |
| `MCP-Protocol-Version`、`Mcp-Method`、`Mcp-Name` 头部必须与 body 一致 | 已实现，并会拒绝不一致 |
| 多轮请求：服务端返回 `input_required` 与 `inputRequests`，客户端带 `inputResponses` 重放 | 已实现 |
| 错误码 `-32020` / `-32021` / `-32022` | 已实现 |
| 可缓存结果（`ttlMs`、`cacheScope`） | 已实现 |
| 资源：`resources/list`、`resources/templates/list`、`resources/read` | 已实现 |
| 提示词：`prompts/list`、`prompts/get` | 已实现 |
| 进度和日志通知走请求自身的响应流；只有客户端显式选择才发送日志 | 已实现 |
| `subscriptions/listen`、tasks 扩展、OAuth、stdio 传输 | 未实现 |

## 快速开始

```bash
mvn verify                     # 协议、适配器和 Spring 传输层测试，并生成覆盖率报告

# 先安装同级模块，再把 app 起在 :8931（MCP 端点 + actuator 健康检查）
mvn -DskipTests install
mvn -pl mcp-app spring-boot:run

# 对真实服务走一遍完整接口，并打印每一步：
mvn -pl mcp-core exec:java -Ddemo.mainClass=io.github.kurukurumi33.mcp.devtools.DevClientMain

# 或者留着不依赖框架的 dev server，自己用 curl 调 http://127.0.0.1:8931/mcp：
mvn -pl mcp-core exec:java
```

完整走查覆盖发现、带命名空间的目录、普通调用、两轮请求、流式通知、资源与提示词，以及新修订要求的四类拒绝。它会在同一进程里用临时端口起服务，但通过真实 HTTP 通信，所以打印出来的就是客户端会看到的内容。

手工调用任一服务：

```bash
curl -s -X POST http://127.0.0.1:8931/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: server/discover' \
  -d '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

`_meta` 里这两个成员都是必需的。没有握手就没有可协商的对象，因此缺少任何一个都会按无效参数拒绝，而不是给默认值。

如果对带进度上报的工具请求 `Accept: text/event-stream`，响应会切换到 SSE；不带这个头就返回单个 JSON body。

## Docker

整个栈，包括网关、对象存储和桶，一条命令启动：

```bash
docker compose up --build -d
curl -s http://127.0.0.1:8931/actuator/health
```

三个服务，每个在运行时都有明确用途：

| 服务 | 为什么单独一个容器 |
|---|---|
| `seaweedfs` | 一个非 AWS 的 S3 端点，让适配器面对真实部署同样的 API。MinIO 本来是首选，直到它在 2025 年撤下 Docker Hub 镜像；SeaweedFS 在 `:8333` 上提供同样的协议 |
| `createbucket` | 适配器的 `health()` 是 `HeadBucket`，而网关不会自动建桶，所以 `docker/create-bucket.sh` 在网关启动前运行一次并退出 |
| `gateway` | 多阶段镜像。构建阶段用 `maven:3.9-eclipse-temurin-21`，运行阶段是 JRE、非 root 用户，健康检查打 `/actuator/health` |

`MCP_REQUEST_STATE_SECRET` 在 `docker-compose.yml` 里固定，而不是留给每个进程随机生成。这是第二个副本能工作的前提：一个实例签发的 `requestState` 必须在另一个实例上验签通过，所以这个密钥属于部署级状态，不是进程级状态。

S3 集成测试指向 compose 栈暴露的同一份存储：

```bash
MCP_S3_ENDPOINT=http://127.0.0.1:8333 \
MCP_S3_ACCESS_KEY=mcpaccess MCP_S3_SECRET_KEY=mcpsecretmcpsecret \
mvn -pl mcp-backends -am test
```

```bash
docker compose down          # 保留数据卷
docker compose down -v       # 连对象一起删除
```

## 多实例

协议声称任意节点都能服务任意请求。`docker/multi-instance-demo.sh` 把这个设计说明变成可执行的实验：

```bash
bash docker/multi-instance-demo.sh
```

它启动两个网关实例，前面放 nginx，然后执行八步。

| 步骤 | 发生什么 | 验证什么 |
|---|---|---|
| 1 | 在节点 A 上调 `storage_put_text`，写入 `demo/incident.txt` | 后端是共享的，对象对两个节点都存在 |
| 2 | 在节点 A 上调 `storage_delete_object`，返回 `input_required` 和 `requestState` | 这一轮结束时服务端没有保留任何状态 |
| 3 | 停掉节点 A | 发起提问的节点已经消失 |
| 4 | 带确认信息把 token 重放到节点 B | 调用的剩余部分不需要 A |
| 5 | 节点 B 列出桶，key 已不存在 | 效果真的发生了，不只是返回 200 |
| 6 | 修改 token 的 payload 后重放 | 返回 `-32602`，`requestState failed signature verification` |
| 7 | A 仍然下线，通过 nginx 连续发三次 `tools/list` | 不需要粘性会话，负载均衡可以使用另一个节点 |
| 8 | 重新启动节点 A | 栈恢复可用 |

第 4 步能成立靠两件事。第一轮响应携带了全部状态，A 上没有可丢的东西。两个容器拿到同一个 `MCP_REQUEST_STATE_SECRET`，所以 B 能验证 A 签发的 token。第 4 步还会断言响应里没有 `Mcp-Session-Id`，这是本修订删除的字段。

边界需要说清楚，因为 "无状态" 描述的是协议层，不是整个系统：

- **适配器也必须无状态，这个测试才会通过。** 内存版 demo `StorageAdapter` 会失败：它写入的对象在 A 的堆里。S3 适配器能通过，因为桶是共享状态，第 1 步就是为此准备的。
- **断掉的流不能恢复。** 进度通知挂在某一次请求的响应体上。承载这个响应体的节点被杀掉，客户端只能看到截断的流；只有多轮调用的下一轮可以恢复，而且是因为客户端手里有 token。
- **只有一个密钥，没有轮换。** 所有实例需要同一个密钥，轮换会使当时在途的所有 token 失效。过期时间按每个实例自己的时钟检查，所以副本之间需要 NTP 同步；时钟比 TTL 快太多的实例会提前拒绝 token。

## 并发与超时

每个后端都是独立资源，所以每个命名空间有自己的并发舱壁：最多同时运行 `max-concurrent-calls-per-backend` 个调用，拿不到槽位的调用会被直接拒绝，而不是排在慢调用后面。

| 情况 | HTTP | JSON-RPC 码 | 响应携带 |
|---|---|---|---|
| 后端所有槽位都被占用 | 429 | `-32000` | `Retry-After: 1`、`error.data.{backend,operation,maxConcurrent}` |
| 调用超过截止时间 | 504 | `-32001` | `error.data.{backend,operation,timeoutMs}` |

```yaml
mcp:
  gateway:
    max-concurrent-calls-per-backend: 16   # 0 关闭舱壁
    max-queued-calls-per-backend: 32       # 只有配置了排队等待才使用
    admission-queue-wait-ms: 0             # 0 表示立即拒绝，不等待
    call-timeout-ms: 30000                 # 0 关闭截止时间
```

有两个限制需要直说：

- **取消是协作式的。** `Thread.interrupt` 只能唤醒阻塞在 `sleep`/`wait`/`park` 或 `InterruptibleChannel` 上的线程；阻塞在 socket 读或原生 JDBC 调用里的线程不会检查中断标志。因此超时的调用通常还在继续运行，并且会正确地占着槽位直到真正结束。网关停止的是等待，不是工作本身；要停止工作，需要客户端库自己的超时或显式 `cancel`。
- **舱壁是进程级的。** 四个实例的实际上限是配置值的四倍。下面的共享令牌桶解决了请求速率的问题；在途并发仍然按实例计数。

HTTP 传输层还有自己的上限。`max-request-bytes` 默认 1 MiB，先检查 `Content-Length`，因为分块请求没有长度头，所以还要在读取过程中按字节计数。超过上限的请求在 JSON 解析之前就返回 `413`、`-32600` 和 `error.data.maxBytes`。控制器刻意不用 `@RequestBody`：那会先把整个请求体缓冲并反序列化，任何限制都来不及生效。

## 共享限流

舱壁保护的是单个进程。四个实例位于负载均衡之后时，四套独立的槽位池会放出四倍于预期的流量。共享限流器把每个后端命名空间的一个令牌桶放进 Redis，补上这个缺口：

```yaml
mcp:
  gateway:
    rate-limit:
      enabled: true
      capacity: 100           # 突发容量
      refill-per-second: 50   # 持续补充速率
      key-prefix: "mcp:gateway:rate:"
      fail-open: true         # Redis 不可达时放行
      timeout-ms: 100         # 单次检查预算
```

令牌桶在本地舱壁之前检查，因为共享预算应该也作用于碰巧有空槽的实例。拿不到令牌的调用和被舱壁拒绝的调用形状相同：HTTP 429、JSON-RPC `-32000`、`Retry-After`；区别是 `error.data.reason` 为 `rate_limit` 而不是 `concurrency`，重试提示来自桶本身，而不是固定的一秒。

两个细节让这个桶是正确的，而不只是近似正确：

- **补充和扣减在同一个 Lua 脚本里。** Redis 原子执行脚本，两个实例不可能同时读到 "只剩一个令牌" 然后都花掉。
- **时钟来自 Redis `TIME`。** 用各实例的墙钟计算经过时间，会让时钟快的机器凭空造出令牌，而这正是共享限流器要防止的故障。

Redis 不可达时，网关不会跟着一起挂掉。`fail-open: true` 放行调用并递增 `mcp.gateway.rate_limit.errors`；`fail-open: false` 则拒绝调用并给出一秒的重试提示。拒绝次数按后端计入 `mcp.gateway.rate_limit.denied`，有 Micrometer 时可以通过 actuator 查看。

检查本身受 `timeout-ms` 约束。只依赖 Redis 客户端的命令超时不够：Lettuce 默认 60 秒，Redis 挂掉时每个工具调用会先卡一分钟才轮到失败策略，桶的检查会活得比它前面的调用截止时间还长。限流器把脚本放到虚拟线程执行，到预算就放弃等待；被放弃的命令会自己完成，结果被丢弃。

fail-open 带来两个后果，`mcp-app` 都已经配置好：

- **Redis 健康指示器关闭。** Redis 不可达时 Boot 会把实例报成 DOWN，负载均衡会把实例摘掉，这与 fail-open 想避免的故障正好相反。告警改用 `mcp.gateway.rate_limit.errors` 计数器。
- **启动后的第一次检查要支付连接握手。** 冷客户端可能超过 `timeout-ms`，所以第一次调用会 fail-open，连接在后台完成；之后的每次检查都只是一次往返。

Redis 实现在 starter 里，通过 `@ConditionalOnClass` 装配，所以 core 和 starter 在应用没有引入 `spring-boot-starter-data-redis` 时保持无依赖。开启限流却没有这个依赖，会在启动时直接报错，而不是静默地不限流。

## 压测

`mcp-loadtest` 是闭循环压测客户端：每个模拟客户端一个虚拟线程，发完 `tools/call` 并收到响应后才发下一条。它刻意不依赖网关的任何代码。如果共享线程池、序列化器或客户端库，测出来的数字会同时描述压测工具和网关。

```bash
mvn -pl mcp-loadtest package
java -jar mcp-loadtest/target/mcp-loadtest-0.1.0-SNAPSHOT.jar \
  --url http://127.0.0.1:8931/mcp \
  --concurrency 32 --duration 10 --think-ms 50 \
  --tool compute_slow_sum --args '{"n":5}'
```

demo 后端的 `compute_slow_sum` 每一步睡 20 ms，`n=5` 时单次调用约 100 ms。这个可预测性很重要：`SELECT 1` 一毫秒就结束，舱壁永远填不满，三种模式看起来会一模一样。

`--url` 可以重复，目标按轮询使用，两个实例共享一个桶的场景就是这样压的。`--requests N` 用来替代持续时间。`--think-ms` 模拟 agent 两次调用之间的思考时间；设成 0 时，被拒绝的客户端会立刻重试，429 的数量更多反映压测客户端的自旋速度，而不是网关容量。

三种准入控制模式可以直接对比：

| 模式 | 配置 |
|---|---|
| 无 | `--mcp.gateway.max-concurrent-calls-per-backend=0`，关闭限流 |
| 舱壁 | 默认 `max-concurrent-calls-per-backend=16`，关闭限流 |
| 共享桶 | 开启限流，两个实例，每个实例一个 `--url` |

先看 `ok ... goodput`。舱壁模式下它会停在 `槽位数 / 单次调用耗时` 附近，不随 `--concurrency` 增长，其余请求落在 `429 concurrency`。共享桶模式下，聚合有效吞吐会保持在配置的 `capacity` 附近，即使负载分散在多个实例上，这是进程内信号量无法提供的性质。完成调用和 429 的延迟分开统计，因为混在一起会让一个以拒绝为主的工作负载看起来很快。

## 测试

`mvn verify` 运行 98 个测试，并在每个模块的 `target/site/jacoco/` 下生成 JaCoCo 报告。CI 在每次推送时用 JDK 21 跑同一条命令，JDK 21 是发布目标而不是本机 JDK；CI 还带 Redis 和 SeaweedFS 服务，所以共享限流和 S3 集成测试也会在那里运行。

| 模块 | 行覆盖率 | 测试重点 |
|---|---|---|
| `mcp-core` | 86% | 协议常量、引擎的头部与 `_meta` 校验、MRTR 往返、`requestState` 签名、三类目录、舱壁与截止时间 |
| `mcp-backends` | 66%（接 SeaweedFS 时 79%） | S3 适配器（单元测试，以及设置 `MCP_S3_ENDPOINT` 后对 SeaweedFS 的集成测试，本地跳过的三个就是它们）、DuckDB 守卫、截断、目录与错误整形 |
| `mcp-spring-boot-starter` | 75% | 真实端口上的 HTTP 端点：状态码、SSE 切换、带 `Retry-After` 的 429、Redis 限流脚本与失败策略、actuator 聚合 |

有两处排除是故意的。`mcp-core` 的报告跳过 `devtools/` 和 `conformance/`：dev server 是没有任何测试驱动的辅助工具，一致性夹具本来就是给官方套件通过网络调用的，统计它们等于统计这里没有执行的代码。

`mcp-backends` 的数字是在存储运行时测的：先 `docker compose up -d seaweedfs`，再带 `MCP_S3_ENDPOINT` 跑同一条 `mvn verify`。不启动存储时，三个 S3 集成测试会自行跳过，模块报告 66%，两个数字的差距就在这里：适配器的上传、列表、带确认删除和 `readResource` 路径都需要真实端点。CI 运行同样的 SeaweedFS 容器，所以那边的数字与这里一致。

测试覆盖不到的内容正是 `docs/manual-tests.http` 存在的原因：HTTP 状态码与 JSON-RPC body 不一致、`Content-Type` 本该切换却没有切换成 `text/event-stream`、SSE 消息是否真的在响应结束前到达。这些是线路上的事实，不是某个返回值的属性。

## 一致性

官方一致性套件扮演客户端，驱动服务端，并按规范检查每个响应。这个修订在套件里仍被算作 draft，所以运行需要 alpha CLI 和 `draft` 套件：

```bash
mvn -DskipTests package
java -jar mcp-app/target/mcp-app-0.1.0-SNAPSHOT.jar --mcp.gateway.conformance-fixtures=true

npx @modelcontextprotocol/conformance@alpha server \
  --url http://127.0.0.1:8931/mcp --suite draft
```

当前结果：draft 服务端场景 **96 通过，0 失败**。

| 场景 | 结果 |
|---|---|
| `server-stateless` | 25 / 25 |
| `http-header-validation` | 14 / 14 |
| `http-custom-header-server-validation` | 10 / 10 |
| `input-required-result-*`（14 个场景） | 35 / 35 |
| `sep-2164-resource-not-found` | 4 / 4 |
| `caching` | 8 / 8 |

修订里有两块内容容易漏掉，因为它们只在 HTTP 层出现：

- **参数镜像（`x-mcp-header`）。** 工具可以把一个字符串参数标注为 `"x-mcp-header": "region"`，客户端随后在 `Mcp-Param-region` 头里重复这个参数。引擎在 handler 运行前比较两者：不一致，或者 body 里有值但头缺失，都返回 `-32020` 和 HTTP 400。被 `=?base64?...?=` 包裹的值按 Base64 解码，必须校验填充；没有包裹的值按字面量比较。检查逻辑在 `McpServerEngine.validateParameterHeaders`。
- **MRTR 不只用于工具。** `prompts/get` 也可以返回 `input_required`，带自己的签名 `requestState`，客户端的回答在下一次 `prompts/get` 里作为 `inputResponses` 到达。`PromptResult` 与 `ToolResult` 一样有两种形状，handler 收到的是 `PromptContext` 而不是裸的参数对象，因此能区分第一轮和恢复轮。

套件驱动的 `test_*` 夹具在 `mcp-core/.../conformance/`，只有 `mcp.gateway.conformance-fixtures` 为 true 时才注册。它们的工具和提示词名称发布时不带命名空间前缀，这是 `BackendAdapter.prefixedNames()` 的退出选项。

## 资源与提示词

资源和提示词属于同一套 SPI，以默认方法提供，所以既没有资源也没有提示词的后端不需要实现任何额外内容。

| 方法 | 引擎做什么 |
|---|---|
| `resources/list` | 把所有适配器的资源拍平成一个列表，uri 形如 `mcp://<namespace>/<path>` |
| `resources/templates/list` | 对 RFC 6570 模板做同样的事 |
| `resources/read` | 拆分 uri，路由到所属适配器，整形文本或二进制内容 |
| `prompts/list` | 发布带命名空间前缀的提示词名称，包含参数定义 |
| `prompts/get` | 校验必填参数，然后路由到适配器渲染消息或请求输入 |

有四个决策直接体现在线上格式里：

- **适配器只声明后端本地路径，永远不声明 uri。** 适配器说 `reports/q3.parquet`；网关发布 `mcp://storage/reports/q3.parquet`。两个后端可以各自持有 `reports/q3.parquet`，替换存储实现也不会让客户端已经缓存的 uri 失效。路由不需要索引查找：第一段路径就是命名空间。
- **文本还是二进制由适配器决定。** `ResourceContent` 要求 `text` 和 `blob` 恰好有一个，由产生它的工厂决定。在引擎里根据 mime 类型猜测，会把手工上传的对象全部误判成 `application/octet-stream`。
- **资源不存在是 JSON-RPC 错误，不是工具错误。** 返回 `-32602` 和 `error.data.uri`，绝不是 `200` 加 `contents: []`，因为客户端会把空数组渲染成空文件。
- **提示词参数在引擎里校验。** 必填参数声明在 `PromptDefinition` 里，缺少时在适配器运行前就返回 `-32602`。
- **提示词名称可以跳过前缀。** `prompts/get` 只用名称寻址，命名空间必须有地方放；默认加前缀，`prefixedNames()` 是工具和提示词共用的退出选项，用于名称本身已经全局唯一的情况。

```java
@Override public List<ResourceDefinition> resources() {
    return List.of(ResourceDefinition.of("reports/q3.parquet", "Q3 report",
            "application/vnd.apache.parquet"));
}

@Override public List<ResourceTemplate> resourceTemplates() {
    return List.of(ResourceTemplate.of("{+key}", "any object by key", null));
}

@Override public Optional<ResourceContent> readResource(String uri) {
    byte[] bytes = fetch(uri);
    return Optional.of(isProbablyText(bytes)
            ? ResourceContent.text(uri, "text/plain", new String(bytes, UTF_8))
            : ResourceContent.binary(uri, "application/octet-stream", bytes));
}
```

## 对象存储

`mcp-backends` 里的 `S3StorageAdapter` 面向 S3 API 编写，而不是绑定某一家厂商，所以同一份适配器可以服务 SeaweedFS、MinIO、Ceph、阿里云 OSS 或 AWS，只需要换 endpoint。强制使用路径风格寻址，因为自建实现很少在裸地址上做虚拟主机桶。

```bash
MCP_S3_ENDPOINT=http://127.0.0.1:8333 \
MCP_S3_ACCESS_KEY=mcpaccess MCP_S3_SECRET_KEY=mcpsecretmcpsecret \
java -jar mcp-app/target/mcp-app-0.1.0-SNAPSHOT.jar
```

没有配置 endpoint 时，应用回退到内存版 demo 适配器。集成测试也由同一个环境变量控制，因此没有存储的机器上构建仍然能通过：

```bash
MCP_S3_ENDPOINT=http://127.0.0.1:8333 mvn -pl mcp-backends -am test
```

## SQL 后端

`DuckDBQueryAdapter` 让模型能看数据，而不是只看对象 key。它按模型应该使用的顺序暴露三个工具：

| 工具 | 用途 |
|---|---|
| `analytics_list_tables` | 列出 schema 里的表和视图，默认 `main` |
| `analytics_describe_table` | 返回某张表的列、类型、可空性和默认值 |
| `analytics_run_sql` | 执行一条只读语句，返回列和行 |

目录和查询放在同一个后端，而不是拆成两个，因为内存版 DuckDB 数据库只存在于它自己的连接上。第二个适配器必须共享这个连接，等于把一个资源变成两个会一起挂掉的后端。同一命名空间也意味着模型会同时看到 `analytics_list_tables` 和 `analytics_run_sql`，知道它们读的是同一份数据。

```bash
curl -s -X POST http://127.0.0.1:8931/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: analytics_run_sql' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"analytics_run_sql","arguments":{"sql":"SELECT customer, amount FROM orders ORDER BY amount DESC"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

目录类工具把参数绑定为 JDBC 参数，而不是用参数拼语句，所以表名永远是数据。`describe_table` 遇到不存在的表会返回一次失败调用，并提示模型先运行 `list_tables`，而不是丢给模型一个需要自己解码的驱动异常。

`run_sql` 的守卫有三层，因为只做一层很容易被绕过：

- 语句必须以只读关键字开头，检查发生在剥离字符串字面量和注释之后，因此 `SELECT 'drop table x'` 不会被误判成写操作；
- 拒绝第二条语句分隔符，所以 `SELECT 1; DROP TABLE t` 永远不会到达驱动；
- 用 `setMaxRows` 限制结果，大表上的 `SELECT *` 不会撑爆网关堆。

`jdbc:duckdb:` 是内存库，启动时会插入示例 `orders` / `events` 表。把 `MCP_DUCKDB_URL` 指向文件可以持久化；此时会跳过种子数据，重启不会重复插入。

| 配置 | 默认值 | 含义 |
|---|---|---|
| `MCP_DUCKDB_URL` | `jdbc:duckdb:` | 内存库或 `.duckdb` 文件 |
| `mcp.gateway.duckdb.max-rows` | 200 | 单次调用硬上限 |
| `mcp.gateway.duckdb.timeout-seconds` | 5 | 驱动支持时传给 `setQueryTimeout` |

## Spark 后端

`SparkQueryAdapter` 通过 `spark-submit` 暴露 `spark_run_sql`。Spark 不能嵌进网关 JVM：它自带 Guava、Netty 和 Jackson，这些版本与网关的版本冲突，共享 JVM 会在类加载或第一次查询时失败。子进程还有第二个好处：网关截止时间到达时可以杀掉整棵进程树，JDBC 驱动做不到这一点。

代价是启动时间。每次调用都要启动一个 JVM 和一个 Spark 会话；本机实测一行查询端到端约 9 秒，其中 Spark 自身约 5 秒。因此适配器做了这些限制：

- 同一时间最多运行一个 driver（`max-parallel-jobs: 1`）；槽位被占用时新调用直接失败并提示 "retry in a few seconds"，而不是排在慢任务后面；
- 结果按 `max-rows` 截断（默认 50，硬上限 500），并返回 `truncated`；
- 超时杀掉进程树，被取消的查询不会继续占 CPU；
- `health()` 只检查文件是否存在，因为为了证明 Spark 可用而启动它，会让每次 actuator 轮询付出数秒代价。

语句走与 DuckDB 后端相同的 `SqlGuard`：一条只读语句，在判断首关键字前剥离字面量和注释。

### 运行方式

默认关闭，因为需要本机 Spark。Windows 上 PySpark launcher 还需要 `HADOOP_HOME`，并把 `bin` 放进 `PATH`，同时让 `PYSPARK_PYTHON` 指向真实解释器（微软商店的 `python.exe` 存根会以退出码 49 结束且没有输出）。

```bash
MCP_SPARK_ENABLED=true \
MCP_SPARK_SUBMIT=/path/to/spark/bin/spark-submit \
MCP_SPARK_PYTHON=/path/to/python \
MCP_SPARK_SPARK_HOME=/path/to/pyspark \
MCP_SPARK_HADOOP_HOME=/path/to/hadoop \
java -jar mcp-app/target/mcp-app-0.1.0-SNAPSHOT.jar
```

```bash
curl -s -X POST http://127.0.0.1:8931/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: spark_run_sql' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"spark_run_sql","arguments":{"sql":"SELECT id, id * 10 AS ten FROM range(5)"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

响应是 `5 row(s), 2 column(s)`，行数据在 `structuredContent` 里。

### 一个值得知道的 Windows 陷阱

Spark launcher 会通过控制台代码页重新编码命令行参数，所以包含非 ASCII 字符的绝对脚本路径到达 JVM 时会变成 `?`，启动过程死在 `File.getCanonicalPath` 的 `Bad pathname`。任何 Windows 账户名不是 ASCII 的用户，默认临时目录都会命中这个问题。适配器因此从不把路径放到命令行上：它设置子进程工作目录（Windows 以 Unicode 传递），只传裸文件名。`jobFilesArePassedAsBareNamesSoTheCommandLineStaysAscii` 把这个行为锁住。

| 配置 | 默认值 | 含义 |
|---|---|---|
| `MCP_SPARK_ENABLED` | `false` | 是否加入这个后端 |
| `MCP_SPARK_SUBMIT` | 必填 | `spark-submit` 或 `spark-submit.cmd` |
| `MCP_SPARK_MASTER` | `local[2]` | Spark master URL |
| `MCP_SPARK_PYTHON` | 继承 | 导出为 `PYSPARK_PYTHON` |
| `MCP_SPARK_SPARK_HOME` | 继承 | 导出为 `SPARK_HOME` |
| `MCP_SPARK_HADOOP_HOME` | 继承 | 导出为 `HADOOP_HOME`，并把 `bin` 加到 `PATH` 前 |
| `mcp.gateway.spark.max-rows` | 50 | 单次调用上限，硬上限 500 |
| `mcp.gateway.spark.timeout-seconds` | 60 | driver 时限，`call-timeout-ms` 仍然叠加生效 |
| `mcp.gateway.spark.max-parallel-jobs` | 1 | 本机同时运行的 driver 数 |

## 在 Spring Boot 中使用

依赖 starter，把适配器声明为 bean，端点就出现了：

```java
@SpringBootApplication
public class MyGateway {
    public static void main(String[] args) { SpringApplication.run(MyGateway.class, args); }

    @Bean BackendAdapter warehouse() { return new WarehouseAdapter(); }
}
```

```yaml
mcp:
  gateway:
    path: /mcp
    server-name: my-gateway
    request-state-secret: ${MCP_REQUEST_STATE_SECRET}
    request-state-ttl-seconds: 900
    max-request-bytes: 1048576
```

其余内容，包括注册中心、引擎、控制器和健康贡献器，全部自动配置，每个 bean 都可以通过声明自己的 bean 覆盖。classpath 上有 actuator 时，`/actuator/health` 会在 `mcpBackend` 组件下报告每个后端的状态。

`mcp-core` 里的 dev server 绑定 JDK 自带的 HTTP server，存在的意义是让引擎在没有框架的情况下也能被驱动。它没有加固：没有 TLS、没有认证、没有限制。

## 新增后端

实现 `BackendAdapter` 并注册。协议引擎不需要任何改动。

```java
public final class WarehouseAdapter implements BackendAdapter {

    @Override public String namespace() { return "warehouse"; }

    @Override public List<ToolDefinition> tools() {
        return List.of(ToolDefinition.of(
                "row_count",
                "Counts rows in a table.",
                Json.obj().put("type", "object")
                        .set("properties", Json.obj().set("table", Json.obj().put("type", "string")))
                        .set("required", Json.arr().add("table"))));
    }

    @Override public ToolResult execute(String toolName, RequestContext context) {
        return ToolResult.structured(Json.obj().put("rows", countRows(context.stringArg("table"))));
    }

    @Override public HealthStatus health() {
        return canReachWarehouse() ? HealthStatus.UP : HealthStatus.down("connection refused");
    }
}
```

两条规则让目录保持可信：

- `tools()` 必须在不访问后端的情况下描述工具。它在启动时和每次 `tools/list` 时运行；在这里发网络请求会把一次无关的故障变成损坏的目录。
- `health()` 负责报告可达性。后端下线时工具仍然列出，但标记为不可用，这样 agent 能看到能力存在，而不是静默丢失。

`resources()`、`resourceTemplates()`、`readResource()`、`prompts()` 和 `getPrompt()` 都是默认方法，所以没有资源或提示词的后端完全不用提到它们。覆盖其中一个就够了，引擎和注册中心的其他部分都不需要改。

## 目录结构

```
mcp-gateway/
├── mcp-core/                  协议引擎、BackendAdapter SPI、目录、dev 传输
│   └── src/main/java/io/github/kurukurumi33/mcp/
│       ├── protocol/          线上常量、JSON-RPC 信封、错误码
│       ├── spi/               BackendAdapter、工具、资源、提示词、RequestContext
│       ├── registry/          带命名空间的目录、冲突检测、mcp:// 路由
│       ├── server/            McpServerEngine、RequestStateCodec
│       ├── devtools/          JDK HttpServer 传输与 main
│       ├── conformance/       官方测试套件使用的诊断夹具
│       └── demo/              三个内存示例适配器
├── mcp-backends/              真实适配器：S3StorageAdapter（任意 S3 端点）、DuckDBQueryAdapter、
│                              SparkQueryAdapter、共享 SqlGuard
├── mcp-spring-boot-starter/   自动配置、POST 端点、actuator 健康贡献器
├── mcp-app/                   可运行示例：starter + demo 与真实适配器
├── mcp-loadtest/              零依赖闭循环压测客户端
├── docker/                    nginx 配置、建桶脚本、多实例演示
├── docker-compose.yml         一条命令启动网关与 S3 兼容存储
└── docker-compose.multi.yml   两个实例位于 nginx 之后，用于故障切换实验
```

`mcp-core` 不依赖 Spring，所以引擎和 SPI 可以在任何 Java 运行时里工作。starter 只是一层很薄的绑定；同一套 SPI 后面的适配器不需要其他东西。

## 两个有意的设计取舍

**`requestState` 有签名，也会过期。** 去掉会话后，服务端记住在途调用的地方只剩一个：客户端回传的不透明 token。任何拿到 token 的人都能修改它。网关会按照回传的内容执行，所以 token 如果不签名，调用方就能改写自己要恢复的操作。token 的形式是 `base64url(envelope).base64url(HMAC-SHA256(envelope))`，信封为 `{"exp": <epoch seconds>, "state": <handler state>}`，签名不匹配直接返回 `-32602`，不降级处理：验不过的 token 不是过期，是伪造。

过期解决另一半问题。没有过期时间，一个签名正确的 token 永远有效，因为系统没有记录哪些 token 已经用过；而保存这样的记录正是协议要移除的共享状态。`request-state-ttl-seconds` 默认 15 分钟，限制被截获 token 的重放窗口。要让 token 严格一次性，需要 Redis 或数据库里的 nonce 存储；没有这种存储时，诚实的说法是重放被限制，不是被消除。没有 `exp` 的旧 token 会被拒绝，而不是当作永久有效。

**流式是进度通知，不是单独的 API。** MCP 没有 "流式工具结果"：最终结果是单条消息，中间反馈通过 `notifications/progress` 传递。所以 `BackendAdapter` 没有 streaming 入口；长时间运行的工具调用 `context.progress(...)`，引擎把这些消息放到请求自己的响应流上。这也让代码只有一条路径，而不是两条会逐渐分叉的路径。

## 路线图

- 适配器：在同一套 SPI 后接入 HBase
- `subscriptions/listen`、tasks 扩展、OAuth

## 许可证

Apache License 2.0。