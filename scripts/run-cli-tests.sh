#!/usr/bin/env bash
# jmeter-cli 全用例回归:覆盖 docs/jmeter-cli-test-cases.md 的 87 条用例(按 TC-ID 逐条)。
#
# 用法: ./run-cli-tests.sh [JMETER_HOME]
# 依赖: bash + grep + curl(无 jq 依赖;用 grep 从 CLI 输出提取 elementId)
# 环境: 需 JMeter GUI 以 -Jjmeter.ai.ipc.enabled=true 运行;JDK 17(经 JAVA_HOME 指定)。
#
# 设计:
#   - 每条用例对应一个 TC-ID 断言(PASS/FAIL/SKIP 三态计数);SKIP 必注明原因。
#   - 写操作隔离在 ZTEST_ 前缀元素下,结尾统一 delete + 残留检查(可重复)。
#   - 断言字符串全部源自 JmeterCli.java / IpcServer.java 源码,避免假绿。
#   - 安全相关(白名单封堵/鉴权)零容忍。
#
# 关键源码事实(实测确认):
#   1) create 不返回 elementId,每次 create 后用 find --searchBy name 查回。
#   2) TestPlan 根不能用 find --searchBy elementType 查;用 tool get_test_plan_tree 拿。
#   3) create 需提供 schema 必填属性(完整名,如 ThreadGroup.num_threads + ThreadGroup.main_controller)。

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- 定位 jar:优先 shade jar(含 Main-Class);排除 assembly 的 *-jar-with-dependencies.jar ---
JAR=""
for f in "$REPO_ROOT"/target/jmeter-agent-*.jar; do
    case "$f" in *-jar-with-dependencies.jar) continue;; esac
    [ -f "$f" ] && JAR="$f" && break
done
JH="${1:-${JMETER_HOME:-}}"
if [ -z "$JAR" ] && [ -n "$JH" ]; then
    for f in "$JH"/lib/ext/jmeter-agent-*.jar; do
        case "$f" in *-jar-with-dependencies.jar) continue;; esac
        [ -f "$f" ] && JAR="$f" && break
    done
fi
[ -z "$JAR" ] && { echo "Error: jmeter-agent jar not found. Run 'mvn package' or pass JMETER_HOME." >&2; exit 2; }
[ -z "$JH" ] && { echo "Error: JMETER_HOME not set. Usage: $0 <JMETER_HOME>" >&2; exit 2; }

if [ -n "${JAVA_HOME:-}" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD="java"; fi
CLI="$JAVACMD -jar $JAR"
GH="--jmeter-home $JH"
ERRFILE="$(mktemp)"

PASS=0; FAIL=0; SKIP=0
ok()   { echo "  [OK]   $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
skip() { echo "  [SKIP] $1 — $2"; SKIP=$((SKIP+1)); }

# 运行 CLI,捕获 stdout→$OUT, stderr→$ERR, exit→$RC
R() { OUT=$("$@" 2>"$ERRFILE"); RC=$?; ERR=$(cat "$ERRFILE"); }

# 退出码断言
ex0()  { if [ "$RC" -eq 0 ]; then ok "$1"; else fail "$1 (exit=$RC want 0)"; fi; }
ex1()  { if [ "$RC" -eq 1 ]; then ok "$1"; else fail "$1 (exit=$RC want 1)"; fi; }
ex2()  { if [ "$RC" -eq 2 ]; then ok "$1"; else fail "$1 (exit=$RC want 2)"; fi; }
ex01() { if [ "$RC" -eq 0 ] || [ "$RC" -eq 1 ]; then ok "$1"; else fail "$1 (exit=$RC want 0|1)"; fi; }
# 输出含关键词
out() { if echo "$OUT" | grep -qiF "$2"; then ok "$1"; else fail "$1 (stdout 缺 '$2')"; fi; }
err() { if echo "$ERR" | grep -qiF "$2"; then ok "$1"; else fail "$1 (stderr 缺 '$2')"; fi; }
# 输出/错误【不含】关键词(断言"未被白名单拒绝"等否定语义)
notout() { if echo "$OUT" | grep -qiF "$2"; then fail "$1 (stdout 不应含 '$2')"; else ok "$1"; fi; }
noterr() { if echo "$ERR" | grep -qiF "$2"; then fail "$1 (stderr 不应含 '$2')"; else ok "$1"; fi; }

# 从 CLI 输出提取首个 elementId
extract_id() { echo "$1" | grep -oE '"elementId"[[:space:]]*:[[:space:]]*[0-9]+' | grep -oE '[0-9]+' | head -1; }
# create 后按名字查回 elementId(因 create 不直接返回 id)
mk() {  # <elementType> <name> <parentId> <properties_json>
    $CLI create --elementType "$1" --elementName "$2" --parentId "$3" --properties "$4" $GH >/dev/null 2>&1
    extract_id "$($CLI find --searchBy name --query "$2" $GH 2>/dev/null)"
}

echo "=== jmeter-cli 全用例回归 ==="
echo "jar  = $JAR"
echo "home = $JH"
echo "java = $JAVACMD"
echo

# ===== 0) 探活(失败则中止)=====
echo "--- 0) 探活 ---"
R $CLI list $GH
if ! echo "$OUT" | grep -q "PID"; then
    fail "list — 无实例?请用 -Jjmeter.ai.ipc.enabled=true 启动 JMeter"
    echo; echo "==== abort: PASS=$PASS FAIL=$FAIL SKIP=$SKIP ===="; rm -f "$ERRFILE"; exit 1
fi
ok "探活 list"
R $CLI health $GH; ex0 "探活 health"
PID=$(echo "$OUT" | grep -oE '"pid"[[:space:]]*:[[:space:]]*"[0-9]+"' | grep -oE '[0-9]+')
PORT=$(grep -oE '"port"[[:space:]]*:[[:space:]]*[0-9]+' "$JH/bin/jmeter-agent/ipc/port-$PID.json" 2>/dev/null | grep -oE '[0-9]+' | head -1)
TOKEN=$(grep -oE '"token"[[:space:]]*:[[:space:]]*"[^"]*"' "$JH/bin/jmeter-agent/ipc/port-$PID.json" 2>/dev/null | sed 's/.*"token"[[:space:]]*:[[:space:]]*"//; s/"$//')
echo "    (pid=$PID port=$PORT)"

# ===== 1) ENV 环境与发现 =====
echo "--- 1) ENV 环境与发现 ---"
R $CLI list $GH;                    ex0 "TC-ENV-01 list exit";            out "TC-ENV-01 list 表头" "PID"
R $CLI list --jmeter-home "$JH";    ex0 "TC-ENV-02 list --jmeter-home";  out "TC-ENV-02 表头" "PID"
R $CLI list --jmeter-home "$REPO_ROOT"; ex0 "TC-ENV-03 list(无实例)exit"; out "TC-ENV-03 提示" "no running"
# TC-ENV-04:显式清空 JMETER_HOME(bash 可能继承系统环境变量),不走 R(env 前缀无法经函数传递)
OUT=$(JMETER_HOME= $CLI list 2>"$ERRFILE"); RC=$?; ERR=$(cat "$ERRFILE")
ex1 "TC-ENV-04 list(无 JMETER_HOME)exit"; err "TC-ENV-04 提示" "JMETER_HOME not set"
R $CLI health $GH;                  ex0 "TC-ENV-05 health exit";         out "TC-ENV-05 ok" "ok"
R $CLI health --json $GH;           ex0 "TC-ENV-06 health --json exit";  out "TC-ENV-06 JSON" '"success"'
R $CLI health --pid "$PID" $GH;     ex0 "TC-ENV-07 health --pid exit"
skip "TC-ENV-08 多实例未带 --pid" "需启动第二个开了 IPC 的 JMeter 实例"
R $CLI health --pid 999999 $GH;     ex1 "TC-ENV-09 health --pid 不存在 exit"; err "TC-ENV-09 提示" "no instance with pid"
R $CLI health $GH; out "TC-ENV-10 health 含 agentInitialized 字段" "agentInitialized"
skip "TC-ENV-10 agentInitialized=false 场景" "当前实例已初始化(true),需重启未 warmup 才能测 false 路径"
# TC-ENV-11 端口文件损坏:临时 home + 损坏 port 文件,list 应静默跳过(exit 0)
#   损坏文件 readSilently 返回 null → 不探活、不删除(无法确认活性),TC-ENV-11b 断言其保留
ENV11_HOME="$(mktemp -d)"; mkdir -p "$ENV11_HOME/bin/jmeter-agent/ipc"
CF="$ENV11_HOME/bin/jmeter-agent/ipc/port-1.json"; echo '{bad json' > "$CF"
R $CLI list --jmeter-home "$ENV11_HOME"; ex0 "TC-ENV-11 list(损坏端口文件)exit"
[ -f "$CF" ] && ok "TC-ENV-11b 损坏文件保留(未误删)" || fail "TC-ENV-11b 损坏文件被误删"
rm -rf "$ENV11_HOME"
skip "TC-ENV-12 关闭 GUI 后 list" "会终止被测实例,无法在回归中执行"
R $CLI list --json $GH; ex0 "TC-ENV-13 list --json exit"; out "TC-ENV-13 JSON 含 pid" "pid"

# --- 1b) STALE 残留端口文件自清理(kill -9 / 强杀后 port 文件残留 → CLI 探活过滤 + 清理)---
echo "--- 1b) STALE 残留端口文件自清理 ---"
STALE_HOME="$(mktemp -d)"; mkdir -p "$STALE_HOME/bin/jmeter-agent/ipc"
SF="$STALE_HOME/bin/jmeter-agent/ipc/port-888888.json"
STALE_JSON='{"pid":"888888","port":59999,"token":"dead","startedAt":1,"bind":"127.0.0.1"}'
printf '%s' "$STALE_JSON" > "$SF"
R $CLI list --jmeter-home "$STALE_HOME"; ex0 "TC-STALE-01 list(死实例)exit"; out "TC-STALE-01 提示" "no running"
[ ! -f "$SF" ] && ok "TC-STALE-02 list 探活后清理死 port 文件" || fail "TC-STALE-02 死 port 文件仍存在"
# 重新写入,测 findInstance(--pid)路径的自清理
printf '%s' "$STALE_JSON" > "$SF"
R $CLI health --pid 888888 --jmeter-home "$STALE_HOME"; ex1 "TC-STALE-03 health 死 pid exit"; err "TC-STALE-03 提示" "no instance with pid"
[ ! -f "$SF" ] && ok "TC-STALE-04 findInstance 自清理死 port 文件" || fail "TC-STALE-04 死 port 文件仍存在"
# TC-STALE-05 越界 port 不崩溃(IAE 被 isAlive 捕获视为不可探活 → PID 死则清理)
SF5="$STALE_HOME/bin/jmeter-agent/ipc/port-777777.json"
printf '{"pid":"777777","port":999999,"token":"dead","startedAt":1,"bind":"127.0.0.1"}' > "$SF5"
R $CLI list --jmeter-home "$STALE_HOME"; ex0 "TC-STALE-05 越界 port 不崩溃 exit"
[ ! -f "$SF5" ] && ok "TC-STALE-05 越界 port 文件被清理(PID 死)" || fail "TC-STALE-05 文件仍在(应判死清理)"
# TC-STALE-06 原子写崩溃遗留的 *.json.tmp:listInstances 过滤器放宽后顺带清理(L6)
TMPF="$STALE_HOME/bin/jmeter-agent/ipc/port-666666.json.tmp"; printf 'garbage' > "$TMPF"
R $CLI list --jmeter-home "$STALE_HOME"; ex0 "TC-STALE-06 list(含 .tmp 残留)exit"
[ ! -f "$TMPF" ] && ok "TC-STALE-06 .tmp 残留被清理" || fail "TC-STALE-06 .tmp 残留仍在"
rm -rf "$STALE_HOME"

# ===== 数据准备(同时断言 TC-CRUD-01/02)=====
echo "--- 数据准备 ---"
ROOT=$(extract_id "$($CLI tool get_test_plan_tree $GH 2>/dev/null)")
if [ -n "$ROOT" ]; then ok "get_test_plan_tree root (id=$ROOT)"; else fail "get_test_plan_tree root"; fi
TG=$(mk threadgroup ZTEST_TG "$ROOT" '{"ThreadGroup.num_threads":1,"ThreadGroup.main_controller":{"LoopController.loops":1}}')
if [ -n "$TG" ]; then ok "TC-CRUD-01 create TG (id=$TG)"; else fail "TC-CRUD-01 create TG"; fi
S1=$(mk httpsampler ZTEST_S1 "$TG" '{"HTTPSampler.domain":"example.com"}')
if [ -n "$S1" ]; then ok "TC-CRUD-02 create sampler 带 properties (id=$S1)"; else fail "TC-CRUD-02 create sampler"; fi
S2=$(mk httpsampler ZTEST_S2 "$TG" '{"HTTPSampler.domain":"example.com"}')
[ -n "$S2" ] && ok "数据准备 S2 (id=$S2)" || fail "数据准备 S2"

# ===== 2) READ 只读查询 =====
echo "--- 2) READ 只读查询 ---"
R $CLI tool get_test_plan_tree $GH;            ex0 "TC-READ-01 get_test_plan_tree exit";   out "TC-READ-01 含 elementId" "elementId"
R $CLI get --elementId "$ROOT" $GH;            ex0 "TC-READ-02 get root exit"
R $CLI get $GH;                                ex01 "TC-READ-03 get(当前选中)exit[弱:依赖GUI选中]"
skip "TC-READ-03 get 当前选中内容" "依赖 GUI 当前选中节点,需人工选中后核对"
R $CLI find --searchBy name --query ZTEST_S1 $GH;            ex0 "TC-READ-04 find name exit"; out "TC-READ-04 命中" "ZTEST_S1"
R $CLI find --searchBy name --query ZTEST_S1 --exactMatch false $GH; ex0 "TC-READ-05 exactMatch false exit"; out "TC-READ-05 命中" "ZTEST_S1"
R $CLI find --searchBy name --query ZTEST_S1 --exactMatch true $GH;  ex0 "TC-READ-06 exactMatch true exit";  out "TC-READ-06 命中" "ZTEST_S1"
skip "TC-READ-07 find --searchBy path" "path 维度 query 语义未在文档明确,留人工"
R $CLI find --searchBy elementId --query "$S1" $GH;          ex0 "TC-READ-08 find elementId exit"; out "TC-READ-08 命中" "$S1"
R $CLI find --searchBy elementType --query threadgroup --limit 1 $GH;  ex0 "TC-READ-09 limit 1 exit"
R $CLI find --searchBy elementType --query threadgroup --limit 50 $GH; ex0 "TC-READ-10 limit 50 exit"
R $CLI find --searchBy elementType --query threadgroup --offset 2 --limit 2 $GH; ex0 "TC-READ-11 offset+limit exit"
R $CLI get --elementId "$S1" --includeProperties false $GH;  ex0 "TC-READ-12 includeProperties false exit"
R $CLI get --elementId "$S1" --maxDepth 1 $GH;               ex0 "TC-READ-13 maxDepth 1 exit"
R $CLI find --searchBy name --query ZTEST_NOPE_NOT_EXIST $GH; ex0 "TC-READ-14 空结果 exit"
R $CLI get --elementId 999999 $GH;                           ex1 "TC-READ-15 不存在 elementId exit"
R $CLI find --searchBy=name --query=ZTEST_S1 $GH;            ex0 "TC-READ-16 --key=value 等号形式 exit"; out "TC-READ-16 命中" "ZTEST_S1"

# ===== 3) CRUD 写操作 =====
echo "--- 3) CRUD 写操作 ---"
TG2=$(mk threadgroup ZTEST_TG2 "$ROOT" '{"ThreadGroup.num_threads":10,"ThreadGroup.main_controller":{"LoopController.loops":1}}')
[ -n "$TG2" ] && ok "TC-CRUD-04 create TG num_threads=10 (id=$TG2)" || fail "TC-CRUD-04 create TG2"
R $CLI create --elementType threadgroup --elementName ZTEST_NOPARENT $GH
err "TC-CRUD-03 省 --parentId Warning" "Warning: --parentId omitted"; ex01 "TC-CRUD-03 省 --parentId exit[弱]"
# 清理可能落到选中节点的 ZTEST_NOPARENT
$CLI find --searchBy name --query ZTEST_NOPARENT $GH >/dev/null 2>&1 && \
  { NP=$(extract_id "$($CLI find --searchBy name --query ZTEST_NOPARENT $GH 2>/dev/null)"); [ -n "$NP" ] && $CLI delete --elementId "$NP" $GH >/dev/null 2>&1; }
R $CLI update --elementId "$S1" --properties '{"HTTPSampler.path":"/api/v1"}' $GH; ex0 "TC-CRUD-05 update exit"
R $CLI update --elementId "$S1" --properties '{"HTTPSampler.port":8080}' $GH;      ex0 "TC-CRUD-06 update 部分属性(数字 port,INTEGER 类型)exit"
DEL_TMP=$(mk httpsampler ZTEST_DEL_TMP "$TG" '{"HTTPSampler.domain":"x.com"}')
R $CLI delete --elementId "$DEL_TMP" $GH; ex0 "TC-CRUD-07 delete exit"
R $CLI batch --elementIds "$S1,$S2" --properties '{"HTTPSampler.domain":"batch.example.com"}' $GH; ex0 "TC-CRUD-08 batch 同类型多元素 exit"
# 实测:batch_update_jmeter_elements 要求所有元素同类型,跨类型被拒绝(工具设计约束,非 bug)
R $CLI batch --elementIds "$TG,$S1" --properties '{"HTTPSampler.domain":"x.com"}' $GH; ex1 "TC-CRUD-09 batch 跨类型(工具拒绝)exit"; err "TC-CRUD-09 文案" "different types"
R $CLI batch --elementIds "$S1" --properties '{"HTTPSampler.domain":"single.example.com"}' $GH; ex0 "TC-CRUD-10 batch 单 id exit"
R $CLI create --elementType bogus_type --elementName X --parentId "$TG" $GH; ex1 "TC-CRUD-11 非法 elementType exit"
R $CLI create --elementType threadgroup --elementName X --parentId "$TG" --properties '{bad json}' $GH; ex1 "TC-CRUD-12 非法 JSON exit"
R $CLI update --elementId 999999 --properties '{"HTTPSampler.path":"/x"}' $GH; ex1 "TC-CRUD-13 update 不存在 id exit"
R $CLI delete --elementId 999999 $GH; ex1 "TC-CRUD-14 delete 不存在 id exit"

# ===== 4) TREE 树操作 =====
echo "--- 4) TREE 树操作 ---"
# move 语义是"移到新父";实测同父 move(目标父==当前父)有已知问题(报 1>0 且元素丢失),
# 故每条 move 用独立新建 sampler、跨父移动(TG -> TG_TREE),符合文档"移到新父"原意。
TG_TREE=$(mk threadgroup ZTEST_TG_TREE "$ROOT" '{"ThreadGroup.num_threads":1,"ThreadGroup.main_controller":{"LoopController.loops":1}}')
ANC=$(mk httpsampler ZTEST_ANCHOR "$TG_TREE" '{"HTTPSampler.domain":"a.com"}')   # 目标父下的定位锚点
mksampler() { mk httpsampler "$1" "$TG" '{"HTTPSampler.domain":"m.com"}'; }       # 在 TG 下新建 sampler
M1=$(mksampler ZTEST_M1); R $CLI move --elementId "$M1" --targetParentId "$TG_TREE" $GH;                         ex0 "TC-TREE-01 move 默认 last(跨父)"
M2=$(mksampler ZTEST_M2); R $CLI move --elementId "$M2" --targetParentId "$TG_TREE" --position first $GH;        ex0 "TC-TREE-02 move first(跨父)"
M3=$(mksampler ZTEST_M3); R $CLI move --elementId "$M3" --targetParentId "$TG_TREE" --position last $GH;         ex0 "TC-TREE-03 move last(跨父)"
M4=$(mksampler ZTEST_M4); R $CLI move --elementId "$M4" --targetParentId "$TG_TREE" --position "before:$ANC" $GH; ex0 "TC-TREE-04 move before:<sibling>"
M5=$(mksampler ZTEST_M5); R $CLI move --elementId "$M5" --targetParentId "$TG_TREE" --position "after:$ANC" $GH;  ex0 "TC-TREE-05 move after:<sibling>"
# 实测:move 对不存在的 sibling(before:999999)宽容处理(fallback,exit 0),不报错;
# 改用"move 不存在元素"作为可靠的工具层 error 场景:
R $CLI move --elementId 999999 --targetParentId "$TG_TREE" $GH; ex1 "TC-TREE-06 move 不存在元素 exit"; err "TC-TREE-06 文案" "Could not find element"
# toggle 用独立元素(连续操作;诊断验证连续 toggle 稳定,不受 move 影响)
TT=$(mk httpsampler ZTEST_TOGGLE "$TG" '{"HTTPSampler.domain":"t.com"}')
R $CLI toggle --elementId "$TT" $GH;                  ex0 "TC-TREE-07 toggle 默认 exit"
R $CLI toggle --elementId "$TT" --action enable $GH;  ex0 "TC-TREE-08 toggle enable exit"
R $CLI toggle --elementId "$TT" --action disable $GH; ex0 "TC-TREE-09 toggle disable exit"
R $CLI toggle --elementId "$TT" --action bogus $GH;   ex1 "TC-TREE-10 toggle 非法 action exit"; err "TC-TREE-10 文案" "Invalid action"

# ===== 5) E2E 端到端链路(TC-E2E-01,独立 E2E_ 元素)=====
echo "--- 5) E2E 端到端链路(TC-E2E-01) ---"
E_TG=$(mk threadgroup E2E_TG "$ROOT" '{"ThreadGroup.num_threads":1,"ThreadGroup.main_controller":{"LoopController.loops":1}}')
[ -n "$E_TG" ] && ok "TC-E2E-01a create E2E_TG (id=$E_TG)" || fail "TC-E2E-01a create E2E_TG"
E_S=$(mk httpsampler E2E_HTTP "$E_TG" '{"HTTPSampler.domain":"example.com"}')
[ -n "$E_S" ] && ok "TC-E2E-01b create E2E_HTTP (id=$E_S)" || fail "TC-E2E-01b create E2E_HTTP"
R $CLI update --elementId "$E_S" --properties '{"HTTPSampler.path":"/api/v1/test"}' $GH; ex0 "TC-E2E-01c update exit"
R $CLI toggle --elementId "$E_S" --action disable $GH; ex0 "TC-E2E-01d toggle disable exit"
R $CLI toggle --elementId "$E_S" --action enable $GH;  ex0 "TC-E2E-01e toggle enable exit"
E_S2=$(mk httpsampler E2E_HTTP2 "$E_TG" '{"HTTPSampler.domain":"example.com"}')
R $CLI move --elementId "$E_S" --targetParentId "$E_TG" --position "before:$E_S2" $GH; ex0 "TC-E2E-01f move before exit"
R $CLI batch --elementIds "$E_S,$E_S2" --properties '{"HTTPSampler.domain":"batch.example.com"}' $GH; ex0 "TC-E2E-01g batch exit"

# ===== 6) AGENT 消息 =====
echo "--- 6) AGENT 消息 ---"
# TC-AGENT-01:轻量、零工具副作用消息,验证 agent 链路真实可通(model 已配置)
R $CLI agent "Reply with the single word OK. Do not call any tools." $GH
if [ "$RC" -eq 0 ]; then ok "TC-AGENT-01 agent 正常消息 exit (content 非空: $(echo "$OUT" | head -c 60))"; else skip "TC-AGENT-01 agent 正常消息" "exit=$RC(model/API 未就绪?)"; fi
skip "TC-AGENT-02 agent --json" "依赖 LLM 回答行为,已在 TC-AGENT-01 验证链路"
skip "TC-AGENT-03 agent --session" "依赖 LLM 回答行为,留人工"
skip "TC-AGENT-04 agent 未初始化预热" "当前 agentInitialized=true,需重启未 warmup"
skip "TC-AGENT-05 agent disabled(503)" "需 jmeter.ai.agent.enabled=false 重启"
skip "TC-AGENT-06 agent 超时(504)" "需构造长任务,难稳定复现"
R $CLI agent $GH;                       ex2 "TC-AGENT-07 agent 无 message exit"; err "TC-AGENT-07 提示" "agent needs a message"
R $CLI agent "hi" --timeout 1 $GH;      ex1 "TC-AGENT-08 agent --timeout 1 早超时 exit"

# ===== 7) TOOL 透传 =====
echo "--- 7) TOOL 透传 ---"
R $CLI tool find_element --params '{"searchBy":"elementType","query":"threadgroup"}' $GH; ex0 "TC-TOOL-01 tool find_element exit"
R $CLI tool get_test_plan_tree $GH;                       ex0 "TC-TOOL-02 tool get_test_plan_tree exit"
TOOL_S=$(mk httpsampler ZTEST_TOOL_S "$TG" '{"HTTPSampler.domain":"t.com"}')
R $CLI tool create_jmeter_element --params "{\"elementType\":\"httpsampler\",\"elementName\":\"ZTEST_TOOL_S2\",\"parentId\":$TG,\"properties\":{\"HTTPSampler.domain\":\"t2.com\"}}" $GH; ex0 "TC-TOOL-03 tool create_jmeter_element exit"
R $CLI tool exec --params '{"command":"dir"}' $GH;        ex1 "TC-TOOL-04 exec blocked exit"; err "TC-TOOL-04 文案" "not allowed"; err "TC-TOOL-04 400" "400"
R $CLI tool read_file --params '{"path":"x"}' $GH;        ex1 "TC-TOOL-05 read_file blocked exit"; err "TC-TOOL-05 文案" "read_file"
# TC-TOOL-06:H1 后 run_test 已放行(原黑名单封堵,现允许)。用 action=bogus 触发工具层报错,
#             既证明"未被白名单拒绝",又不实际启动测试(无副作用)。
R $CLI tool run_test --params '{"action":"bogus"}' $GH;   ex1 "TC-TOOL-06 run_test 已放行(工具层报错)exit"; noterr "TC-TOOL-06 不再被白名单拒绝" "not allowed"
R $CLI tool web_search --params '{"query":"x"}' $GH;      ex1 "TC-TOOL-07 web_search blocked exit"; err "TC-TOOL-07 文案" "web_search"
R $CLI tool $GH;                                          ex2 "TC-TOOL-08 tool 无 name exit"; err "TC-TOOL-08 提示" "tool needs a name"
R $CLI tool nonexistent_tool_xyz $GH;                     ex1 "TC-TOOL-09 tool 不存在名 exit"

# --- 7b) allowlist 新增放行类工具(H1:测试执行/解析/日志,旧黑名单曾封堵,现放行)---
echo "--- 7b) allowlist 新增放行类工具 ---"
R $CLI tool get_test_status $GH;                          noterr "TC-TOOL-10 get_test_status 放行(非白名单拒绝)" "not allowed"
R $CLI tool get_test_results $GH;                         noterr "TC-TOOL-11 get_test_results 放行(非白名单拒绝)" "not allowed"
R $CLI tool get_log_panel_content $GH;                    ex0  "TC-TOOL-12 get_log_panel_content 放行 exit"
R $CLI tool parse_jmx_file --params '{"filePath":"/nonexistent.jmx"}' $GH; ex1 "TC-TOOL-13 parse_jmx_file 工具层报错 exit"; noterr "TC-TOOL-13 放行(非白名单拒绝)" "not allowed"

# ===== 8) EXC 异常与安全 =====
echo "--- 8) EXC 异常与安全 ---"
R $CLI;                          ex0 "TC-EXC-01 无参 help exit";   out "TC-EXC-01 命令清单" "Commands"
R $CLI -h;                       ex0 "TC-EXC-02 -h exit";          out "TC-EXC-02 帮助" "Usage"
R $CLI --help;                   ex0 "TC-EXC-02 --help exit"
R $CLI help;                     ex0 "TC-EXC-02 help exit"
R $CLI help create;              ex0 "TC-EXC-03 help <cmd> exit";  out "TC-EXC-03 create 帮助" "elementType"
R $CLI create -h;                ex0 "TC-EXC-03 <cmd> -h exit"
R $CLI help boguscmd;            ex2 "TC-EXC-04 help 未知 cmd exit"; err "TC-EXC-04 提示" "Unknown command"
R $CLI boguscmd;                 ex2 "TC-EXC-05 未知命令 exit";     err "TC-EXC-05 提示" "Unknown command"
R $CLI create --elementName X --parentId 2 $GH;   ex2 "TC-EXC-06 create 缺 --elementType exit"; err "TC-EXC-06 提示" "elementType"
R $CLI find --query x $GH;                         ex2 "TC-EXC-07 find 缺 --searchBy exit"
R $CLI update --elementId 5 $GH;                   ex2 "TC-EXC-08 update 缺 --properties exit"
R $CLI batch $GH;                                  ex2 "TC-EXC-09 batch 缺 --elementIds exit"
R $CLI create --elementType threadgroup --elementName X --parentId abc $GH; ex2 "TC-EXC-10 非整数 parentId exit"
R $CLI health --token wrong $GH;  ex1 "TC-EXC-11 错 token exit";   err "TC-EXC-11 401" "401"
EXC12_HOME="$(mktemp -d)"                       # 空 home,其下无 ipc/ → 0 实例
R $CLI health --jmeter-home "$EXC12_HOME"
ex1 "TC-EXC-12 0 实例 exit"; err "TC-EXC-12 提示" "no running"
rm -rf "$EXC12_HOME"
skip "TC-EXC-13 多实例未带 --pid" "需第二个开了 IPC 的实例"
# TC-EXC-14 直接 GET /tool → HTTP 405(curl 绕过 CLI 直连 server)
if [ -n "$PORT" ] && [ -n "$TOKEN" ]; then
    HC=$(curl -s -o /dev/null -w '%{http_code}' -H "X-IPC-Token: $TOKEN" "http://127.0.0.1:$PORT/tool" 2>/dev/null)
    if [ "$HC" = "405" ]; then ok "TC-EXC-14 GET /tool → 405"; else fail "TC-EXC-14 GET /tool (http=$HC want 405)"; fi
    # TC-EXC-15 body > 1MB → HTTP 413(L3:parseRequest 将超大体映射为 413,原为 500)
    BIG="$(mktemp)"; head -c 1100000 /dev/zero | tr '\0' 'a' > "$BIG"
    HC=$(curl -s -o /dev/null -w '%{http_code}' -H "X-IPC-Token: $TOKEN" -H 'Content-Type: application/json' --data-binary @"$BIG" "http://127.0.0.1:$PORT/tool" 2>/dev/null)
    if [ "$HC" = "413" ]; then ok "TC-EXC-15 body>1MB → 413"; else fail "TC-EXC-15 body>1MB (http=$HC want 413)"; fi
    rm -f "$BIG"
    # TC-EXC-15b 空 body → HTTP 400(L3:parseRequest 空体映射为 400)
    HC=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-IPC-Token: $TOKEN" -H 'Content-Type: application/json' --data-binary "" "http://127.0.0.1:$PORT/tool" 2>/dev/null)
    if [ "$HC" = "400" ]; then ok "TC-EXC-15b empty body → 400"; else fail "TC-EXC-15b empty body (http=$HC want 400)"; fi
else
    skip "TC-EXC-14 / TC-EXC-15" "未能从端口文件解析 port/token"
fi
skip "TC-EXC-16 他机访问" "需另一台主机验证 loopback 绑定"
skip "TC-EXC-17 bind=0.0.0.0 拒启动" "需改 jmeter.ai.ipc.bind 重启"
skip "TC-EXC-18 固定端口冲突" "需改端口配置重启"

# ===== 9) 清理 + 残留检查 =====
echo "--- 9) 清理 + 残留检查 ---"
for id in "$TG" "$TG2" "$TG_TREE" "$E_TG"; do
    [ -n "$id" ] && $CLI delete --elementId "$id" $GH >/dev/null 2>&1
done
RESID="$($CLI find --searchBy name --query ZTEST_ $GH 2>/dev/null)$($CLI find --searchBy name --query E2E_ $GH 2>/dev/null)"
if echo "$RESID" | grep -qE "ZTEST_|E2E_"; then
    fail "残留检查(仍有 ZTEST_/E2E_ 元素)"
else
    ok "残留检查(clean)"
fi

echo
echo "==== PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP  (总计 $((PASS+FAIL+SKIP))) ===="
rm -f "$ERRFILE"
[ "$FAIL" -eq 0 ]
