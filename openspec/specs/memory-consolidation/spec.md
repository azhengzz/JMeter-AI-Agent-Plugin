# memory-consolidation Specification

## Purpose

跨实例共享工作空间下的记忆整合与 MEMORY.md 写并发控制——深度提炼（LLM 产出全文重写）、关闭期归档/提炼、跨进程写锁的锁范围契约与 lost-update 防护、两门写序与幂等、关闭期有界预算。

## Requirements

### Requirement: MEMORY.md 跨进程写锁只覆盖读-验证-写

系统 SHALL 以跨进程文件锁互斥 MEMORY.md 的读改写。锁的临界区 SHALL 仅覆盖「重读快照 - 验证未变 - 写盘」（含随后的 HISTORY 追加），MUST NOT 覆盖 LLM 提炼调用（**唯一例外**：乐观重试耗尽后的持锁兜底路径，见下条）——锁持有时间 SHALL 与文件 IO 同量级，而非与 LLM 调用时长同量级。读 MEMORY.md 失败（IO 故障）SHALL 放弃本次整合，MUST NOT 把空串当作快照基线继续提炼或写盘。

#### Scenario: 双实例同时关闭提炼并行进行

- **WHEN** 共享同一工作空间的两个实例同时进入关闭期深度提炼且无快照冲突
- **THEN** 两者的 LLM 提炼调用并行进行，锁仅在各自的最终写盘窗口串行
- **AND** 持续快照冲突触达兜底路径时退化为持锁串行（各自有界预算内），该兜底 SHALL 高声记录日志

#### Scenario: 读失败放弃整合而非以空串为基线

- **WHEN** 乐观路径读取 MEMORY.md 发生 IO 故障（读得空串哨兵）
- **THEN** 本次整合立即放弃（不写盘、不计冲突、不递增失败计数），既有记忆不被覆盖

#### Scenario: 关闭提炼不再卡住对端在跑回合的内联整合

- **WHEN** 实例 A 关闭期深度提炼进行中，实例 B 的在跑回合触达内联记忆整合（需写 MEMORY.md）
- **THEN** B 的等锁时长仅为 A 的写盘窗口（毫秒级），不为 A 的整轮 LLM 时长（分钟级）

### Requirement: 乐观并发与 lost-update 防护

以锁外快照参与 LLM 提炼的写入方，在写盘前 SHALL 于锁内重读 MEMORY.md 并验证快照仍新鲜：新鲜则写盘；已过期 SHALL 以最新内容为新快照重试提炼（有限次，上限 2 次）；重试耗尽 SHALL 退化为持锁完成整轮提炼（锁内含 LLM 调用，**替换**而非追加最后一次乐观尝试，单次整合的 LLM 调用总轮数不因此突破既有失败重试上限），保底绝不丢失并发写入。

冲突重试 SHALL 与失败处理严格隔离：快照过期不是 LLM 失败，MUST NOT 计入失败重试计数、MUST NOT 触发原始归档（rawArchive）路径。每次乐观尝试之间 SHALL 保留中止/超时检查点。

#### Scenario: 提炼期间他实例写入触发重试

- **WHEN** 实例 A 以快照 M0 完成提炼、取锁重读发现 MEMORY.md 已被实例 B 改写为 M1
- **THEN** A 放弃基于 M0 的写盘，以 M1 为新快照重试提炼，且该重试不计入失败计数、不触发原始归档
- **AND** 最终写盘包含 B 的内容与 A 的提炼成果，无相互覆盖

#### Scenario: 持续冲突时退化为持锁保底

- **WHEN** 重试次数耗尽仍检测到快照过期
- **THEN** 最后一次以持锁方式完成整轮提炼（行为退化为串行语义）
- **AND** 任何路径下都不发生丢失并发写入的整文件覆盖

### Requirement: 两门写序与幂等

整合落盘 SHALL 先写 MEMORY.md、成功后仅追加 HISTORY.md：MEMORY 写失败 MUST NOT 追加 HISTORY（防重试时历史条目重复累积）；LLM 产出与当前记忆内容相同（无变更）SHALL 跳过 MEMORY 写、仅补 HISTORY。任一门写失败 SHALL 报告整合失败（会话保留、关闭路径不清会话，HISTORY 归档兜底不丢数据）。HISTORY.md 追加 SHALL NOT 以跨进程串行为前提（跨进程锁只保护 MEMORY.md 读改写；单条 append 的原子性依赖文件系统语义）。

#### Scenario: MEMORY 写失败不追加历史

- **WHEN** MEMORY.md 写盘失败（只读/盘满）
- **THEN** 不追加 HISTORY.md 条目，整合报告失败
- **AND** 下次重试不会产生重复的历史条目

#### Scenario: 无变更时幂等跳过

- **WHEN** LLM 产出的 memory_update 与当前 MEMORY.md 内容相同
- **THEN** 跳过 MEMORY.md 写，仅追加 HISTORY.md 条目

### Requirement: 关闭期提炼有界预算与可中止等锁

关闭期深度提炼 SHALL 受 `agent.memory.consolidate-on-exit.timeout.ms` 有界预算约束（预算含等锁与 LLM 全程，含乐观重试）；预算耗尽或被中止 SHALL 放弃落盘并返回失败（不降级为无锁写、不清会话）。等锁 SHALL 可中止：abort 置位或线程中断时 SHALL 放弃等待并视为未执行。

#### Scenario: 预算耗尽放弃落盘

- **WHEN** 关闭期提炼在其有界预算内未完成（含等锁与重试）
- **THEN** 提炼不落盘、返回失败，退出流程继续
- **AND** 会话消息仍由静默归档进 HISTORY.md 兜底，不丢数据

#### Scenario: 等锁中被中止不降级

- **WHEN** 等待 MEMORY.md 写锁期间回合被取消或线程被中断
- **THEN** 放弃等待并按未执行处理，MUST NOT 降级为无锁写盘
