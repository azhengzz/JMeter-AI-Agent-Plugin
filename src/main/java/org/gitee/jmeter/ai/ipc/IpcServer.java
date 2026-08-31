package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.command.CommandRouter;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.tools.JMeterToolRegistry;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.protocol.IpcRequest;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.gitee.jmeter.ai.service.AiService;
import org.gitee.jmeter.ai.service.provider.AiServiceFactory;
import org.gitee.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌 IPC server:仅绑 loopback 的 HTTP server,把外部 CLI 请求转发到运行中的 JMeter GUI。
 *
 * <p>两条调用链都在 {@code ipc-worker} 线程(非 EDT)上发起:
 * <ul>
 *   <li>{@code POST /tool} —— 直接调 {@link ToolRegistry#execute} 执行白名单内工具,
 *       工具内部已自包 {@code EdtRunner}(invokeAndWait)上 EDT。<b>本 handler 绝不再包 EDT</b>,
 *       否则 EDT 上调 invokeAndWait 会抛 Error/死锁。</li>
 *   <li>{@code POST /agent} —— 调 {@link AgentLoop#processMessage} 推消息进 Agent,
 *       默认复用本实例 {@code instanceId} 会话(经 {@code InstanceContext}),与 GUI 聊天共享历史。</li>
 *   <li>{@code GET /health} —— 健康检查(需 token)。</li>
 * </ul>
 *
 * <p>安全:默认 {@code jmeter.ai.ipc.enabled=true};仅绑 127.0.0.1(拒绝通配地址);
 * 每端点校验 {@code X-IPC-Token}(常量时间比较);白名单排除 exec/fs/web/测试执行类工具。
 */
public final class IpcServer {
    private static final Logger log = LoggerFactory.getLogger(IpcServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** body 上限 1MB,防止恶意/错误请求 OOM。 */
    private static final int MAX_BODY_BYTES = 1 << 20;

    /**
     * 允许经 IPC 调用的工具(显式允许列表)。放 CRUD + 只读查询 + 测试执行 + 解析/日志类工具;
     * 任何未列于此的工具(含未来新增的)一律拒绝。
     * <p>exec/filesystem/web 工具本就默认不注册;即便被开启,也因不在本列表而被拒绝。
     * 测试执行类(run_test 等)与 Agent 同一信任边界(Agent 本就可调),无密钥泄露面。
     * <p>⚠ {@code parse_jmx_file} 与 {@code open_jmx_file} 均接受任意 filePath,可回读 .jmx 全部属性
     * (HeaderManager 鉴权头、JDBC 密码等);{@code open_jmx_file} 还会把脚本加载进 GUI(替换当前计划)。
     * 暂未做目录限制 —— TODO:让其校验 {@code agent.tools.filesystem.allowed.dirs} 与 {@code denied.dirs},
     * 复用 read_file 的文件系统沙箱(见 {@code TODO/TODO.md})。
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            // CRUD(写)
            "create_jmeter_element", "update_jmeter_element", "batch_update_jmeter_elements",
            "delete_jmeter_element", "move_jmeter_element", "copy_paste_jmeter_element",
            "toggle_jmeter_element",
            // 只读查询(作用于当前 GUI 测试计划树)
            "find_element", "get_selected_element", "get_test_plan_tree",
            "get_script_info", "query_element_properties",
            // 测试执行(与 Agent 同信任边界,无密钥泄露面)
            "run_test", "get_test_status", "get_test_results",
            // 加载/解析外部脚本 / 读取日志面板
            // ⚠ parse_jmx_file / open_jmx_file 待按 allowed.dirs 限制(见 TODO/TODO.md)
            "parse_jmx_file", "open_jmx_file", "get_log_panel_content"
    );

    private static final IpcServer INSTANCE = new IpcServer();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Object registryLock = new Object();
    private volatile HttpServer server;
    private volatile String expectedToken;
    private ToolRegistry toolRegistry;

    private IpcServer() {
    }

    public static IpcServer getInstance() {
        return INSTANCE;
    }

    public boolean isStarted() {
        return started.get();
    }

    public int getPort() {
        HttpServer s = server;
        return s != null ? s.getAddress().getPort() : -1;
    }

    /**
     * 启动 server(幂等)。{@code enabled=false} 直接返回;通配地址拒绝启动;
     * 固定端口冲突时<b>不静默回退</b>——记错并跳过,GUI 继续运行。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.info("IPC server already started");
            return;
        }
        if (!AiConfig.isIpcEnabled()) {
            log.info("IPC server disabled (jmeter.ai.ipc.enabled=false)");
            started.set(false);
            return;
        }

        String bind = AiConfig.getIpcBind();
        int configuredPort = AiConfig.getIpcPort();

        // 解析并断言 loopback:不再用字符串黑名单挡通配字面量,而是要求解析出的地址必须是
        // loopback。这样空值/null/任意通配写法(0.0.0.0/::)/意外主机名一律被 isLoopbackAddress() 拒绝。
        InetAddress bindAddr;
        try {
            bindAddr = InetAddress.getByName(bind);
        } catch (UnknownHostException e) {
            log.error("IPC bind '{}' could not be resolved. GUI continues without IPC.", bind);
            started.set(false);
            return;
        }
        if (!bindAddr.isLoopbackAddress()) {
            log.error("IPC bind '{}' ({}) is not loopback; refusing to start (loopback only). "
                    + "Set jmeter.ai.ipc.bind=127.0.0.1", bind, bindAddr.getHostAddress());
            started.set(false);
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(bindAddr, configuredPort), 0);
        } catch (IOException e) {
            log.error("IPC server failed to bind on {}:{} — {}. GUI continues without IPC. "
                    + "(Tip: leave jmeter.ai.ipc.port=0 for auto port.)",
                    bindAddr.getHostAddress(), configuredPort, e.getMessage());
            started.set(false);
            server = null;
            return;
        }

        String cfgToken = AiConfig.getIpcToken();
        expectedToken = (cfgToken == null || cfgToken.isEmpty()) ? randomToken() : cfgToken;

        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ipc-worker");
            t.setDaemon(true);
            return t;
        }));
        // 注意:com.sun.net.httpserver 按路径前缀路由,"/tool" 也会匹配 "/toolXYZ"。
        // 各 handler 靠 op/message 校验拒绝非法请求,/health 则需 token,故前缀越界不会泄露。
        server.createContext("/tool", this::handleTool);
        server.createContext("/agent", this::handleAgent);
        server.createContext("/health", this::handleHealth);
        server.start();

        int actualPort = server.getAddress().getPort();
        String pid = InstanceRegistry.currentPid();
        try {
            File ipcDir = InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()));
            InstanceRegistry.writeInstance(ipcDir, pid, actualPort, expectedToken, bind,
                    InstanceContext.instanceId(), "");
        } catch (Exception e) {
            log.error("IPC server bound on {}:{} but failed to write port file "
                    + "(CLI discovery won't work): {}", bind, actualPort, e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                File ipcDir = InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()));
                InstanceRegistry.deleteInstance(ipcDir, pid);
            } catch (Exception ignore) {
                // best-effort
            }
            HttpServer s = server;
            if (s != null) {
                try {
                    s.stop(2);
                } catch (Exception ignore) {
                    // best-effort
                }
            }
        }, "ipc-shutdown"));

        log.info("IPC server started on {}:{} (pid={}, token={}..., {}/{} tools available over IPC)",
                bind, actualPort, pid, expectedToken.substring(0, 8),
                ALLOWED_TOOLS.size(), registry().size());
    }

    private ToolRegistry registry() {
        synchronized (registryLock) {
            if (toolRegistry == null) {
                ToolRegistry r = new ToolRegistry();
                JMeterToolRegistry.registerDefaultTools(r);
                toolRegistry = r;
            }
            return toolRegistry;
        }
    }

    // ---------- handlers ----------

    private void handleTool(HttpExchange ex) throws IOException {
        if (!checkPost(ex)) {
            return;
        }
        if (!tokenOk(ex)) {
            sendError(ex, 401, "invalid or missing token");
            return;
        }
        try {
            IpcRequest req = parseRequest(ex);
            if (req == null) {
                return;
            }
            if (!"tool".equals(req.getOp())
                    || req.getTool() == null || req.getTool().isEmpty()) {
                sendError(ex, 400, "expect op=tool and non-empty 'tool'");
                return;
            }
            if (!ALLOWED_TOOLS.contains(req.getTool())) {
                sendError(ex, 400, "tool not allowed over IPC: " + req.getTool()
                        + " (allowlist: CRUD + read-only query tools only)");
                return;
            }
            Map<String, Object> params = req.getParams() == null ? Map.of() : req.getParams();
            long t0 = System.currentTimeMillis();
            ToolResult result = registry().execute(req.getTool(), params);
            send(ex, 200, fromToolResult(result, System.currentTimeMillis() - t0));
        } catch (Exception e) {
            log.error("IPC /tool error", e);
            sendError(ex, 500, "server error: " + rootMessage(e));
        }
    }

    private void handleAgent(HttpExchange ex) throws IOException {
        if (!checkPost(ex)) {
            return;
        }
        if (!tokenOk(ex)) {
            sendError(ex, 401, "invalid or missing token");
            return;
        }
        try {
            IpcRequest req = parseRequest(ex);
            if (req == null) {
                return;
            }
            if (!"agent".equals(req.getOp())
                    || req.getMessage() == null || req.getMessage().isEmpty()) {
                sendError(ex, 400, "expect op=agent and non-empty 'message'");
                return;
            }
            AgentLoop loop = resolveAgentLoop();
            if (loop == null) {
                sendError(ex, 503, "agent disabled (agent.enabled=false)");
                return;
            }
            String session = (req.getSession() == null || req.getSession().isEmpty())
                    ? InstanceContext.currentSessionKey() : req.getSession();
            long timeout = AiConfig.getIpcAgentTimeoutMs();
            long t0 = System.currentTimeMillis();
            // CLI 直连(delegated=false)的非命令消息加 [from cli] 前缀(随消息文本进回合上下文与
            // jsonl,单一事实源);斜杠命令豁免;委派载荷保持 [delegated-from …] 原样
            String message = applyCliProvenance(loop.getCommandRouter(), req.getMessage(), req.isDelegated());
            // 每回合挂文本累积器:取消/超时响应的 partialContent 来源(GUI 无关)。面板呈现
            // 不再经此转发——回合事件(STARTED/PROGRESS/终态)由 AgentLoop 统一发射,本
            // handler 只负责 wire:阻塞等待、超时取消、结构化响应
            TurnContentAccumulator accumulator = new TurnContentAccumulator();
            AgentLoop.ProgressCallback turnCallback = accumulator::onProgress;
            // delegated=true → 该回合内置 DelegationGuard,回合内 delegate_to_instance 直接报错(防 ping-pong)
            CompletableFuture<AgentResponse> future =
                    loop.processMessage(message, session, turnCallback,
                            req.isDelegated() ? TurnOrigin.IPC_DELEGATED : TurnOrigin.IPC_CLI);
            AgentResponse ar;
            try {
                ar = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // 超时即取消 in-flight turn:否则 agent 会继续在后台跑完并改测试计划树,
                // 而 CLI 已向操作者报了失败,产生"报错却已生效"的状态错位。
                loop.cancelActiveTask(session, CancelCause.TIMEOUT);
                // 取消窗口内自然完成的回合(get 超时→取消之间的 TOCTOU):signalCancel 对
                // 已完成 future no-op(cancelled=false,终态已按 TURN_COMPLETED 发出),
                // 按真实结果回 200——否则 CLI 收 504 "已取消" 而回合实际完整生效,
                // 恰是上注要防的状态错位。isCancelled 分流:真被取消(含中断)的回合仍走
                // 504;异常完成的按失败语义回 500。
                if (future.isDone() && !future.isCancelled()) {
                    try {
                        send(ex, 200, fromAgentResponse(future.getNow(null),
                                System.currentTimeMillis() - t0));
                    } catch (CompletionException completedErr) {
                        sendError(ex, 500, "agent failed: " + rootMessage(completedErr));
                    }
                    return;
                }
                send(ex, 504, cancelledResponse(
                        IpcResponse.CANCEL_REASON_TIMEOUT, accumulator,
                        "agent timeout after " + timeout + "ms (turn cancelled)"));
                return;
            } catch (ExecutionException ee) {
                // 回合任务自身抛异常(future.completeExceptionally):面板的失败收尾由
                // AgentLoop 的 TURN_COMPLETED(error) 事件承担,此处只管 wire 语义
                sendError(ex, 500, "agent failed: " + rootMessage(ee));
                return;
            } catch (java.util.concurrent.CancellationException ce) {
                // Stop 按钮/关闭对话框取消了该回合：409 + 结构化载荷让委派方/CLI 可读地处理
                // （重试/放弃、展示部分内容），而非落入外层 catch(Exception) 变成 500 "server error: null"
                send(ex, 409, cancelledResponse(
                        IpcResponse.CANCEL_REASON_USER_STOP, accumulator,
                        "turn cancelled before completion"));
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sendError(ex, 500, "interrupted");
                return;
            }
            send(ex, 200, fromAgentResponse(ar, System.currentTimeMillis() - t0));
        } catch (Exception e) {
            log.error("IPC /agent error", e);
            sendError(ex, 500, "server error: " + rootMessage(e));
        }
    }

    /** CLI 直连消息的来源前缀(与委派的 {@code [delegated-from …]} 同为消息文本一部分)。 */
    static final String CLI_PREFIX = "[from cli] ";

    /**
     * CLI 直连(delegated=false)的非命令消息加 {@code [from cli] } 来源前缀——随消息文本进
     * 会话存储与面板渲染(单一事实源)。斜杠命令豁免:命中
     * {@link CommandRouter#isPriority}/{@link CommandRouter#isDispatchable} 则原样投递,
     * 前缀会把 {@code jmeter-cli agent "/status"} 这类命令挡成普通消息发给 LLM(命令分发是
     * trim 后的精确匹配)。委派消息的 {@code [delegated-from …]} 前缀由发起侧添加,维持不动。
     * 包内可见以便单测。
     */
    static String applyCliProvenance(CommandRouter router, String message, boolean delegated) {
        if (delegated) {
            return message;
        }
        if (isCommandMessage(router, message)) {
            return message;
        }
        return CLI_PREFIX + message;
    }

    /**
     * 消息是否命中已知斜杠命令（trim 后 {@link CommandRouter#isPriority} 或
     * {@link CommandRouter#isDispatchable}）。前缀豁免与回合呈现豁免共用此判据——
     * 命令回合无面板显示契约，本地面板从不渲染命令的 "You:" 行。
     */
    static boolean isCommandMessage(CommandRouter router, String message) {
        String trimmed = message.trim();
        return router.isPriority(trimmed) || router.isDispatchable(trimmed);
    }

    /**
     * 构造 409/504 的结构化取消响应体：{@code cancelled=true} + {@code cancelReason}
     * （区分目标用户 STOP 与超时自取消）+ {@code partialContent}（累积器截断快照，
     * 无累积则省略字段）。提取为静态便于单测直接验证响应体形状。
     */
    static IpcResponse cancelledResponse(String cancelReason, TurnContentAccumulator accumulator,
            String error) {
        IpcResponse resp = new IpcResponse();
        resp.setSuccess(false);
        resp.setError(error);
        resp.setCancelled(true);
        resp.setCancelReason(cancelReason);
        String partial = accumulator == null ? "" : accumulator.snapshotTruncated();
        if (!partial.isEmpty()) {
            resp.setPartialContent(partial);
        }
        return resp;
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        if (!tokenOk(ex)) {
            sendError(ex, 401, "invalid or missing token");
            return;
        }
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("ok", true);
            info.put("pid", InstanceRegistry.currentPid());
            info.put("port", getPort());
            info.put("agentInitialized", isAgentInitialized());
            IpcResponse resp = new IpcResponse();
            resp.setSuccess(true);
            resp.setContent(MAPPER.writeValueAsString(info));
            send(ex, 200, resp);
        } catch (Exception e) {
            log.error("IPC /health error", e);
            sendError(ex, 500, "server error: " + rootMessage(e));
        }
    }

    /**
     * 解析 AgentLoop 单例;未初始化则用默认 model 预热(经 AiServiceFactory 缓存,
     * 与 GUI 用同一 model 时复用同一 AiService 引用 → 同一 AgentLoop 单例)。
     * agent 禁用时返回 null。health 等场景请用 {@link #isAgentInitialized()} 避免预热。
     */
    private AgentLoop resolveAgentLoop() {
        try {
            return AgentLoopFactory.getAgentLoop();
        } catch (IllegalStateException e) {
            AiService svc = AiServiceFactory.createService(AiConfig.getDefaultModel());
            AgentLoop loop = AgentLoopFactory.getAgentLoop(svc);
            if (loop == null) {
                return null;
            }
            log.info("IPC warmed up AgentLoop with default model {}", AiConfig.getDefaultModel());
            return loop;
        }
    }

    private boolean isAgentInitialized() {
        try {
            AgentLoopFactory.getAgentLoop();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    // ---------- helpers ----------

    private boolean checkPost(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method not allowed: use POST");
            return false;
        }
        return true;
    }

    private boolean tokenOk(HttpExchange ex) {
        String got = ex.getRequestHeaders().getFirst("X-IPC-Token");
        if (got == null) {
            return false;
        }
        return MessageDigest.isEqual(
                got.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    /** 读取并校验请求体;空体/超大/JSON 格式错已发送对应 4xx 响应并返回 null。 */
    private static IpcRequest parseRequest(HttpExchange ex) throws IOException {
        byte[] body;
        try {
            body = readBody(ex);
        } catch (BodyTooLargeException e) {
            sendError(ex, 413, e.getMessage());
            return null;
        } catch (IOException e) {
            sendError(ex, 400, "failed to read request body: " + rootMessage(e));
            return null;
        }
        if (body.length == 0) {
            sendError(ex, 400, "empty request body");
            return null;
        }
        try {
            return MAPPER.readValue(body, IpcRequest.class);
        } catch (IOException e) {
            sendError(ex, 400, "malformed JSON body: " + rootMessage(e));
            return null;
        }
    }

    private static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BODY_BYTES) {
                    throw new BodyTooLargeException();
                }
            }
            return bos.toByteArray();
        }
    }

    /** 请求体超过 {@link #MAX_BODY_BYTES} 时抛出,供 handler 映射为 413。 */
    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    private static void send(HttpExchange ex, int status, IpcResponse resp) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(resp);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) throws IOException {
        IpcResponse resp = new IpcResponse();
        resp.setSuccess(false);
        resp.setError(message);
        send(ex, status, resp);
    }

    private static IpcResponse fromToolResult(ToolResult r, long durationMs) {
        IpcResponse resp = new IpcResponse();
        resp.setSuccess(r.isSuccess());
        resp.setContent(r.getContent());
        resp.setError(r.getError());
        resp.setDurationMs(durationMs);
        return resp;
    }

    private static IpcResponse fromAgentResponse(AgentResponse r, long durationMs) {
        IpcResponse resp = new IpcResponse();
        resp.setSuccess(r.isSuccess());
        resp.setContent(r.getContent());
        resp.setErrorMessage(r.getErrorMessage());
        resp.setToolsUsed(r.getToolsUsed());
        resp.setIterations(r.getIterationCount());
        resp.setDurationMs(durationMs);
        return resp;
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause();
        return (c != null ? c : t).getMessage();
    }
}
