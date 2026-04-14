# JMeter AI - 支持的组件

本文档列出了 JMeter AI 扩展已支持的所有 JMeter 组件。这些组件的 Schema 定义文件位于 `src/main/jmeter-agent/skills/jmeter/references/` 目录下。

> **说明**：以下组件均已提交到代码仓库，具备完整的参数校验规则（Schema）定义。

## 统计概览

| 类别 | 组件数量 |
|------|----------|
| 采样器 (Samplers) | 6 |
| 逻辑控制器 (Controllers) | 8 |
| 配置元件 (Config Elements) | 4 |
| 断言 (Assertions) | 5 |
| 后置处理器 (Post Processors) | 6 |
| 前置处理器 (Pre Processors) | 3 |
| 线程组 (Thread Groups) | 4 |
| 定时器 (Timers) | 4 |
| **总计** | **40** |

---

## 采样器 (Samplers)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| HTTP Request | `httpsampler` | 发送 HTTP/HTTPS 请求到 Web 服务器 |
| JDBC Sampler | `jdbcsampler` | 执行 SQL 查询和数据库操作 |
| JSR223 Sampler | `jsr223sampler` | 使用 JSR223 脚本编写自定义采样逻辑 |
| BeanShell Sampler | `beanshellsampler` | 使用 BeanShell 脚本编写自定义采样逻辑 |
| Debug Sampler | `debugsampler` | 调试用途，显示 JMeter 变量和属性 |
| Flow Control Action | `flowcontrolaction` | 控制测试流程（暂停、停止等） |

---

## 逻辑控制器 (Controllers)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| Loop Controller | `loopcontroller` | 控制其子元素的执行次数 |
| If Controller | `ifcontroller` | 根据条件决定是否执行子元素 |
| While Controller | `whilecontroller` | 当条件为真时持续执行子元素 |
| Transaction Controller | `transactioncontroller` | 将多个请求分组为一个事务 |
| Foreach Controller | `foreachcontroller` | 遍历输入变量数组 |
| Random Controller | `randomcontroller` | 随机选择一个子元素执行 |
| Simple Controller | `simplecontroller` | 分组组织测试元素，无逻辑控制 |
| Include Controller | `includecontroller` | 引入外部 JMX 文件中的测试计划片段 |

---

## 配置元件 (Config Elements)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| HTTP Header Manager | `headermanager` | 管理 HTTP 请求头 |
| HTTP Request Defaults | `httprequestdefaults` | 设置 HTTP 请求的默认参数 |
| Cookie Manager | `cookiemanager` | 管理 HTTP Cookie |
| User Defined Variables | `userdefinedvariables` | 定义用户自定义变量 |

---

## 断言 (Assertions)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| Response Assertion | `responseassertion` | 验证服务器响应是否符合预期标准 |
| JSON Path Assertion | `jsonpathassertion` | 使用 JSON Path 表达式验证 JSON 响应 |
| XPath Assertion | `xpathassertion` | 使用 XPath 表达式验证 XML 响应 |
| XML Assertion | `xmlassertion` | 验证响应是否为有效 XML |
| BeanShell Assertion | `beanshellassertion` | 使用 BeanShell 脚本编写自定义断言 |

---

## 后置处理器 (Post Processors)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| Regular Expression Extractor | `regexextractor` | 使用正则表达式从响应中提取数据 |
| JSON Post Processor | `jsonpostprocessor` | 使用 JSON Path 从 JSON 响应中提取数据 |
| JSR223 Post Processor | `jsr223postprocessor` | 使用 JSR223 脚本处理响应数据 |
| BeanShell Post Processor | `beanshellpostprocessor` | 使用 BeanShell 脚本处理响应数据 |
| Debug Post Processor | `debugpostprocessor` | 调试用途，打印响应数据 |
| HTML Extractor | `htmlextractor` | 从 HTML 响应中提取数据 |

---

## 前置处理器 (Pre Processors)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| JSR223 Pre Processor | `jsr223preprocessor` | 使用 JSR223 脚本预处理请求 |
| BeanShell Pre Processor | `beanshellpreprocessor` | 使用 BeanShell 脚本预处理请求 |
| User Parameters | `userparameters` | 为不同用户定义不同的参数值 |

---

## 线程组 (Thread Groups)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| Thread Group | `threadgroup` | 标准线程组，定义虚拟用户行为 |
| setUp Thread Group | `setuptimegroup` | 在主测试前执行的特殊线程组 |
| tearDown Thread Group | `teardowntimegroup` | 在主测试后执行的特殊线程组 |
| Open Model Thread Group | `openmodelthreadgroup` | 吞吐量控制的线程组变体 |

---

## 定时器 (Timers)

| 组件名称 | 类型标识 | 说明 |
|----------|----------|------|
| Constant Timer | `constanttimer` | 固定延迟，在每个采样器之前暂停指定时间 |
| Constant Throughput Timer | `constantthroughputtimer` | 恒定吞吐量控制，限制每分钟采样器执行次数 |
| Precise Throughput Timer | `precisethroughputtimer` | 精确吞吐量控制，提供更精确的吞吐量限制 |
| Uniform Random Timer | `uniformrandomtimer` | 均匀随机延迟，在指定范围内均匀分布随机延迟 |

---

## 待提交组件

以下组件的 Schema 定义已在本地创建，但尚未提交到代码仓库（共 17 个）：

### 断言 (6 个)
- Duration Assertion - 验证响应时间
- Size Assertion - 验证响应大小
- Compare Assertion - 比较两个样本
- HTML Assertion - 验证 HTML 响应
- MD5Hex Assertion - 验证响应的 MD5 哈希值
- JSR223 Assertion - JSR223 脚本断言

### 定时器 (2 个)
- Gaussian Random Timer - 高斯随机延迟
- Poisson Random Timer - 泊松随机延迟

### 监听器 (5 个)
- View Results Tree - 查看结果树
- Aggregate Report - 聚合报告
- Summary Report - 汇总报告
- Backend Listener - 后端监听器
- Summariser - 摘要报告

### 后置处理器 (3 个)
- XPath Extractor - XPath 提取器
- Boundary Extractor - 边界提取器
- JMESPath Extractor - JMESPath 提取器

### 配置元件 (1 个)
- CSV Data Set - CSV 数据文件参数化

---

## Schema 文件位置

所有组件的 Schema 定义文件位于项目目录下：

```
src/main/jmeter-agent/skills/jmeter/references/
├── assertions/          # 断言组件
├── configuration/       # 配置元件
├── controllers/         # 逻辑控制器
├── listeners/           # 监听器
├── post-processors/    # 后置处理器
├── pre-processors/     # 前置处理器
├── samplers/           # 采样器
├── thread-group/       # 线程组
└── timers/             # 定时器
```

每个组件对应一个 `{ComponentName}.schema.yaml` 文件，定义了：
- 组件类型和名称
- 组件描述
- 属性列表（名称、类型、是否必填、默认值、枚举值、范围、正则等）

构建时，这些文件会被复制到 `{JMETER_HOME}/bin/jmeter-agent/skills/jmeter/references/` 目录。
