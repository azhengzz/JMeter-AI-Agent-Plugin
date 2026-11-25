# Spec Delta

## ADDED Requirements

### Requirement: 保存工具可经 CLI 直调

`save_test_plan` SHALL 进入 IPC `/tool` 白名单，并经专属动词 `save` 暴露。经此路径调用时，参数 schema、校验语义、保护规则与结果格式 SHALL 与 Agent 进程内调用完全同构（同一注册表、同一实现）。本变更 MUST NOT 为该工具引入目录沙箱：`--file` 不按文件系统工具的 `allowed.dirs` / `denied.dirs` 校验，与 `parse_jmx_file` / `open_jmx_file` 的现状一致；写盘面由工具侧的 `.jmx` 扩展名限定与另存覆盖保护约束。

#### Scenario: CLI 直调存回关联文件
- **WHEN** `jmeter-cli save`（计划已关联文件）
- **THEN** 返回保存路径与文件字节数，关联文件被当前测试计划覆写

#### Scenario: 未保存计划的无参保存得到教学错误
- **WHEN** `jmeter-cli save`（计划从未保存）
- **THEN** 工具返回说明「计划从未保存，请提供 --file」的错误，CLI 以退出码 1 结束，不产生任何文件

#### Scenario: 另存到已有文件未带 overwrite 被拒
- **WHEN** `jmeter-cli save --file D:/plans/existing.jmx`（文件已存在，未传 `--overwrite`）
- **THEN** 工具返回说明需要 `--overwrite` 的错误，该文件内容不变

## MODIFIED Requirements

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
- `save`→`save_test_plan`（`--file` 可选，映射 `filePath`，缺省 = 存回关联文件；`--overwrite` 可选，缺省不投递。路径形态约束与覆盖保护由工具端校验，CLI 不做客户端复制）

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

#### Scenario: save 无参存回关联文件
- **WHEN** `jmeter-cli save`
- **THEN** 投递空参数给 `save_test_plan`，返回保存路径与文件字节数

#### Scenario: save 另存并覆盖
- **WHEN** `jmeter-cli save --file D:/plans/login-v2.jmx --overwrite`
- **THEN** 投递参数为 `{filePath:"D:/plans/login-v2.jmx", overwrite:true}`

### Requirement: 新动词进入帮助体系

下列动词——`batch-delete`、`batch-move`、`batch-toggle`、`validate`、`copy`、`tree`、`script-info`、`query`、`parse`、`log`、`save`——SHALL 出现在全局帮助的命令列表中，并各自提供 `-h` 级别的用法、参数与示例说明，文案与映射工具的参数语义一致；`run` 的帮助 SHALL 覆盖 `--mode` 旗标；`save` 的帮助 SHALL 写明另存覆盖已有文件需要 `--overwrite`、未保存的计划必须提供 `--file`。`documents/jmeter-cli.md` SHALL 同步收录新命令。

#### Scenario: 全局帮助列出新动词
- **WHEN** `jmeter-cli help`
- **THEN** 命令列表包含上述全部动词及一行摘要

#### Scenario: save 帮助写明覆盖保护
- **WHEN** `jmeter-cli save -h`
- **THEN** 用法、参数说明与示例齐备，并写明覆盖已有文件需要 `--overwrite`
