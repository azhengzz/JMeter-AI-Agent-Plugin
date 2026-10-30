# Tasks

## 1. IPC 白名单扩容（IpcServer）

- [x] 1.1 `ALLOWED_TOOLS` 新增 `batch_delete_jmeter_elements` / `batch_move_jmeter_elements` / `batch_toggle_jmeter_elements` / `validate_test_plan`（分入既有类别注释组：batch 三兄弟进 CRUD 组、validate 进新「静态校验」组），可见性改 package-private 以便测试；验证：1.3 守卫测试绿
- [x] 1.2 更新白名单拒绝文案为类别实况（"element CRUD + read-only query + static validation + test execution + jmx load/parse/log" 粒度，见 design D5，不列举工具名）；验证：对 live 实例 `jmeter-cli tool exec` 收到新文案
- [x] 1.3 新增白名单守卫测试（如 `IpcServerAllowedToolsTest`：`ALLOWED_TOOLS ⊆ registerDefaultTools(new ToolRegistry())` 注册名、4 个新工具在内、`read_file`/`web_search`/`exec` 恒不在内）；验证：`mvn test -Dtest=IpcServerAllowedToolsTest` 绿

## 2. CLI 动词（JmeterCli）

- [x] 2.1 实现 `batch-delete` / `batch-move` / `batch-toggle` / `validate`：dispatch case + `cmdXxx` + package-private static `buildXxxParameters` 构建器（`--elementIds` 复用 `parseIntList`；可选参数缺省不投递，见 design D2）；验证：2.5 单测绿
- [x] 2.2 实现 `copy` / `tree` / `script-info` / `query` / `parse` / `log` 六个透传转动词（同 D2 构建器模式；`parse --file` 对齐 `open --file`；`log` 三参全可选缺省尾读）；验证：2.5 单测绿
- [x] 2.3 `run` 增加 `--mode normal|smoke` 直传（design D7：不做客户端组合校验，帮助文案说明 smoke 恒跳定时器）；验证：2.5 单测绿
- [x] 2.4 10 个新动词的 SPECS 帮助条目 + `run` 帮助补 `--mode`（文案默认值与工具 schema 一致）；验证：`jmeter-cli help` 列出 10 命令、各 `-h` 与 schema 一致
- [x] 2.5 扩展 `JmeterCliTest`：batch-move 全参 Map 形状、缺 `--targetParentId` 抛 usage 异常、可选缺省不投递（batch-toggle 无 action）、batch-delete CSV 解析、copy 缺省 targetParentId 不投递、run --mode 投递、validate 空参数、帮助打印冒烟（global + 10 个新命令不抛异常）；验证：`mvn test -Dtest=JmeterCliTest` 绿

## 3. 审计基线与钉定

- [x] 3.1 将规划期参数审计矩阵（变更后 23 个动词 × 工具 × 参数映射，含 camelCase 直传族与 snake_case 转换族）落盘为 `documents/jmeter-cli.md` 的映射附录表；验证：文档表覆盖全部动词且键名与工具 schema 一致
- [x] 3.2 `run --wait` 的 `"**State**: Running"` 提取为命名常量 + 来源注释，加单测钉定常量值（design D4，不做跨类共享常量）；验证：`mvn test -Dtest=JmeterCliTest` 绿
- [x] 3.3 `documents/jmeter-cli.md` 收录 10 个新命令 + `run --mode`（用法 + 示例）；验证：文档示例命令可照抄执行

## 4. 手工集成验证与收尾

- [x] 4.1 `docs/test/jmeter-cli-test-cases.md` 增补用例：10 个新动词（含 `--json`、错误路径、copy 原位复制、log 尾读/行区间、query 过滤、parse 不加载进 GUI、position 语义、`--elementIds` 超 50 上限）+ `run --mode smoke`（判定输出、与 `--wait` 组合、`--ignoreTimers` 组合行为）+ `validate` PASS 与 FAIL 计划两态；验证：用例文档覆盖 spec 全部场景
- [x] 4.2 `mvn clean install` 后对 live GUI 实例全量执行手工用例（新旧命令一起回归，注意 lib/ext 旧 jar 清理）；验证：用例全绿、`jmeter.log` 无新增异常，结果记录回写用例文档
- [x] 4.3 `openspec validate "cli-tool-parity"` 通过；验证：命令退出码 0
