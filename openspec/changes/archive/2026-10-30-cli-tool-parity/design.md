# Design

## Context

现状三块事实塑造本设计（动机见 proposal.md - Why）：

1. **闸门在服务端**：CLI 的 `tool` 命令是裸透传，真正的可达性闸门是 `IpcServer.ALLOWED_TOOLS`（显式 `Set.of`，deny-by-default）。CLI 侧加动词不放开白名单则 400，放开白名单不加动词则只能 `tool` 透传。
2. **IPC 侧 registry 与 AgentLoop registry 是两个实例**：`IpcServer.registry()` 用 `JMeterToolRegistry.registerDefaultTools(r)` 单参构造（sessionManager=null）。4 个目标工具均已在该注册表注册（batch 三兄弟无条件、`validate_test_plan` 无条件），goal 工具不在——因此白名单加名即可直调，无需动 registry 构造。`validate_test_plan` 在 null 通道下 `recordStaticEvidence` 旁路（既有 null-bypass 语义），正是 IPC 直调想要的行为。
3. **CLI 是 thin client**：`JmeterCli` 零 JMeter 类依赖（仅 `ipc.protocol.*`、`InstanceRegistry`、Jackson、JDK HttpClient）。所有动词 = 参数 Map 组装 + `execTool` 统一投递；参数构建器模式已有先例（`buildRunParameters` 为 package-private static 供单测）。
4. **6 个白名单内工具仅 `tool` 透传**：`copy_paste_jmeter_element`、`get_test_plan_tree`、`get_script_info`、`query_element_properties`、`parse_jmx_file`、`get_log_panel_content` 在白名单内但无专属动词——能力可达但帮助体系不可发现，属可发现性缺口而非可达性缺口。

规划期参数审计结论：既有 13 个动词（变更前口径）的参数键与工具 schema 全部匹配，无实现缺口；`run_test` 的 `mode` 参数此前未暴露（本变更补 `--mode`）；唯一脆弱点是 `run --wait` 以 `"**State**: Running"` 字面子串判定运行态（client 侧硬编码，不能引用工具类常量——那会拖入 JMeter 类）。

## Goals / Non-Goals

**Goals:**

- 白名单 + CLI 动词两层一次到位，使 4 个工具既有 `tool` 透传又有专属动词
- 参数审计结果固化为回归基线（审计矩阵 + 守卫测试），防未来漂移
- thin-client 边界与输出字面耦合点显式化并有测试钉定

**Non-Goals:**（proposal 非目标之外的设计层边界）

- 不引入 CLI 侧参数 schema 反射/自动化机制——每个动词手写 Map 组装，与现状一致（工具少、映射直白，自动化反而引入 schema 耦合）
- 不为 `--wait` 类轮询逻辑做通用化抽象

## Decisions

### D1：动词命名用带连字符的独立动词，不复用/扩展现有动词

`batch-delete` / `batch-move` / `batch-toggle` / `validate` 各自独立，不复用现有 `delete --elementIds`、也不把 `batch` 动词升级成多模式分派。

- 理由：`batch` 动词（batch_update）语义已被占用（`--elementIds + --properties`）；给 `delete` 加复数参数会改变既有动词的参数面（隐式超载）；独立动词与工具名 1:1 对应，帮助与心智模型最直。
- 备选否决：`delete --elementIds 1,2,3`（隐式分派到 batch 工具，破坏「动词↔工具一一映射」spec 要求）；仅 `tool` 透传不加动词（用户体验差，且 CLI 帮助体系不可发现）。
- 范围扩大后的补名规则（同 D1 精神）：读操作取短名（`tree`/`query`/`parse`/`log`/`script-info`），写操作取动作名（`copy`）；`parse --file` 对齐 `open --file` 的既有旗标名。**不设**「白名单全工具必有专属动词」的强不变式——`tool` 透传是兜底入口，未来白名单演进不强制动词同步（避免闸门与命令面锁死耦合）。

### D2：参数构建器沿用 `buildRunParameters` 模式，package-private static 可测

每个新动词的参数组装提为包内可见 static 方法（`buildBatchMoveParameters` 等），`cmdXxx` 只做 require + 构建器 + `execTool`。单测直接断言 Map 形状，不起 HTTP。

- 理由：与 `buildRunParameters`（JmeterCliTest 已覆盖）同一模式，零新抽象；`require` 抛 `IllegalArgumentException` → 退出码 2 的既有链路自然复用，spec 的 usage 错误场景无需新代码。

### D3：白名单一致性用测试守卫，不做运行期校验

`ALLOWED_TOOLS` 改为 package-private（沿用 `applyCliProvenance` 包内可见以便单测的先例），新增测试：`ALLOWED_TOOLS` 每个条目存在于 `JMeterToolRegistry.registerDefaultTools(new ToolRegistry())` 注册名集合，且 fs/web/exec 族恒不在内。

- 理由：运行期启动校验是多余的防御（错名只会在首次调用时 400，测试已能挡住合入）；测试守卫零运行时成本，且顺带钉住「deny-by-default 不因重构漂移」。
- 备选否决：启动时 log warn 校验（多余的代码路径）；不改可见性而用反射读私有字段（脆弱，违背项目对反射测试的态度——见 `thinking-style-map-reflective-test` 教训）。

### D4：`run --wait` 运行态标记保持字面常量 + 测试钉定，不建共享常量

`"**State**: Running"` 保留为 CLI 内常量并加注释指明来源（`get_test_status` 输出格式），配一条单测钉定常量值本身（防手滑改动），跨 jar 的格式变更靠手工测试用例覆盖。

- 理由：共享常量要求 CLI 引用工具类 → 违反 thin-client 边界（工具类 import JMeter 类）。字面耦合在两个部署面（CLI 与插件同 jar 分发但类图隔离）下是成本最低的正确解。
- 备选否决：状态判定改走结构化 JSON（`--json` 模式下 `get_test_status` 的 IpcResponse content 仍是 markdown 文本，无结构化状态字段可用——需要服务端新字段，超出本次范围）。

### D5：拒绝文案随白名单类别实况更新，不列举工具名

`"allowlist: CRUD + read-only query tools only"` → 按当前实况改为 "allowlist: element CRUD + read-only query + test execution + jmx load/parse/log tools"。

- 理由：列举具体工具名会让文案与 `Set.of` 双源维护、必然再漂移；类别描述粒度与白名单注释一致即可。

### D6：测试分层——单测钉参数面，手工用例钉端到端

- 单测（mvn test 可跑，无 GUI）：4 个新动词参数构建（含必填缺失、可选缺省、CSV 解析）+ 白名单守卫 + `run --wait` 常量钉定。
- 手工集成（真实 GUI 实例）：`docs/test/jmeter-cli-test-cases.md` 增补 4 命令用例并对 live 实例全量回归（含 `--json`、错误路径、批量边界 50 上限、position 语义）。批量树操作是 EDT 真树变更，纯单测无法覆盖，维持项目「GUI 路径手工验收」惯例。
- 备选否决：GUI 自动化集成测试（引入重型脚手架，收益不成比例）。

### D7：`run --mode` 直传，不做客户端组合校验

`--mode normal|smoke` 直传工具 `mode` 参数。组合语义（mode 仅随 start、`ignore_timers` 在 smoke 下不适用）以工具端校验为准：工具对 `ignore_timers`+`smoke` 组合**不拒绝**（smoke 恒跳过定时器，旗标被忽略），CLI 帮助文案如实说明该行为而不复制校验逻辑。smoke 是阻塞式执行（完成即返回判定），`--wait` 对 smoke 冗余但无害（状态轮询首轮即判完成）。

- 理由：客户端复制工具端校验 = 双源维护，工具端已是唯一事实源；组合误用由工具错误信息教学。
- 备选否决：客户端预校验 `--mode` 枚举与 `--ignoreTimers` 冲突（双源漂移风险，与 D5 同理）。

## Risks / Trade-offs

- [白名单扩容扩大远端可达面] → 4 工具均无凭据泄露面（内存树操作/校验）；与 Agent 同信任边界；fs/web/exec 封禁不动，守卫测试钉住。
- [`validate_test_plan` 直调与 goal 证据的语义分叉] → 既有 null-bypass 注释已声明；本设计在 spec 中显式写明「直调不记 goal 证据」，防止未来被当 bug「修复」。
- [CLI 帮助文案与工具 schema 双源漂移] → 审计矩阵 + 手工用例清单作为回归基线；D5 已消除一处已知漂移。残余风险接受（工具参数变更频率低）。
- [`--elementIds` CSV 与工具 `maxItems: 50` 上限] → CLI 不预校验条数（避免双源复制上限值），超限时由工具侧返回明确错误，CLI 原样透传——与 `find --limit` 的现状处理一致。
- [smoke 经 IPC 直调不计 goal 冒烟预算] → registry 单参构造的 null 通道旁路（与 `validate_test_plan` 证据旁路同语义）；spec/design 显式写明，防未来被当 bug「修复」。

## Migration Plan

纯增量、无数据/协议迁移。部署 = 重新构建 + `mvn install` 覆盖 jar（注意 lib/ext 旧版本残留清理，见项目已知陷阱）。回滚 = 还原 jar 即可，无状态残留。

## Open Questions

（无——规划期审计已完成，无会改变 spec/方法/任务拆分的未决项。）
