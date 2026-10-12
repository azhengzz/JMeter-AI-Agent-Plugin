# Spec Delta

## ADDED Requirements

### Requirement: 断言结果采集与暴露

结果收集器 SHALL 采集每个 sample 的断言结果：断言名称、失败/错误标志、失败消息（期望与实际不等时的 `failureMessage`）。`get_test_results` 的输出 SHALL 对断言失败的 sample 暴露断言失败信息（断言名 + 失败消息）：samples 表中该 sample 的成功列反映断言失败，详情视图（include_details）含断言失败块。超长失败消息 SHALL 与响应体同款截断上限约束。既有字段语义 MUST NOT 变化：`success` 布尔与 errorCount 计数已含断言失败影响（现状仅消息丢失），无断言失败的 sample 输出与现状同构（不新增空断言块）。

#### Scenario: 断言失败消息在详情中可见
- **WHEN** 一次运行中某 sample 的 JSON 断言失败（期望 `code=0` 实际 `code=401`），Agent 调用 get_test_results(include_details)
- **THEN** 该 sample 的详情块含断言名与失败消息（含期望/实际差异描述）
- **AND** Agent 无需读原始 JTL 即可定位断言失败原因

#### Scenario: 断言失败使 sample 成败正确反映
- **WHEN** HTTP 响应码 200 但断言判定失败
- **THEN** 该 sample 在 samples 表中标记为失败，error 统计计入

#### Scenario: 无断言失败不新增输出结构
- **WHEN** sample 全部断言通过
- **THEN** get_test_results 输出与既有结构同构，无空断言块

#### Scenario: 超长失败消息截断
- **WHEN** 断言失败消息超过单字段截断上限
- **THEN** 输出中的失败消息被截断（与响应体同款上限），不撑爆工具结果
