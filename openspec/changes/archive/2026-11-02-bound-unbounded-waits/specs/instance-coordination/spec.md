# Spec Delta

## ADDED Requirements

### Requirement: IPC 路由并发容量与长等待隔离

IPC 服务 SHALL 在并发多个长等待请求在场时保持短请求路由可用：并发多个 `/agent` 长回合（最长为服务端超时）或 `/tool` 上的长工具执行（如 run_test 冒烟）期间，`/health` 与秒级 `/tool` 操作 SHALL 即时应答，不被有界 handler 线程池耗尽后的排队饿死。

服务 SHALL NOT 对在跑 `/agent` 回合施加低于 AgentLoop 既有按会话 busy 拒绝语义的隐性并发上限。`/agent` 既有响应语义 SHALL 与扩容前逐项一致：超时自取消目标活跃任务、超时边界竞态窗口内自然完成的回合按真实结果回 200（TOCTOU 分流保留）、取消/异常分别回 409/500 结构化响应。

#### Scenario: 并发长回合期间其他路由可用

- **WHEN** 4 个以上并发 `/agent` 请求各自等待长回合（接近服务端超时）期间，CLI 发起 `/health` 探活
- **THEN** `/health` 立即得到响应，不被长等待排队阻塞

#### Scenario: /tool 长工具执行不饿死健康探活

- **WHEN** 多个 `/tool` run_test 冒烟请求（每个可阻塞 ~200s）在途期间，CLI 发起 `/health` 探活
- **THEN** `/health` 立即得到响应

#### Scenario: 超时自取消语义保持

- **WHEN** `/agent` 回合在服务端超时到点仍未完成
- **THEN** 目标实例取消该活跃任务并返回 504 结构化载荷（含取消原因与部分内容）
- **AND** 取消窗口内自然完成的回合按真实结果回 200

## MODIFIED Requirements

### Requirement: 实例注册表扩展（instanceId 与 jmxPath）

`InstanceRegistry` 的 `InstanceInfo`（当前为 `{pid, port, token, startedAt, bind}`，写入 `port-{pid}.json`）SHALL 扩展两个字段：`instanceId`（与本实例会话键同源的进程级实例标识）与 `jmxPath`（本实例当前打开的 `.jmx` 测试计划绝对路径，无则空）。`port-{pid}.json` 的写入 SHALL 经既有原子写（temp + `ATOMIC_MOVE`）携带这两个新字段。`InstanceRegistry.listInstances()` SHALL 返回所有存活实例及其 `instanceId` 与 `jmxPath`。既有失活清理（TCP 与 PID 双确认）与回环绑定约束保持不变。

`InstanceInfo` SHALL 再扩展 `agentTimeoutMs` 字段：本实例 `/agent` 路由的服务端等待超时毫秒值，于 IPC 服务启动时定格写入。既有读改写式字段维护（如 `jmxPath` 更新）SHALL 保留该字段不丢失。字段对不识别它的旧版本读者 SHALL 无害（被忽略）。

#### Scenario: 注册项携带 instanceId 与 jmxPath

- **WHEN** 一个 JMeter 实例启动并完成 `ADD_ALL` 初始化
- **THEN** 其 `port-{pid}.json` 含 `instanceId`（与会话键同源）与 `jmxPath` 字段
- **AND** `listInstances()` 返回该实例时这两个字段可见

#### Scenario: listInstances 返回各实例及其打开的 jmx

- **WHEN** 机器上并存实例 A（开着 `a.jmx`）与实例 B（开着 `b.jmx`）
- **THEN** 任一实例调用 `listInstances()` 得到两条记录，分别标注 `jmxPath=a.jmx` 与 `jmxPath=b.jmx`

#### Scenario: 失活实例被既有清理逻辑回收

- **WHEN** 一个实例退出（PID 与 TCP 均失活）
- **THEN** 其 `port-{pid}.json` 经既有双确认清理被移除
- **AND** 不出现在后续 `listInstances()` 结果中

#### Scenario: agentTimeoutMs 随注册发布且后续维护不丢

- **WHEN** 实例以 `/agent` 超时配置 300000ms 启动，随后用户打开新 jmx 触发 `jmxPath` 读改写更新
- **THEN** 端口文件仍含 `agentTimeoutMs=300000`，其余字段不丢
- **AND** 旧版本读者（CLI/委派工具）忽略该未知字段不受影响

### Requirement: 委派执行与结果回传（复用既有 /agent 传输）

`delegate_to_instance` SHALL 把任务经既有 `POST /agent` 端点投递给目标实例（与"CLI 驱动 GUI"同一传输：目标 `port` + `token`，回环 HTTP，`IpcRequest` 体）。目标实例 SHALL 经其既有 `/agent` 处理器把消息送入自身 `AgentLoop`（使用其 `instanceId` 会话与自身工具集——它持有目标 jmx 故能执行），产生响应并回传。`delegate_to_instance` SHALL 是阻塞式工具：在工具执行线程上等待目标响应并把响应内容作为工具结果返回给主代理；MUST NOT 在 EDT 上阻塞。

超时与取消 SHALL 复用既有 `/agent` 的 `CompletableFuture` + 超时机制：超时时取消目标实例的活跃任务并返回超时错误。委派方的等待上限 SHALL 优先按目标实例注册项发布的 `agentTimeoutMs` + 余量（10s，覆盖服务器超时分支取消收尾的有界等待）派生；目标为旧版本（未发布该字段）时 SHALL 回退按本实例自身 `jmeter.ai.ipc.agent.timeout.ms` 配置 + 同等余量派生。CLI 直连 `/agent` 的等待上限 SHALL 同理优先按目标发布值 + 余量（10s）派生，字段缺席回退内置缺省；用户显式 `--timeout` SHALL 始终最优先。派生 SHALL 仅作用于 `/agent` 端点：经 `/tool` 路由的 CLI 命令（如 `run --wait`）MUST NOT 按 `agentTimeoutMs` 派生——其语义与 `/agent` 超时无耦合，维持既有缺省。CLI 超时报错文案 SHALL 回显实际生效的等待值（显式传值或派生值），不得报与实际不符的缺省值；CLI 的超时派生与请求发送 SHALL 使用同一次实例解析结果。

#### Scenario: 委派任务被执行、结果回传主代理

- **WHEN** 主代理调用 `delegate_to_instance(jmx="b.jmx", task="运行测试并报告结果")`
- **THEN** 实例 B 的 `AgentLoop` 用自身工具集执行该任务
- **AND** B 的最终响应作为 `delegate_to_instance` 的工具结果返回给主代理 A
- **AND** 主代理 A 据此继续本轮对话

#### Scenario: 委派不阻塞 EDT

- **WHEN** 主代理在工具执行线程调用 `delegate_to_instance` 并等待远程响应
- **THEN** JMeter GUI 的 EDT 不被阻塞，用户界面保持响应
- **AND** 主代理 A 的迭代线程按正常工具调用语义等待工具结果

#### Scenario: 委派超时取消目标活跃任务

- **WHEN** 目标实例在委派等待上限内未完成委派任务
- **THEN** `delegate_to_instance` 返回超时错误
- **AND** 目标实例上该委派触发的活跃任务被取消（复用既有 `cancelActiveTask`），不继续燃烧 token

#### Scenario: 目标发布超时优先于本侧配置

- **WHEN** 实例 A（自身 `jmeter.ai.ipc.agent.timeout.ms=120000`）委派给发布了 `agentTimeoutMs=300000` 的实例 B
- **THEN** A 的委派等待上限按 300000+10000 派生，不按本侧 120000 提前放弃
- **AND** 余量覆盖目标超时分支取消收尾的有界等待——结构化 504（含取消原因）先于委派方 HTTP 超时到达
- **AND** 目标为旧版本（无该字段）时按本侧配置 +10000 派生

#### Scenario: 接收侧复用既有 /agent 路径

- **WHEN** 一个实例收到来自另一实例的委派请求
- **THEN** 请求经既有 `/agent` 处理器进入该实例的 `AgentLoop`，不引入并行的第二套执行引擎
- **AND** 该委派交互记录在该实例自身的 `instanceId` 会话中

#### Scenario: CLI 按目标发布值派生等待

- **WHEN** CLI 对发布了 `agentTimeoutMs=300000` 的实例执行 `agent` 子命令且未显式传 `--timeout`
- **THEN** 请求超时按 300000+10000 派生；目标未发布字段时回退内置缺省（310000）
- **AND** 用户显式 `--timeout` 覆盖一切派生值
