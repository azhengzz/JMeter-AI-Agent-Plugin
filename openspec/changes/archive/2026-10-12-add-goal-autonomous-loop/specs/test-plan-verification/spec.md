# Spec Delta

## Purpose

脚本修改后的分层验证能力：不发流量的静态校验工具与最小成本冒烟运行模式，输出结构化 PASS/FAIL 判定供 Agent 迭代修复。独立于 goal 模式，任何主 Agent 回合均可调用。

## ADDED Requirements

### Requirement: 静态校验工具 validate_test_plan

系统 SHALL 提供 `validate_test_plan` 工具，对当前测试计划树执行不发任何流量的静态检查，检查范围 SHALL 覆盖三类：

1. **结构完整性**——测试计划含至少一个线程组；树上元素的 GUI/模型类可解析加载；关键元素的必填内容非空（如 JSR223 采样器须有脚本或脚本文件）；
2. **schema 回扫**——对树上现有元素按组件参数 schema 全量重校验（未知参数、类型不符、枚举越界、范围违规）；
3. **逻辑 lint**——结构性逻辑错误（如循环控制器缺循环条件、断言配置在不可挂载的父节点）。

检查 MUST NOT 包含建议性警告（如「采样器无断言」）——只报告错误，不报告风格建议。检查 MUST NOT 校验未定义 `${var}` 引用与 HTTP 采样器目标（域名/绝对路径）——变量定义位置（JSR223 脚本、后置处理器运行时赋值等）与 HTTP 请求默认值的运行时合并语义不可静态枚举，此类检查的假阳性会阻塞验证判定，冒烟运行（零 error 硬标准）是这两类问题的仲裁者。工具 SHALL 在任意主 Agent 回合可直接调用（不依赖 goal 模式）， SHALL 声明为并发安全的只读操作。输出 SHALL 为结构化报告：整体 `PASS` / `FAIL` 判定 + 按上述三类分组的错误清单（每条含元素定位、问题描述、修复提示）。

#### Scenario: 结构错误被报告
- **WHEN** 测试计划无线程组，Agent 调用 validate_test_plan
- **THEN** 整体判定 FAIL，结构完整性组报告「无线程组」

#### Scenario: 未定义变量引用不被报告
- **WHEN** HTTP 采样器路径引用 `${userId}` 而计划内无静态可见的定义源
- **THEN** 校验不报告该引用（非静态可判定问题，交由冒烟运行仲裁）

#### Scenario: schema 违规被回扫报告
- **WHEN** 树上既有元素的某属性值超出组件 schema 的枚举/范围约束
- **THEN** schema 回扫组报告违规条目

#### Scenario: 全部通过返回 PASS
- **WHEN** 计划三类检查均无错误
- **THEN** 整体判定 PASS，报告无错误条目

#### Scenario: 普通回合可直接调用
- **WHEN** 用户未使用 /goal，仅要求 Agent 检查脚本
- **THEN** Agent 调用 validate_test_plan 正常返回报告

### Requirement: run_test 冒烟模式

`run_test` SHALL 支持 `mode: "smoke"` 参数（与既有 action=start 语义组合，smoke 即受规模约束的启动）。冒烟运行 SHALL 强制最小成本规模：每个线程组 1 线程 × 1 迭代、调度器关闭、启动延迟 0（经 JMeter 验证模式的 `testplan_validation.*` 属性族实现）；断言与前置/后置处理器 SHALL 保留执行，定时器 SHALL 默认剔除。冒烟 SHALL 执行测试计划的全部线程组（程序化设置全线程组树选择，不依赖用户当前选中节点）。冒烟运行 MUST NOT 修改用户计划中保存的线程组配置（引擎运行于克隆树，运行后用户计划元素属性保持原值）。冒烟启动触发的 JMeter save-before-run（SAVE 写盘）副作用 SHALL 被接受，且收集器节点经既有 Save 剥离机制 MUST NOT 泄漏进 `.jmx`。

#### Scenario: 规模强制为 1×1
- **WHEN** 线程组配置为 50 线程 × 100 迭代，Agent 以 smoke 模式运行
- **THEN** 实际执行为每线程组 1 线程 × 1 迭代

#### Scenario: 全部线程组被执行
- **WHEN** 计划含 3 个线程组且用户当前未选中任何节点
- **THEN** 冒烟运行执行全部 3 个线程组

#### Scenario: 用户计划配置不被修改
- **WHEN** 冒烟运行完成
- **THEN** 测试计划树中线程组仍为用户原配置（50 线程 × 100 迭代），无残留修改

#### Scenario: 断言保留、定时器剔除
- **WHEN** 冒烟运行执行含断言与定时器的计划
- **THEN** 断言执行且失败会影响 sample 成败，定时器不产生等待

#### Scenario: 收集器不泄漏进 .jmx
- **WHEN** 冒烟启动触发 save-before-run
- **THEN** 写出的 `.jmx` 不含结果收集器节点

### Requirement: 冒烟运行的结果采集与来源标记

冒烟运行 SHALL 经既有结果采集机制采集（来源记录为 `AGENT`），`get_test_status` / `get_test_results` SHALL 对冒烟运行返回与普通 Agent 运行同构的实时数据。

#### Scenario: 冒烟结果可读取
- **WHEN** 冒烟运行完成后 Agent 调用 get_test_results
- **THEN** 返回该次运行的采样统计与样本明细

#### Scenario: 来源标记为 AGENT
- **WHEN** 冒烟运行被采集
- **THEN** `get_test_status` 输出运行来源为 AGENT

### Requirement: 机器可判定的验证证据

系统 SHALL 为分层验证提供机器可读的判定来源：静态校验由 `validate_test_plan` 的整体判定直接给出；冒烟判定 SHALL 可由冒烟运行的采集结果得出（零 error sample 即 PASS）。该判定 SHALL 作为 goal 护栏的验证通过证据（见 goal-autonomous-loop 能力），且对普通回合同样可用（Agent 可自行读数判断）。

#### Scenario: 冒烟零 error 判定可得
- **WHEN** 冒烟运行完成且全部 sample 成功
- **THEN** 该次运行的采集结果可得出冒烟 PASS 判定（error 计数为 0）

#### Scenario: 冒烟有 error 判定可得
- **WHEN** 冒烟运行存在失败 sample
- **THEN** 该次运行的采集结果可得出冒烟 FAIL 判定，且失败明细（含断言失败消息，见 run-result-capture 增量）可供定位
