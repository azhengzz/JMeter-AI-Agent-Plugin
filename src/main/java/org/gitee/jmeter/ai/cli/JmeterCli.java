package org.gitee.jmeter.ai.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.gitee.jmeter.ai.ipc.protocol.IpcRequest;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI 客户端:发现并驱动运行中的 JMeter GUI 实例。
 
 * <p>Thin client -- 只依赖 {@code ipc.protocol.*}、{@link InstanceRegistry}、Jackson 和 JDK
 * {@link HttpClient},**不引用任何 JMeter/GUI 类**。所有改动经 loopback HTTP 转发到插件内的
 * {@code IpcServer},由后者在 EDT 上操作测试计划树或推消息给 Agent。
 *
 * <p>用法示例:
 * <pre>
 *   jmeter-cli list
 *   jmeter-cli health
 *   jmeter-cli find --searchBy elementType --query testplan
 *   jmeter-cli create --elementType threadgroup --elementName TG1 --parentId &lt;id&gt; [--properties "{\\"ThreadGroup.num_threads\\":10}"]
 *   jmeter-cli update --elementId &lt;id&gt; --properties "{\\"HTTPSampler.domain\\":\\"newdomain.com\\"}"
 *   jmeter-cli delete --elementId &lt;id&gt;
 *   jmeter-cli move  --elementId &lt;id&gt; --targetParentId &lt;parentId&gt; [--position first|last]
 *   jmeter-cli toggle --elementId &lt;id&gt; [--action enable|disable|toggle]
 *   jmeter-cli get --elementId &lt;id&gt;
 *   jmeter-cli batch --elementIds 1,2,3 --properties "{\\"HTTPSampler.domain\\":\\"batch.example.com\\"}"
 *   jmeter-cli agent "再加一个 5 用户的线程组"
 *   jmeter-cli tool &lt;tool_name&gt; --params "{\\"searchBy\\":\\"elementType\\",\\"query\\":\\"threadgroup\\"}"
 *   全局:--pid &lt;pid&gt; --token &lt;t&gt; --json --jmeter-home &lt;dir&gt;
 * </pre>
 */
public final class JmeterCli {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private JmeterCli() {
    }

    public static void main(String[] args) {
        // CLI 进程:slf4j 2.x 在 shade jar 内无 2.x provider,首次初始化会向 stderr 打
        // "No SLF4J providers were found" 等告警。执行前先吞掉这次初始化告警,再恢复 stderr。
        PrintStream realErr = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            InstanceRegistry.currentPid(); // 触发 InstanceRegistry 静态 logger → slf4j 初始化
        } catch (Throwable ignore) {
            // best-effort
        } finally {
            System.setErr(realErr);
        }
        // 同时抑制 log4j2 StatusLogger 告警(若有)
        System.setProperty("log4j2.StatusLogger.level", "OFF");
        try {
            if (args.length == 0) {
                printGlobalHelp(System.out);
                System.exit(0);
            }
            String first = args[0];
            if (isHelpFlag(first) || "help".equals(first)) {
                // `jmeter-cli -h | --help | help [command]`
                if (args.length >= 2 && !isHelpFlag(args[1]) && !args[1].startsWith("--")) {
                    if (printCommandHelp(System.out, args[1])) {
                        System.exit(0);
                    }
                    System.err.println("Unknown command: " + args[1]);
                    System.exit(2);
                }
                printGlobalHelp(System.out);
                System.exit(0);
            }
            Parsed p = parse(args);
            if (p.help) {
                // `jmeter-cli <command> -h | --help`
                if (printCommandHelp(System.out, p.command)) {
                    System.exit(0);
                }
                System.err.println("Unknown command: " + p.command);
                System.exit(2);
            }
            System.exit(dispatch(p));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error: " + friendly(e));
            System.exit(1);
        }
    }

    /**
     * 把传输层异常(连接拒绝/超时,getMessage() 常为 null→"Error: null")转成可读提示。
     */
    private static String friendly(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof ConnectException || root instanceof HttpTimeoutException) {
            // ConnectException: 端口无监听(进程死/残留文件);HttpTimeoutException: 连接或读取超时
            return "cannot reach JMeter IPC server in time (stale port file? process killed? "
                    + "agent still running? verify with 'jmeter-cli list').";
        }
        String m = root.getMessage();
        return (m != null && !m.isEmpty()) ? m : root.getClass().getSimpleName();
    }

    // ---------- dispatch ----------

    private static int dispatch(Parsed p) throws Exception {
        switch (p.command) {
            case "list":
                return cmdList(p.opts, p.json);
            case "health":
                return cmdHealth(p.opts, p.json);
            case "agent":
                return cmdAgent(p, p.json);
            case "tool":
                return cmdTool(p, p.json);
            case "create":
                return cmdCreate(p.opts, p.json);
            case "update":
                return cmdUpdate(p.opts, p.json);
            case "delete":
                return cmdSimple(p.opts, p.json, "delete_jmeter_element", "elementId");
            case "toggle":
                return cmdToggle(p.opts, p.json);
            case "move":
                return cmdMove(p.opts, p.json);
            case "get":
                return cmdGet(p.opts, p.json);
            case "find":
                return cmdFind(p.opts, p.json);
            case "batch":
                return cmdBatch(p.opts, p.json);
            default:
                System.err.println("Unknown command: " + p.command);
                System.err.println("Run 'jmeter-cli help' to list available commands.");
                return 2;
        }
    }

    // ---------- commands ----------

    private static int cmdList(Map<String, String> opts, boolean json) throws Exception {
        File ipcDir = ipcDirOf(opts);
        if (ipcDir == null) {
            return 1;
        }
        List<InstanceInfo> all = InstanceRegistry.listInstances(ipcDir);
        if (json) {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(all));
            return 0;
        }
        if (all.isEmpty()) {
            System.out.println("(no running JMeter AI instance in " + ipcDir + ")");
            return 0;
        }
        System.out.printf("%-10s %-7s %-12s %s%n", "PID", "PORT", "BIND", "STARTED(UTC)");
        for (InstanceInfo i : all) {
            System.out.printf("%-10s %-7d %-12s %s%n",
                    i.getPid(), i.getPort(), str(i.getBind(), "127.0.0.1"),
                    Instant.ofEpochMilli(i.getStartedAt()));
        }
        return 0;
    }

    private static int cmdHealth(Map<String, String> opts, boolean json) throws Exception {
        InstanceInfo inst = resolveInstance(opts);
        if (inst == null) {
            return 1;
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host(inst) + ":" + inst.getPort() + "/health"))
                .timeout(Duration.ofSeconds(15))
                .header("X-IPC-Token", tokenFor(inst, opts))
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return printResp(resp, json);
    }

    private static int cmdAgent(Parsed p, boolean json) throws Exception {
        String message = p.positionals.isEmpty() ? p.opts.get("message") : p.positionals.get(0);
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("agent needs a message: agent \"<text>\"");
        }
        IpcRequest req = new IpcRequest();
        req.setOp("agent");
        req.setMessage(message);
        if (p.opts.get("session") != null) {
            req.setSession(p.opts.get("session"));
        }
        return sendToTool(p.opts, "/agent", req, json);
    }

    private static int cmdTool(Parsed p, boolean json) throws Exception {
        String name = p.positionals.isEmpty() ? p.opts.get("name") : p.positionals.get(0);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("tool needs a name: tool <name> --params \"<json>\"");
        }
        Map<String, Object> params = p.opts.get("params") != null
                ? parseJsonMap(p.opts.get("params")) : new HashMap<>();
        IpcRequest req = new IpcRequest();
        req.setOp("tool");
        req.setTool(name);
        req.setParams(params);
        return sendToTool(p.opts, "/tool", req, json);
    }

    private static int cmdCreate(Map<String, String> opts, boolean json) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("elementType", require(opts, "elementType"));
        params.put("elementName", require(opts, "elementName"));
        if (opts.get("parentId") != null) {
            params.put("parentId", Integer.parseInt(opts.get("parentId")));
        } else {
            System.err.println("Warning: --parentId omitted; will use GUI's current selection "
                    + "(non-deterministic). Pass --parentId <elementId> for determinism.");
        }
        if (opts.get("properties") != null) {
            params.put("properties", parseJsonMap(opts.get("properties")));
        }
        return execTool(opts, json, "create_jmeter_element", params);
    }

    private static int cmdUpdate(Map<String, String> opts, boolean json) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", Integer.parseInt(require(opts, "elementId")));
        params.put("properties", parseJsonMap(require(opts, "properties")));
        return execTool(opts, json, "update_jmeter_element", params);
    }

    private static int cmdSimple(Map<String, String> opts, boolean json, String tool, String idKey) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", Integer.parseInt(require(opts, idKey)));
        return execTool(opts, json, tool, params);
    }

    private static int cmdToggle(Map<String, String> opts, boolean json) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", Integer.parseInt(require(opts, "elementId")));
        if (opts.get("action") != null) {
            params.put("action", opts.get("action"));
        }
        return execTool(opts, json, "toggle_jmeter_element", params);
    }

    private static int cmdMove(Map<String, String> opts, boolean json) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("elementId", Integer.parseInt(require(opts, "elementId")));
        params.put("targetParentId", Integer.parseInt(require(opts, "targetParentId")));
        if (opts.get("position") != null) {
            params.put("position", opts.get("position"));
        }
        return execTool(opts, json, "move_jmeter_element", params);
    }

    private static int cmdGet(Map<String, String> opts, boolean json) throws Exception {
        Map<String, Object> params = new HashMap<>();
        if (opts.get("elementId") != null) {
            // find_element by elementId (shares find_element's optional params)
            params.put("searchBy", "elementId");
            params.put("query", opts.get("elementId"));
            putBoolIfPresent(opts, params, "includeProperties");
            putIntIfPresent(opts, params, "maxDepth");
            return execTool(opts, json, "find_element", params);
        }
        // get_selected_element
        putBoolIfPresent(opts, params, "includeProperties");
        putIntIfPresent(opts, params, "maxDepth");
        return execTool(opts, json, "get_selected_element", params);
    }

    private static int cmdFind(Map<String, String> opts, boolean json) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("searchBy", require(opts, "searchBy"));
        params.put("query", require(opts, "query"));
        putBoolIfPresent(opts, params, "exactMatch");
        putBoolIfPresent(opts, params, "includeProperties");
        putIntIfPresent(opts, params, "maxDepth");
        putIntIfPresent(opts, params, "offset");
        putIntIfPresent(opts, params, "limit");
        return execTool(opts, json, "find_element", params);
    }

    private static int cmdBatch(Map<String, String> opts, boolean json) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("elementIds", parseIntList(require(opts, "elementIds")));
        params.put("properties", parseJsonMap(require(opts, "properties")));
        return execTool(opts, json, "batch_update_jmeter_elements", params);
    }

    /** Passes an optional boolean opt through to the tool params under the same key. */
    private static void putBoolIfPresent(Map<String, String> opts, Map<String, Object> params, String key) {
        if (opts.get(key) != null) {
            params.put(key, Boolean.parseBoolean(opts.get(key)));
        }
    }

    /** Passes an optional integer opt through to the tool params under the same key. */
    private static void putIntIfPresent(Map<String, String> opts, Map<String, Object> params, String key) {
        if (opts.get(key) != null) {
            params.put(key, Integer.parseInt(opts.get(key)));
        }
    }

    private static int execTool(Map<String, String> opts, boolean json, String tool, Map<String, Object> params)
            throws Exception {
        IpcRequest req = new IpcRequest();
        req.setOp("tool");
        req.setTool(tool);
        req.setParams(params);
        return sendToTool(opts, "/tool", req, json);
    }

    // ---------- transport ----------

    private static int sendToTool(Map<String, String> opts, String endpoint, IpcRequest req, boolean json)
            throws Exception {
        InstanceInfo inst = resolveInstance(opts);
        if (inst == null) {
            return 1;
        }
        long timeout = opts.get("timeout") != null
                ? Long.parseLong(opts.get("timeout")) : 130_000L;
        String body = MAPPER.writeValueAsString(req);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host(inst) + ":" + inst.getPort() + endpoint))
                .timeout(Duration.ofMillis(timeout))
                .header("Content-Type", "application/json")
                .header("X-IPC-Token", tokenFor(inst, opts))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());
        return printResp(resp, json);
    }

    private static int printResp(HttpResponse<String> resp, boolean json) throws Exception {
        int status = resp.statusCode();
        IpcResponse ipc;
        try {
            ipc = MAPPER.readValue(resp.body(), IpcResponse.class);
        } catch (Exception e) {
            System.err.println("HTTP " + status + " (unparseable body): " + resp.body());
            return 1;
        }
        if (json) {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ipc));
            return ipc.isSuccess() ? 0 : 1;
        }
        if (ipc.isSuccess()) {
            String c = ipc.getContent();
            if (c != null && !c.isEmpty()) {
                System.out.println(c);
            }
            return 0;
        }
        String err = ipc.getError() != null ? ipc.getError() : ipc.getErrorMessage();
        System.err.println("Error" + (status >= 400 ? " (HTTP " + status + ")" : "") + ": " + err);
        return 1;
    }

    // ---------- instance discovery ----------

    private static InstanceInfo resolveInstance(Map<String, String> opts) {
        File ipcDir = ipcDirOf(opts);
        if (ipcDir == null) {
            return null;
        }
        String pid = opts.get("pid");
        if (pid != null) {
            InstanceInfo info = InstanceRegistry.findInstance(ipcDir, pid);
            if (info == null) {
                System.err.println("Error: no instance with pid " + pid + " in " + ipcDir);
                printInstances(InstanceRegistry.listInstances(ipcDir));
            }
            return info;
        }
        // 只探活一次:findSingleInstance 内部也会 listInstances,直接复用本列表避免双重探活。
        List<InstanceInfo> all = InstanceRegistry.listInstances(ipcDir);
        if (all.size() == 1) {
            return all.get(0);
        }
        if (all.isEmpty()) {
            System.err.println("Error: no running JMeter AI instance in " + ipcDir
                    + ". Start JMeter GUI with -Jjmeter.ai.ipc.enabled=true.");
        } else {
            System.err.println("Error: found " + all.size() + " instances, use --pid to select:");
            printInstances(all);
        }
        return null;
    }

    private static File ipcDirOf(Map<String, String> opts) {
        String home = opts.getOrDefault("jmeter-home", System.getenv("JMETER_HOME"));
        if (home == null || home.isEmpty()) {
            System.err.println("Error: JMETER_HOME not set. Use --jmeter-home <dir> or set JMETER_HOME env.");
            return null;
        }
        return InstanceRegistry.ipcDir(new File(home));
    }

    private static void printInstances(List<InstanceInfo> instances) {
        for (InstanceInfo i : instances) {
            System.err.println("  pid=" + i.getPid() + " port=" + i.getPort()
                    + " bind=" + str(i.getBind(), "127.0.0.1"));
        }
    }

    private static String host(InstanceInfo inst) {
        return str(inst.getBind(), "127.0.0.1");
    }

    private static String tokenFor(InstanceInfo inst, Map<String, String> opts) {
        String t = opts.get("token");
        return (t != null && !t.isEmpty()) ? t : inst.getToken();
    }

    // ---------- arg parsing & helpers ----------

    private static Parsed parse(String[] args) {
        Parsed p = new Parsed();
        p.command = args[0];
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (isHelpFlag(a)) {
                p.help = true;
            } else if ("--json".equals(a)) {
                p.json = true;
            } else if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                if (eq > 2) {
                    // --key=value 形式:值可任意(含以 -- 开头的值),避免被当成下一个 flag
                    p.opts.put(a.substring(2, eq), a.substring(eq + 1));
                } else {
                    String key = a.substring(2);
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        p.opts.put(key, args[++i]);
                    } else {
                        p.opts.put(key, "");
                    }
                }
            } else {
                p.positionals.add(a);
            }
        }
        return p;
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required option --" + key);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMap(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    private static List<Integer> parseIntList(String csv) {
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(Integer.parseInt(s.trim()));
        }
        return list;
    }

    private static String str(String s, String dflt) {
        return (s == null || s.isEmpty()) ? dflt : s;
    }

    // ---------- help ----------

    /** Metadata for one command; drives both the global help list and per-command help. */
    private record Cmd(String name, String summary, String usage,
                       String description, String[][] opts, String[] examples) {
    }

    private static final Cmd[] SPECS = {
        new Cmd("list",
            "List running JMeter AI GUI instances.",
            "jmeter-cli list [--jmeter-home <dir>]",
            """
            Enumerates the IPC port files under <JMETER_HOME>/bin/jmeter-agent/ipc/ and TCP-probes each
            to drop stale (dead) entries left by abnormally-killed JMeter processes (those files are
            removed automatically). Prints one row per LIVE instance: PID, port, bind, start time (UTC).
            Use it to find the --pid to target when more than one instance is running.""",
            new String[][]{
                {"--jmeter-home <dir>", "JMETER_HOME override (default: $JMETER_HOME env)"}
            },
            new String[]{
                "jmeter-cli list"
            }),

        new Cmd("health",
            "Probe the IPC server and Agent readiness.",
            "jmeter-cli health [--pid <pid>] [--token <t>] [--jmeter-home <dir>] [--json]",
            """
            Calls GET /health on the target instance and reports whether the IPC server is up and whether
            the AI Agent is initialized. Unlike 'agent', it does NOT warm up the Agent - use it as a
            connectivity / readiness check.""",
            new String[][]{
                {"--json", "print the raw JSON response"},
                {"--pid <pid>", "target instance by PID"},
                {"--token <t>", "auth token override (default: read from port file)"},
                {"--jmeter-home <dir>", "JMETER_HOME override"}
            },
            new String[]{
                "jmeter-cli health",
                "jmeter-cli health --pid 12345 --json"
            }),

        new Cmd("get",
            "Get one element by elementId, or the GUI's current selection.",
            "jmeter-cli get [--elementId <id>] [--includeProperties <bool>] [--maxDepth <n>]",
            """
            With --elementId: fetches a single element (internally a find_element by elementId).
            Without --elementId: returns the element currently selected in the GUI tree (get_selected_element).
            --includeProperties / --maxDepth map 1:1 to the underlying tool params.""",
            new String[][]{
                {"--elementId <id>", "elementId to fetch; omit to use the GUI's current selection"},
                {"--includeProperties <bool>", "include element properties (default: true)"},
                {"--maxDepth <n>", "tree depth to traverse (-1 = unlimited)"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli get --elementId 5",
                "jmeter-cli get"
            }),

        new Cmd("find",
            "Find elements by name / elementType / path / elementId.",
            "jmeter-cli find --searchBy <c> --query <q> [--exactMatch <bool>] [--includeProperties <bool>] [--maxDepth <n>] [--offset <n>] [--limit <n>]",
            """
            Searches the test plan tree. --searchBy selects the match dimension; --query is the value to match.
            All flags map 1:1 to find_element params. Prints elementId(s) and a summary; use --json for the
            full payload.""",
            new String[][]{
                {"--searchBy <c>", "REQUIRED. name | elementType | path | elementId"},
                {"--query <q>", "REQUIRED. value to search for"},
                {"--exactMatch <bool>", "name search: exact vs partial (default: true)"},
                {"--includeProperties <bool>", "include element properties (default: true)"},
                {"--maxDepth <n>", "depth from found node (-1 unlimited, 0 node only; default: 0)"},
                {"--offset <n>", "results to skip, for pagination (default: 0)"},
                {"--limit <n>", "max results (default: 20, max: 50)"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli find --searchBy elementType --query testplan",
                "jmeter-cli find --searchBy name --query \"HTTP Request\" --limit 10"
            }),

        new Cmd("create",
            "Create a new test-plan element.",
            "jmeter-cli create --elementType <t> --elementName <n> [--parentId <id>] [--properties \"<json>\"]",
            """
            Creates a new element of the given type under --parentId and prints its new elementId. --elementType
            is the JMeter element type key (e.g. threadgroup, httpsampler, loopcontroller). --properties is a JSON
            object of element properties keyed by JMeter property names. If --parentId is omitted, the GUI's current
            selection is used (non-deterministic from the CLI) and a warning is printed - pass --parentId for
            determinism. Get the root id with: jmeter-cli find --searchBy elementType --query testplan""",
            new String[][]{
                {"--elementType <t>", "REQUIRED. element type key (threadgroup, httpsampler, ...)"},
                {"--elementName <n>", "REQUIRED. display name of the new element"},
                {"--parentId <id>", "parent elementId; omit = GUI selection (warns)"},
                {"--properties \"<json>\"", "JSON object of properties"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli create --elementType threadgroup --elementName TG1 --parentId 2",
                "jmeter-cli create --elementType httpsampler --elementName HTTP1 --parentId 5 --properties \"{\\\"HTTPSampler.domain\\\":\\\"example.com\\\"}\""
            }),

        new Cmd("update",
            "Update properties of an existing element.",
            "jmeter-cli update --elementId <id> --properties \"<json>\"",
            """
            Sets properties on an existing element. --properties is a JSON object keyed by JMeter property names.
            Only the listed properties are touched; others are left unchanged.""",
            new String[][]{
                {"--elementId <id>", "REQUIRED. elementId of the element to update"},
                {"--properties \"<json>\"", "REQUIRED. JSON object of properties to set"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli update --elementId 5 --properties \"{\\\"HTTPSampler.domain\\\":\\\"newdomain.com\\\"}\""
            }),

        new Cmd("delete",
            "Delete an element from the test plan.",
            "jmeter-cli delete --elementId <id>",
            "Removes the element with the given elementId from the test plan tree, along with all its descendants.",
            new String[][]{
                {"--elementId <id>", "REQUIRED. elementId of the element to delete"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli delete --elementId 5"
            }),

        new Cmd("move",
            "Move an element under a different parent.",
            "jmeter-cli move --elementId <id> --targetParentId <parentId> [--position first|last|before:<id>|after:<id>]",
            """
            Moves an element to a new parent and inserts it at the given position. --position defaults to 'last'.
            'before:<id>' / 'after:<id>' position relative to a sibling elementId that must already live under
            the target parent.""",
            new String[][]{
                {"--elementId <id>", "REQUIRED. elementId of the element to move"},
                {"--targetParentId <id>", "REQUIRED. elementId of the new parent"},
                {"--position <p>", "first | last (default) | before:<id> | after:<id>"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli move --elementId 5 --targetParentId 2",
                "jmeter-cli move --elementId 5 --targetParentId 2 --position first"
            }),

        new Cmd("toggle",
            "Enable / disable / toggle an element.",
            "jmeter-cli toggle --elementId <id> [--action enable|disable|toggle]",
            """
            Changes the enabled state of an element. --action defaults to 'toggle' (invert current state).
            'enable' / 'disable' force a state regardless of the current one.""",
            new String[][]{
                {"--elementId <id>", "REQUIRED. elementId of the element"},
                {"--action <a>", "enable | disable | toggle (default)"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli toggle --elementId 5",
                "jmeter-cli toggle --elementId 5 --action disable"
            }),

        new Cmd("batch",
            "Update several elements in one shot.",
            "jmeter-cli batch --elementIds 1,2,3 --properties \"<json>\"",
            """
            Applies the same property JSON to every elementId in --elementIds. Equivalent to running 'update'
            once per id, but in a single IPC round-trip.""",
            new String[][]{
                {"--elementIds <csv>", "REQUIRED. comma-separated elementIds, e.g. 1,2,3"},
                {"--properties \"<json>\"", "REQUIRED. JSON object of properties to apply to each"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli batch --elementIds 5,6,7 --properties \"{\\\"HTTPSampler.domain\\\":\\\"batch.example.com\\\"}\""
            }),

        new Cmd("agent",
            "Send a natural-language message to the AI Agent.",
            "jmeter-cli agent \"<message>\" [--session <key>] [--timeout <ms>]",
            """
            Forwards your message to the Agent Loop running in the GUI, sharing the same chat session
            (jmeter-ai-chat) and memory as the GUI chat panel - so the reply also appears in the GUI. Blocks
            until the agent turn completes or --timeout is reached. If the agent is not yet initialized it is
            warmed up with the configured default model. Reply text goes to stdout; toolsUsed / iterations show
            with --json.""",
            new String[][]{
                {"\"<message>\"", "message text (positional); --message <text> is an alias"},
                {"--session <key>", "session key (default: jmeter-ai-chat)"},
                {"--timeout <ms>", "wait timeout, in ms (default: 130000)"},
                {"--json, --pid, --token, --jmeter-home", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli agent \"add a thread group with 5 users named TG2\"",
                "jmeter-cli agent --message \"optimize the HTTP sampler\" --json"
            }),

        new Cmd("tool",
            "Raw passthrough - run any allowed tool by name.",
            "jmeter-cli tool <name> [--params \"<json>\"]",
            """
            Low-level escape hatch: builds an arbitrary tool request and POSTs it to /tool. The server enforces
            an allowlist - exec / filesystem / web / run_test tools are BLOCKED over IPC regardless of config.
            --params is passed verbatim as the tool's parameter map (see each tool's JSON schema for required
            fields). Use --json to inspect the full result/error structure.""",
            new String[][]{
                {"<name>", "tool name, e.g. find_element, get_test_plan_tree, create_jmeter_element"},
                {"--params \"<json>\"", "JSON object of tool parameters (default: {})"},
                {"--json, --pid, --token, --jmeter-home, --timeout", "global options (see jmeter-cli help)"}
            },
            new String[]{
                "jmeter-cli tool find_element --params \"{\\\"searchBy\\\":\\\"elementType\\\",\\\"query\\\":\\\"threadgroup\\\"}\"",
                "jmeter-cli tool get_test_plan_tree"
            })
    };

    private static boolean isHelpFlag(String a) {
        return "-h".equals(a) || "--help".equals(a);
    }

    private static Cmd findCmd(String name) {
        for (Cmd c : SPECS) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static void printGlobalHelp(PrintStream out) {
        int w = 0;
        for (Cmd c : SPECS) {
            w = Math.max(w, c.name().length());
        }
        out.println("JMeter AI CLI - drive a running JMeter GUI via IPC.");
        out.println();
        out.println("Usage:");
        out.println("  jmeter-cli <command> [options]");
        out.println("  jmeter-cli <command> -h | --help    (detailed help for a command)");
        out.println("  jmeter-cli help [command]");
        out.println();
        out.println("Talks to a running JMeter GUI over loopback HTTP. Start the GUI with");
        out.println("-Jjmeter.ai.ipc.enabled=true; the CLI discovers it via the port file at");
        out.println("<JMETER_HOME>/bin/jmeter-agent/ipc/port-<pid>.json.");
        out.println();
        out.println("Commands:");
        for (Cmd c : SPECS) {
            out.printf("  %-" + w + "s  %s%n", c.name(), c.summary());
        }
        out.println();
        out.println("Global options (apply to most commands):");
        out.println("  --pid <pid>        target instance by PID (needed when >1 instance runs)");
        out.println("  --token <t>        auth token override (default: read from port file)");
        out.println("  --json             print raw IpcResponse JSON instead of plain text");
        out.println("  --jmeter-home <d>  JMETER_HOME override (default: $JMETER_HOME env)");
        out.println("  --timeout <ms>     HTTP timeout, in ms (default: 130000)");
        out.println("  -h, --help         show this help, or a command's help");
        out.println();
        out.println("Quick start:");
        out.println("  jmeter-cli list");
        out.println("  jmeter-cli health");
        out.println("  jmeter-cli find --searchBy elementType --query testplan");
        out.println("  jmeter-cli create --elementType threadgroup --elementName TG1 --parentId <id>");
        out.println();
        out.println("Exit codes: 0 success  |  1 tool/agent/transport error  |  2 bad usage");
    }

    private static boolean printCommandHelp(PrintStream out, String name) {
        Cmd c = findCmd(name);
        if (c == null) {
            return false;
        }
        out.println("jmeter-cli " + c.name() + " - " + c.summary());
        out.println();
        out.println("Usage: " + c.usage());
        out.println();
        out.println(c.description());
        if (c.opts() != null && c.opts().length > 0) {
            out.println();
            out.println("Options:");
            int ow = 0;
            for (String[] o : c.opts()) {
                ow = Math.max(ow, o[0].length());
            }
            for (String[] o : c.opts()) {
                out.printf("  %-" + ow + "s  %s%n", o[0], o[1]);
            }
        }
        if (c.examples() != null && c.examples().length > 0) {
            out.println();
            out.println("Examples:");
            for (String e : c.examples()) {
                out.println("  " + e);
            }
        }
        return true;
    }

    private static class Parsed {
        String command;
        final Map<String, String> opts = new HashMap<>();
        final List<String> positionals = new ArrayList<>();
        boolean json;
        boolean help;
    }
}
