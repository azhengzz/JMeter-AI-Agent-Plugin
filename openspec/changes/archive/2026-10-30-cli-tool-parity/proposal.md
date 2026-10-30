# Proposal

## Why

CLI（`jmeter-cli`）自 2026-07-25（v0.2.3）后再无功能更新，而同期原生 Agent 新增了一批工具。IPC `/tool` 白名单是 deny-by-default（未列出的工具一律拒绝），导致 `batch_delete_jmeter_elements` / `batch_move_jmeter_elements` / `batch_toggle_jmeter_elements`（2026-07-30）与 `validate_test_plan`（2026-10-12）对 CLI 结构化直调完全不可达——多元素操作只能逐条循环单件命令，静态校验（最适合 CLI/CI 的能力）只能绕道 `agent` 自然语言通道。需要一次白名单对齐 + CLI 动词补齐 + 参数一致性审计 + 全面测试验证，恢复 CLI 与原生工具能力的同步。

## What Changes

- **IPC `/tool` 白名单扩容**：`IpcServer.ALLOWED_TOOLS` 新增 4 个工具：`batch_delete_jmeter_elements`、`batch_move_jmeter_elements`、`batch_toggle_jmeter_elements`、`validate_test_plan`。四者均为树变更/只读校验类，与 Agent 同信任边界，不引入 fs/web/exec 类暴露面。
- **CLI 新增 10 个专属动词**（参数映射纪律见 spec；同步更新 CLI 帮助文案 SPECS）：
  - 白名单新工具 4 个：`batch-delete` / `batch-move` / `batch-toggle`（`--elementIds` CSV 复用既有 `parseIntList`）、`validate`（无参）
  - 白名单内透传工具转动词 6 个：`copy`（copy_paste_jmeter_element；`--elementId` 必填，`--targetParentId`/`--position` 可选）、`tree`（get_test_plan_tree；`--includeProperties`/`--maxDepth` 可选）、`script-info`（get_script_info；无参）、`query`（query_element_properties；`--elementType`/`--propertyName`/`--propertyValue`/`--matchMode`/`--parentId`/`--includeProperties`/`--maxDepth`/`--offset`/`--limit` 全可选）、`parse`（parse_jmx_file；`--file` 必填对齐 `open --file`，`--elementType`/`--includeProperties`/`--maxDepth` 可选）、`log`（get_log_panel_content；`--startLine`/`--endLine`/`--maxLines` 可选，缺省尾读）
- **`run` 暴露 `--mode normal|smoke`**（默认 normal，直传 `mode` 参数）：smoke 为全线程组 1×1 的阻塞式最小验证，完成即返回判定；经 IPC 直调不计 goal 冒烟预算（registry 单参构造的 null 通道旁路，既有语义）。
- **全量参数审计**：13 个既有 CLI 动词 × 工具参数 schema 的静态核对。规划期已完成初核：**全部匹配，无键名错位**（camelCase 直传族 `elementId/parentId/includeProperties/maxDepth/...` 与工具 schema 一致；snake_case 映射族 `ignoreTimers→ignore_timers`、`includeDetails→include_details`、`statusFilter→status_filter` 转换正确）。审计矩阵落入本文档族作为回归基线；发现的一处脆弱耦合（`run --wait` 以 `"**State**: Running"` 字面子串判定运行态）纳入文档化与测试钉定。
- **修正过时拒绝文案**：白名单拒绝提示 `"allowlist: CRUD + read-only query tools only"` 更新为反映实际类别（CRUD + 只读查询 + 测试执行 + 脚本加载/日志）。
- **测试**：单测（新动词参数构建 + 白名单 ⊆ IPC registry 注册名守卫）+ 手工集成验证（`docs/test/jmeter-cli-test-cases.md` 增补新命令用例并对真实 GUI 实例全量执行）。
- **文档同步**：`documents/jmeter-cli.md` 补新命令。

非目标（明确不做）：
- `list_instances` / `read_instance_session` / goal 工具的白名单放开
- `parse_jmx_file`/`open_jmx_file` 的 allowed.dirs 沙箱（独立 TODO 挂账项）
- goal 证据记账接入 IPC 直调路径（`validate_test_plan` 经 IPC registry 单参构造 goal 接线旁路，为既有 null-bypass 语义，维持现状）

## Capabilities

### New Capabilities

- `cli-tool-surface`: CLI 命令面与 IPC `/tool` 白名单的契约——哪些工具可经结构化直调、CLI 动词到工具参数的映射纪律（含 camelCase/snake_case 映射规则）、白名单 deny-by-default 的演进规则、CLI thin-client 边界（不得引入 JMeter 类依赖）。

### Modified Capabilities

（无。`validate_test_plan` 的行为规格不变，仅暴露面变化，`test-plan-verification` 无需求变更。）

## Impact

- **代码**：
  - `src/main/java/org/gitee/jmeter/ai/ipc/IpcServer.java` — `ALLOWED_TOOLS` 扩容 + 拒绝文案
  - `src/main/java/org/gitee/jmeter/ai/cli/JmeterCli.java` — 10 个新动词 + `run --mode`（dispatch + 参数构建器 + SPECS 帮助）
  - `src/main/resources/bin/jmeter-cli.bat` — 退出码传播修复（原 `endlocal` 吞掉 java 退出码，Windows 上 0/1/2 契约整体失效；实测验证前 `.bat` 均返回 0）
  - `skills/jmeter-cli/SKILL.md`、`skills/jmeter-cli/references/cli-reference.md` — 外部 Agent 入口文档同步新动词
- **测试**：
  - `src/test/java/org/gitee/jmeter/ai/cli/JmeterCliTest.java` — 新动词参数构建测试
  - IPC 侧新增白名单守卫测试（断言 `ALLOWED_TOOLS ⊆ JMeterToolRegistry.registerDefaultTools` 注册名集合，防手抖错名）
  - `docs/test/jmeter-cli-test-cases.md` — 增补 + 全量手工执行
- **文档**：`documents/jmeter-cli.md`、CLI `--help` 输出
- **兼容性**：纯增量，无破坏性变更；旧版 CLI 客户端无感知（白名单扩容不影响既有调用）。
- **安全**：4 个新放行工具均无密钥泄露面（树 CRUD + 内存树静态校验）；不触碰 fs/web/exec 封禁边界。
