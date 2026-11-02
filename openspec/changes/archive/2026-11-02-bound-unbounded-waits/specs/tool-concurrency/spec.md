# Spec Delta

## ADDED Requirements

### Requirement: 单例批工具的 GUI 线程等待有界

单例批（非并发安全）工具经统一 EDT 执行咽喉等待 JMeter GUI 事件线程时，等待 SHALL 有界：上限经 `agent.tools.edt.wait.timeout.ms` 配置（默认 120000ms，下限钳 1000ms，无"0=关闭"语义）。

等待超时或被中断（如 Stop）时，系统 SHALL 仅放弃调用方等待并返回结构化错误，错误文案 SHALL：声明操作**结局未知**（可能仍在排队、执行中、或已完成——调用方放弃等待不等于操作未执行）、引导先经 `get_test_plan_tree` 等不依赖 EDT 派发的只读工具复查现状后再行动、明确禁止盲目重试写操作（重试可能叠加在迟到的原操作之上）。

已投递到 EDT 的任务在任何情况下 MUST NOT 被撤回或中断，SHALL 在 GUI 线程恢复后完整执行（与既有 Stop 中断后的孤儿任务语义同构）。工具对 EDT 执行错误 SHALL 可见——任何调用点 MUST NOT 把 EDT 错误吞没为成功形态结果（**例外**：源码注释明示为有意的制备性 best-effort 步骤——如批量工具主块前的 `deselectCurrentNode` warn-only 取消选中——不改变树结构，吞没为告警可接受）。

#### Scenario: GUI 线程长期不派发事件时等待有界

- **WHEN** GUI 事件线程被长任务占用超过配置上限，Agent 调用写树工具（如 create_element）
- **THEN** 工具在上限到达时返回含"结局未知"与复查指引的结构化错误，回合继续推进而非无限停滞
- **AND** jmeter 日志留有 EDT 等待超时记录

#### Scenario: Stop 中断等待后 EDT 任务不撤回

- **WHEN** 工具正在等待 EDT 执行期间用户按下 Stop（运行线程被中断）
- **THEN** 工具返回中止/结局未知语义的错误，不抛出无文案的裸中断
- **AND** 已投递到 EDT 的任务不被取消，GUI 线程恢复后照常完整执行

#### Scenario: 超时错误不得被吞没为成功

- **WHEN** 批量更新工具（batch_update）的 EDT 执行等待超时
- **THEN** 工具返回超时错误结果给模型，MUST NOT 以"Updated 0 of N elements"成功形态掩盖错误

#### Scenario: 正常路径行为不变

- **WHEN** GUI 线程健康，工具的 EDT 操作毫秒级完成
- **THEN** 工具结果、类加载器切换与 `setProperties + refreshCurrentGui` 同块原子性与既有行为完全一致

## MODIFIED Requirements

### Requirement: 并行批内超时与错误互不传染

并行批内每个工具 SHALL 独立享有超时约束；单工具超时或失败 SHALL 仅产生该工具的错误结果/事件，不中断同批其他工具，也不改变 AgentRunner 既有 `failOnToolError` 语义。

并行批工具超时时，系统 SHALL best-effort 中断该工具仍在执行的工作（该路径仅 concurrency-safe 只读工具可达；中断 MUST NOT 波及 EDT 上已排队或执行中的任务）；工具若不响应中断则照常跑完，其超时错误结果不变。

#### Scenario: 批内单工具超时

- **WHEN** 并行批中 readA 正常完成、readB 超过工具超时上限
- **THEN** readA 返回正常结果，readB 返回超时错误结果，批整体不抛异常

#### Scenario: 超时后泄漏的工作被尽力取消

- **WHEN** 并行批中 readB 超时且其池线程仍在阻塞等待（如等待 GUI 线程或慢 IO）
- **THEN** 该池线程被中断以尽早退出，readB 的超时错误结果与同批其他工具均不受影响
- **AND** 若该工具内部已向 GUI 线程排队任务，该任务不受中断影响、稍后照常执行
