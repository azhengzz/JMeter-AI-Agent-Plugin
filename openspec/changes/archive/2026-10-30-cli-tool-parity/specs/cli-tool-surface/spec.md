# Spec Delta

## Purpose

定义 `jmeter-cli` 命令面与 IPC `/tool` 白名单之间的契约：哪些 Agent 工具可经 CLI 结构化直调、CLI 动词到工具参数的映射纪律、白名单的 deny-by-default 演进规则，以及 CLI 作为 thin client 的边界约束。

## ADDED Requirements

### Requirement: IPC `/tool` 白名单采用 deny-by-default

IPC `/tool` 端点 SHALL 维护一份显式工具白名单，作为外部结构化直调的唯一闸门。任何不在白名单内的工具（含未来新增的工具）MUST 被拒绝并返回明确的 400 错误，且 MUST NOT 执行任何工具代码。白名单条目 MUST 与 IPC 侧工具注册表中的工具名完全一致（错名即守卫失败）。fs/web/exec 三族工具 MUST 永不进入白名单。白名单拒绝文案 MUST 如实描述当前放行的类别，不得与实际内容矛盾。

#### Scenario: 请求白名单外工具被拒
- **WHEN** 客户端向 `/tool` 提交工具名 `exec` 或任何未列入白名单的工具
- **THEN** 服务端返回 400 与 "tool not allowed over IPC" 错误，工具未执行

#### Scenario: 白名单与注册表一致性守卫
- **WHEN** 白名单被修改引入了注册表中不存在的工具名
- **THEN** 一致性测试失败，提示错名条目

### Requirement: 批量树操作与静态校验工具可经 CLI 直调

`batch_delete_jmeter_elements`、`batch_move_jmeter_elements`、`batch_toggle_jmeter_elements`、`validate_test_plan` SHALL 进入 IPC `/tool` 白名单。经此路径调用时，四者的参数 schema、校验语义与结果格式 SHALL 与 Agent 进程内调用完全同构（同一注册表、同一实现）。`validate_test_plan` 经 IPC 直调 SHALL NOT 触发 goal 证据记账（静态证据仅属于 goal 回合语义；无会话语境的直调天然旁路）。

#### Scenario: CLI 直调批量删除
- **WHEN** `jmeter-cli tool batch_delete_jmeter_elements --params "{\"elementIds\":[5,6]}"`
- **THEN** 请求成功送达并返回聚合删除结果（成功计数与逐元素明细）

#### Scenario: CLI 直调静态校验
- **WHEN** `jmeter-cli tool validate_test_plan`
- **THEN** 返回结构化 PASS/FAIL 校验报告，且不改动任何 goal 状态

### Requirement: CLI 动词与工具参数一一映射

每个 CLI 专属动词 SHALL 映射到映射附录所列的工具（个别命令按参数二选一，如 `get`；`tool` 可达任意白名单工具），并逐参数对齐该工具的参数 schema：CLI 直传的参数键 MUST 与工具 schema 键名一致；CLI 侧 camelCase 旗标对应工具 snake_case 参数的（`ignoreTimers→ignore_timers`、`includeDetails→include_details`、`statusFilter→status_filter`）MUST 显式转换后投递；必填参数缺失 MUST 以 usage 错误退出（退出码 2），可选参数缺省 MUST 不投递（让工具侧默认值生效）。CLI 帮助文案中的参数名、默认值与可选性 SHALL 与工具 schema 保持一致。

新增动词遵循同一纪律（动词 → 工具；括号内为参数约束）：

- `batch-delete`→`batch_delete_jmeter_elements`（`--elementIds` CSV 必填）
- `batch-move`→`batch_move_jmeter_elements`（`--elementIds`、`--targetParentId` 必填，`--position` 可选默认 last）
- `batch-toggle`→`batch_toggle_jmeter_elements`（`--elementIds` 必填，`--action` 可选默认 toggle）
- `validate`→`validate_test_plan`（无参）
- `copy`→`copy_paste_jmeter_element`（`--elementId` 必填；`--targetParentId`/`--position` 可选，缺省由工具落为原位复制）
- `tree`→`get_test_plan_tree`（`--includeProperties`/`--maxDepth` 可选）
- `script-info`→`get_script_info`（无参）
- `query`→`query_element_properties`（`--propertyName`/`--propertyValue` 至少其一——服务端校验，无过滤列举由 `tree`/`find` 承担；其余 `--elementType`/`--matchMode`/`--parentId`/`--includeProperties`/`--maxDepth`/`--offset`/`--limit` 可选）
- `parse`→`parse_jmx_file`（`--file` 必填；`--elementType`/`--includeProperties`/`--maxDepth` 可选；`--limit`/`--offset` 仅 query 模式可用、MUST 与 `--elementType` 同现，无 `--elementType` 时提供 MUST 以 usage 错误退出）
- `log`→`get_log_panel_content`（`--startLine`/`--endLine`/`--maxLines` 可选，缺省尾读最近 50 行）

既有动词 `run` 新增 `--mode normal|smoke` 旗标（默认 normal，直传 `mode` 参数；smoke 的组合语义与定时器处理以工具端校验为准，CLI 不做客户端组合校验）。

#### Scenario: batch-move 参数构建
- **WHEN** `jmeter-cli batch-move --elementIds 5,6 --targetParentId 2 --position first`
- **THEN** 投递给工具的参数为 `{elementIds:[5,6], targetParentId:2, position:"first"}`

#### Scenario: 必填参数缺失走 usage 错误
- **WHEN** `jmeter-cli batch-move --elementIds 5`（缺 `--targetParentId`）
- **THEN** CLI 报 "missing required option --targetParentId" 并以退出码 2 结束，不发起 IPC 请求

#### Scenario: 可选参数缺省不投递
- **WHEN** `jmeter-cli batch-toggle --elementIds 5,6`（未传 `--action`）
- **THEN** 投递参数仅含 `{elementIds:[5,6]}`，action 由工具默认值 `toggle` 生效

#### Scenario: copy 缺省目标父节点走工具默认
- **WHEN** `jmeter-cli copy --elementId 5`（未传 `--targetParentId`）
- **THEN** 投递参数仅含 `{elementId:5}`，目标父节点由工具默认为源元素自身父节点（原位复制）

#### Scenario: log 无参尾读
- **WHEN** `jmeter-cli log`
- **THEN** 投递空参数，返回日志面板最近行（工具默认尾读 50 行）

#### Scenario: run 指定 smoke 模式
- **WHEN** `jmeter-cli run --mode smoke`
- **THEN** 投递参数含 `{action:"start", mode:"smoke"}`，阻塞至 smoke 完成并返回判定结果

#### Scenario: query 无过滤参数得到教学错误
- **WHEN** `jmeter-cli query`（未传 `--propertyName`/`--propertyValue`）
- **THEN** 工具返回明确的教学错误（exit 1），CLI 帮助如实描述该约束（不宣称"全可选"）

#### Scenario: parse 分页参数脱离 query 模式被拒
- **WHEN** `jmeter-cli parse --file plan.jmx --limit 20`（未传 `--elementType`）
- **THEN** CLI 以 usage 错误退出（exit 2），说明 `--limit/--offset` 须与 `--elementType` 同现——树模式下这两个参数会被工具静默忽略，不得放行

### Requirement: CLI 保持 thin-client 边界

CLI SHALL 保持零 JMeter/GUI 类依赖（仅依赖 IPC 协议、实例注册表与 JDK/Jackson），新动词不得破坏此边界。CLI 对工具输出格式存在字面耦合的判定点（如 `run --wait` 依赖测试状态输出中的运行态标记）MUST 作为显式输出契约记录并有测试钉定，工具输出格式变更时同步维护。

#### Scenario: run --wait 完成判定
- **WHEN** `jmeter-cli run --wait` 轮询测试状态且状态输出不再包含运行态标记
- **THEN** CLI 判定测试完成，输出最终状态并以退出码 0 结束

### Requirement: 新动词进入帮助体系

全部 10 个新动词（`batch-delete`、`batch-move`、`batch-toggle`、`validate`、`copy`、`tree`、`script-info`、`query`、`parse`、`log`）SHALL 出现在全局帮助的命令列表中，并各自提供 `-h` 级别的用法、参数与示例说明，文案与映射工具的参数语义一致；`run` 的帮助 SHALL 覆盖 `--mode` 旗标。`documents/jmeter-cli.md` SHALL 同步收录新命令。

#### Scenario: 全局帮助列出新动词
- **WHEN** `jmeter-cli help`
- **THEN** 命令列表包含全部 10 个新动词及一行摘要
