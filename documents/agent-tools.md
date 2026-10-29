# Agent 工具

本页列出 Gitee Ai - JMeter Agent 内置的全部 Agent 工具。基础使用见 [README 快速开始](../README.md#快速开始)。

除子代理工具（默认关闭）外，以下工具在默认配置下均注册启用；各分组可分别通过配置开关关闭（见[配置参考](configuration.md#工具配置)）。

## JMeter 元素工具

| 工具名 | 说明 |
|--------|------|
| `create_jmeter_element` | 创建 JMeter 元素（线程组、采样器、控制器、断言、定时器等） |
| `update_jmeter_element` | 更新已有元素的属性 |
| `batch_update_jmeter_elements` | 批量更新同类型多个元素的属性（一次校验、一次刷新 GUI） |
| `delete_jmeter_element` | 删除指定元素（不可删除 TestPlan 根节点） |
| `batch_delete_jmeter_elements` | 批量删除多个元素（删除前统一校验） |
| `move_jmeter_element` | 移动元素到不同父节点，支持精确位置控制（first / last / before:<id> / after:<id>） |
| `batch_move_jmeter_elements` | 批量移动多个元素到同一父节点的同一位置 |
| `copy_paste_jmeter_element` | 复制粘贴测试计划元素 |
| `toggle_jmeter_element` | 启用、禁用或切换元素状态（禁用的元素在测试执行中被跳过） |
| `batch_toggle_jmeter_elements` | 批量启用、禁用或切换多个元素状态 |
| `get_test_plan_tree` | 获取完整测试计划树结构（JSON） |
| `get_selected_element` | 获取当前选中元素的详细信息 |
| `find_element` | 按名称、类型或路径查找元素 |
| `query_element_properties` | 按属性名/属性值查询测试计划中的元素 |

## JMX 与脚本工具

| 工具名 | 说明 |
|--------|------|
| `parse_jmx_file` | 解析外部 JMX 脚本文件（返回组件树，或按条件过滤查询元素） |
| `open_jmx_file` | 在当前 GUI 中打开外部 JMX 文件（默认替换当前计划，可合并加载） |
| `get_script_info` | 获取当前脚本与运行环境信息（脚本路径、保存状态、JMeter/JDK 版本、JMETER_HOME） |
| `get_log_panel_content` | 按行范围读取 JMeter LoggerPanel 日志面板内容 |

## 测试执行工具

| 工具名 | 说明 |
|--------|------|
| `run_test` | 启动、停止或关闭当前测试计划 |
| `get_test_status` | 获取测试执行状态（运行状态、线程进度、采样数） |
| `get_test_results` | 获取测试结果（响应时间、吞吐量、错误率） |

## 跨实例协作工具（随 IPC 注册，默认开启）

| 工具名 | 说明 |
|--------|------|
| `list_instances` | 列出本机所有存活的 JMeter AI 实例（含自身）及其打开的测试计划 |
| `delegate_to_instance` | 将任务委派给本机另一个 JMeter AI 实例执行，并阻塞等待其结果 |

## 文件系统工具（默认启用）

| 工具名 | 说明 |
|--------|------|
| `read_file` | 读取文件内容，支持分页 |
| `write_file` | 写入文件，自动创建目录 |
| `edit_file` | 编辑文件（字符串替换） |
| `list_dir` | 列出目录内容，支持递归 |

## Web 工具（默认启用）

| 工具名 | 说明 |
|--------|------|
| `web_search` | 搜索互联网（支持 Brave、Tavily、Jina 等搜索引擎） |
| `web_fetch` | 抓取网页内容（优先经 Jina Reader 提取正文，直连时转为 Markdown/纯文本） |

## 命令执行工具（默认启用）

| 工具名 | 说明 |
|--------|------|
| `exec` | 执行 Shell 命令，支持超时和工作目录配置 |

## 子代理工具（默认关闭）

设置 `agent.subagent.enabled=true` 后注册（配置见[配置参考](configuration.md#异步子代理subagent)）。

| 工具名 | 说明 |
|--------|------|
| `spawn` | 将自包含的只读分析任务委派给后台子代理 |
| `subagent_status` | 查询子代理的进度与结果 |

## 浏览器录制工具

与 GUI ●REC 按钮共用同一录制会话（单会话互斥，工具可接管按钮打开的会话）。`jmeter.ai.recorder.enabled=false` 时不注册；open/begin/stop 在 goal 回合硬阻断（录制须人工驱动；get_recording_status 只读保留）。控制指令与 UI 采集脚本运行在 CDP 隔离世界（页面主世界不可见不可调，防伪造）。

| 工具名 | 说明 |
|--------|------|
| `open_recording_browser` | 打开录制浏览器入预备态（零采集；参数：起始 URL、只采域名，均选填） |
| `begin_recording` | 显式开始采集（此刻取 cookie 基线，预备期登录 cookie 得以保留） |
| `stop_recording` | 停止收官（落盘 events.jsonl/cookies.json；预备态调用=取消） |
| `get_recording_status` | 查询会话状态与请求/UI 事件计数、数据目录 |
| `import_recording` | 把已有录制场次确定性转换为独立骨架 .jmx（「稍后导入」入口，goal 回合可用） |

增强套路（业务分组、`${var}` 关联提取、参数化、断言）见技能参考 `skills/jmeter/references/recording-workflow.md`。
