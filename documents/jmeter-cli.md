# jmeter-cli 命令行客户端

除聊天面板外，还可通过 **jmeter-cli** 命令行驱动正在运行的 JMeter GUI 实例（经 loopback HTTP/IPC），对测试计划做组件增删改查，或向 AI Agent 推消息。CLI 参数与底层工具 schema **1:1 同名**（工具清单见 [agent-tools.md](agent-tools.md)）。IPC 服务默认开启（仅 loopback + token 鉴权），如需关闭设 `jmeter.ai.ipc.enabled=false`（完整配置见[配置参考](configuration.md#ipc--cli-配置)）。

## 常用命令

```bash

# 常用命令(全局选项:--pid --token --json --jmeter-home --timeout)
jmeter-cli list                                                 # 发现实例
jmeter-cli health                                               # 探活
jmeter-cli tree                                                 # 查整树结构/TestPlan 根 id
jmeter-cli find --searchBy name --query "HTTP"                  # 按名称模糊查找元素
jmeter-cli query --propertyName HTTPSampler.domain --propertyValue example.com   # 按属性查
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <id>
jmeter-cli copy --elementId <id>                                # 深拷贝元素(缺省原位复制)
jmeter-cli batch-toggle --elementIds 5,6,7 --action disable     # 批量禁用
jmeter-cli validate                                             # 静态校验当前计划
jmeter-cli run --mode smoke                                     # 冒烟运行(全 TG 1×1,阻塞至判定)
jmeter-cli agent "再加一个 5 用户的线程组"
```

完整命令清单、109 条测试用例与一键回归脚本见 [../docs/test/jmeter-cli-test-cases.md](../docs/test/jmeter-cli-test-cases.md)；实现方案见 [../TODO/cli-support-plan.md](../TODO/cli-support-plan.md)。

`skills/jmeter-cli/` 是面向**第三方 Agent**（如 OpenClaw、Hermes、Codex 等外部自动化工具）使用的 skill 文档，指导它们通过 `jmeter-cli` 命令操作运行中的 JMeter GUI 实例。它不在插件内加载，而是由外部 Agent 的 skill 系统读取。

## 在外部 Agent 中接入 jmeter-cli

`skills/jmeter-cli/` 遵循 [Agent Skills](https://agentskills.io/) 开放标准（`SKILL.md` + YAML frontmatter），Claude Code、Codex、OpenClaw、Hermes 均原生支持——**同一份 skill 目录即可被这四类 Agent 读取，区别仅在于放置目录不同**。

**前置条件**（所有 Agent 通用）：

1. 启动 JMeter GUI（IPC 默认开启；若曾显式关闭，需设 `jmeter.ai.ipc.enabled=true` 重新开启）
2. 让 `jmeter-cli` 可被调用：将 `$JMETER_HOME/bin` 加入 `PATH`，或设置 `JMETER_HOME` 环境变量（CLI 会据此定位 `jmeter-cli.bat` / `jmeter-cli.sh`）

**各 Agent 安装方式：**

| Agent | Skill 目录 | 安装方式 |
|-------|-----------|---------|
| Claude Code | `~/.claude/skills/`（全局）或 `<项目>/.claude/skills/`（项目级） | 复制 `skills/jmeter-cli/` 到该目录 |
| Codex | `~/.codex/skills/`（全局）或 `.agents/skills/`（项目级） | 复制 `skills/jmeter-cli/` 到该目录；项目级须为**真实目录**，不能是符号链接 |
| OpenClaw | `~/.openclaw/skills/`（全局）或工作区 `./skills/` | `openclaw skills install ./skills/jmeter-cli --global`（去掉 `--global` 装到工作区） |
| Hermes | `~/.hermes/skills/` | 复制 `skills/jmeter-cli/` 到该目录 |

示例（以 Claude Code 全局安装为例）：

```bash
# Linux / macOS
cp -r skills/jmeter-cli ~/.claude/skills/

# Windows (PowerShell)
Copy-Item -Recurse skills/jmeter-cli $HOME\.claude\skills\
```

安装后，Agent 会依据 `SKILL.md` 的 `description` 自动判断何时调用 `jmeter-cli`，并按其中的工作流执行 `list → health → get/find → create/update → run → results`。完整命令清单见 [../skills/jmeter-cli/references/cli-reference.md](../skills/jmeter-cli/references/cli-reference.md)。

> **可选：** 若希望 Agent 直接引用各 JMeter 组件的属性名与 schema，可将组件技能 `src/main/jmeter-agent/skills/jmeter/` 一并复制到同一 skills 目录（cli skill 中的 `../jmeter/SKILL.md` 即指向它）。

## 附录：命令 × 工具参数映射矩阵（审计基线）

CLI 命令到 IPC 工具的映射见下表（`list`/`health`/`agent` 不经 `/tool`；`get` 按参数二选一；`tool` 可达任意白名单工具），参数键与工具 schema 一一对应。两类映射约定：

- **camelCase 直传**：CLI 旗标名 = 工具参数名（如 `--includeProperties` → `includeProperties`），缺省不投递、由工具默认值生效
- **snake_case 转换**：工具参数为 snake_case 时 CLI 用 camelCase 旗标显式转换（仅 `ignoreTimers→ignore_timers`、`includeDetails→include_details`、`statusFilter→status_filter` 三处）

| CLI 命令 | 工具 | 参数映射（CLI 旗标 → 工具参数） |
|---|---|---|
| `list` | —（直读端口文件） | — |
| `health` | —（GET /health） | — |
| `get` | `find_element` / `get_selected_element` | `--elementId`→query(searchBy=elementId)；`--includeProperties`/`--maxDepth` 直传 |
| `find` | `find_element` | `--searchBy`/`--query` 必填；`--exactMatch`/`--includeProperties`/`--maxDepth`/`--offset`/`--limit`/`--parentId` 直传 |
| `query` | `query_element_properties` | 全可选：`--elementType`/`--propertyName`/`--propertyValue`/`--matchMode`/`--parentId`/`--includeProperties`/`--maxDepth`/`--offset`/`--limit` 直传 |
| `tree` | `get_test_plan_tree` | `--includeProperties`/`--maxDepth` 直传 |
| `script-info` | `get_script_info` | 无参 |
| `open` | `open_jmx_file` | `--file`→filePath；`--merge`/`--includeProperties`/`--maxDepth` 直传 |
| `parse` | `parse_jmx_file` | `--file`→filePath；`--elementType`/`--includeProperties`/`--maxDepth` 直传 |
| `create` | `create_jmeter_element` | `--elementType`/`--elementName` 必填；`--parentId` 直传；`--properties` JSON |
| `copy` | `copy_paste_jmeter_element` | `--elementId` 必填；`--targetParentId`/`--position` 直传（缺省=原位复制） |
| `update` | `update_jmeter_element` | `--elementId` 必填；`--properties` JSON 必填 |
| `delete` | `delete_jmeter_element` | `--elementId` 必填 |
| `batch-delete` | `batch_delete_jmeter_elements` | `--elementIds` CSV→elementIds（≤50） |
| `move` | `move_jmeter_element` | `--elementId`/`--targetParentId` 必填；`--position` 直传 |
| `batch-move` | `batch_move_jmeter_elements` | `--elementIds` CSV + `--targetParentId` 必填；`--position` 直传 |
| `toggle` | `toggle_jmeter_element` | `--elementId` 必填；`--action` 直传 |
| `batch-toggle` | `batch_toggle_jmeter_elements` | `--elementIds` CSV 必填；`--action` 直传 |
| `batch` | `batch_update_jmeter_elements` | `--elementIds` CSV + `--properties` JSON 必填 |
| `validate` | `validate_test_plan` | 无参（只读 lint，不记 goal 证据） |
| `log` | `get_log_panel_content` | `--startLine`/`--endLine`/`--maxLines` 直传（缺省尾读 50 行） |
| `run` | `run_test` | action=start；`--mode`→mode（normal/smoke）；`--ignoreTimers`→ignore_timers；`--properties` JSON；`--wait` 客户端轮询 |
| `stop` | `run_test` | action=stop |
| `shutdown` | `run_test` | action=shutdown |
| `status` | `get_test_status` | 无参 |
| `results` | `get_test_results` | `--format`/`--limit`/`--offset` 直传；`--includeDetails`→include_details；`--statusFilter`→status_filter |
| `agent` | —（POST /agent，自然语言回合） | 位置参数/--message；`--session` |
| `tool` | 白名单内任意工具 | `--params` JSON 原样透传 |

维护约定：新增 CLI 命令或工具参数变更时同步本矩阵；`run --wait` 依赖 `get_test_status` 输出中的 `"**State**: Running"` 字面标记（`JmeterCli.RUNNING_STATE_MARKER`，有钉定单测），工具输出格式变更须同步。
