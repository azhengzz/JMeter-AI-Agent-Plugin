# jmeter-cli 命令行客户端

除聊天面板外，还可通过 **jmeter-cli** 命令行驱动正在运行的 JMeter GUI 实例（经 loopback HTTP/IPC），对测试计划做组件增删改查，或向 AI Agent 推消息。CLI 参数与底层工具 schema **1:1 同名**（工具清单见 [agent-tools.md](agent-tools.md)）。IPC 服务默认开启（仅 loopback + token 鉴权），如需关闭设 `jmeter.ai.ipc.enabled=false`（完整配置见[配置参考](configuration.md#ipc--cli-配置)）。

## 常用命令

```bash

# 常用命令(全局选项:--pid --token --json --jmeter-home --timeout)
jmeter-cli list                                                 # 发现实例
jmeter-cli health                                               # 探活
jmeter-cli tool get_test_plan_tree                              # 查 TestPlan 根 id
jmeter-cli find --searchBy name --query "HTTP"                  # 按名称模糊查找元素
jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <id>
jmeter-cli agent "再加一个 5 用户的线程组"
```

完整命令清单、87 条测试用例与一键回归脚本见 [../docs/test/jmeter-cli-test-cases.md](../docs/test/jmeter-cli-test-cases.md)；实现方案见 [../TODO/cli-support-plan.md](../TODO/cli-support-plan.md)。

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
