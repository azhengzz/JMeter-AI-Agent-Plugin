package org.gitee.jmeter.ai.recorder.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import org.gitee.jmeter.ai.recorder.RecorderJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最小命令集客户端：自动附着（flatten + waitForDebuggerOnStart）、页面会话初始化、
 * 网络事件解析、响应体/请求体/cookie 拉取。
 *
 * <p><b>控制通道的安全边界</b>：注入脚本、UI 事件监听与 binding 全部运行在
 * CDP **隔离世界**（{@code worldName}/{@code executionContextName} = {@value #WORLD_NAME}）：
 * 页面主世界的脚本既看不到 {@code __jaiRec} 也不知道悬浮条内部逻辑，无法窃取令牌或伪造
 * 控制指令（实测 Chrome 156：主世界 {@code typeof __jaiRec === "undefined"}）。
 * 隔离世界仍可监听 DOM 事件、渲染悬浮条。
 *
 * <p>既有文档注入：{@code Page.addScriptToEvaluateOnNewDocument} 配
 * {@code worldName + runImmediately}，attach 到已加载页面时立即执行（脚本自带安装守卫）。
 *
 * <p>{@code Network.responseReceived} 的 headers 被 Chrome 裁剪掉 Set-Cookie；原始头从
 * {@code Network.responseReceivedExtraInfo} 获取（多值以 {@code \n} 连接）——引擎按
 * sessionId|requestId 配对合并。
 */
public final class CdpClient implements CdpConnection.EventListener {
    private static final Logger log = LoggerFactory.getLogger(CdpClient.class);

    public static final String BINDING_NAME = "__jaiRec";
    /** 隔离世界名：页面主世界脚本不可见（绑定与注入脚本的宿主）。 */
    public static final String WORLD_NAME = "__jaiRecWorld";

    /** 面向引擎的语义化事件回调（回调线程 = 传输线程）。 */
    public interface Callbacks {
        /** 新页面附着完成（pageId 由本类分配，从 1 递增）。 */
        default void onPageAttached(int pageId, String sessionId) {
        }

        /** 页面会话初始化完成且已放行（runIfWaitingForDebugger 已发出）。 */
        default void onPageReady(int pageId, String sessionId) {
        }

        /** 隔离世界上下文就绪（注入脚本已可执行）——引擎在此推送悬浮条状态。 */
        default void onRecordingWorldReady(String sessionId) {
        }

        /** 主帧整页导航（新文档 = 新世界，需重推状态）。 */
        default void onFrameNavigated(String sessionId) {
        }

        /** 页面分离；attached 页面归零由引擎据此判定「全页面关闭」。 */
        default void onPageDetached(String sessionId) {
        }

        default void onRequestWillBeSent(int pageId, String sessionId, JsonNode params) {
        }

        default void onResponseReceived(int pageId, String sessionId, JsonNode params) {
        }

        /** 原始响应头（含 Set-Cookie；与 responseReceived 按 requestId 配对）。 */
        default void onResponseExtraInfo(String sessionId, JsonNode params) {
        }

        default void onLoadingFailed(int pageId, String sessionId, JsonNode params) {
        }

        /** bindingCalled：UI 事件与悬浮控制条指令（隔离世界回传，页面不可伪造）。 */
        default void onBindingCalled(int pageId, String sessionId, String payload) {
        }

        /** 传输断开（关浏览器通道）：RECORDING→无刷新收官 / ARMED→取消。 */
        default void onConnectionClosed() {
        }
    }

    private final CdpConnection connection;
    private final Callbacks callbacks;
    private final String initScript;
    private final Map<String, Integer> sessionPages = new ConcurrentHashMap<>();
    /** sessionId → 隔离世界 executionContextId（每次导航更新）。 */
    private final Map<String, Long> worldContexts = new ConcurrentHashMap<>();
    private volatile int pageSeq = 0;
    private volatile boolean closed;

    public CdpClient(CdpConnection connection, Callbacks callbacks, String initScript) {
        this.connection = connection;
        this.callbacks = callbacks;
        this.initScript = initScript;
        connection.setEventListener(this);
    }

    /**
     * 建立后会话自启附着链。先以 {@code Browser.getVersion} 预检（任何 Chromium 实现都即时应答）——
     * 预检超时 = WS 整体沉默，与 autoAttach 特定无应答可区分。
     */
    public CompletableFuture<Void> setup() {
        return connection.send("Browser.getVersion")
                .thenAccept(v -> log.info("CDP preflight ok: {}",
                        v.path("product").asText("?")))
                .thenCompose(v -> connection.send("Target.setAutoAttach", Map.of(
                                "autoAttach", true,
                                "waitForDebuggerOnStart", true,
                                "flatten", true))
                        .thenAccept(r -> log.info("CDP autoAttach enabled")));
    }

    /** 在新标签页打开 URL（attach 模式带起始页用）。 */
    public CompletableFuture<JsonNode> createTarget(String url) {
        return connection.send("Target.createTarget", Map.of("url", url));
    }

    /** 响应体拉取（截断由引擎负责）。 */
    public CompletableFuture<JsonNode> getResponseBody(String sessionId, String requestId) {
        return connection.send("Network.getResponseBody", Map.of("requestId", requestId), sessionId);
    }

    /** 请求体补拉（CDP 对过大 POST 体不在 requestWillBeSent 内联 postData 时使用）。 */
    public CompletableFuture<JsonNode> getRequestPostData(String sessionId, String requestId) {
        return connection.send("Network.getRequestPostData", Map.of("requestId", requestId), sessionId);
    }

    /**
     * cookie 快照：{@code Storage.getCookies} 为主（Network.getAllCookies 已弃用，仅回退）。
     * browser 级会话即可调用。
     */
    public CompletableFuture<JsonNode> getCookies() {
        return connection.send("Storage.getCookies")
                .exceptionallyCompose(e -> {
                    log.debug("Storage.getCookies unavailable, fallback to Network.getAllCookies: {}", e.getMessage());
                    return connection.send("Network.getAllCookies");
                });
    }

    /** 在页面隔离世界中求值（悬浮条状态/计数推送；主世界不可干预）。 */
    public CompletableFuture<JsonNode> evaluateInRecordingWorld(String sessionId, String expression) {
        Long contextId = worldContexts.get(sessionId);
        if (contextId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("recording world not ready"));
        }
        return connection.send("Runtime.evaluate", Map.of(
                "expression", expression, "contextId", contextId, "silent", true), sessionId);
    }

    /** 在所有已附着页面的隔离世界中求值。 */
    public void evaluateOnAllPages(String expression) {
        if (closed) {
            return;
        }
        for (String sid : sessionPages.keySet()) {
            evaluateInRecordingWorld(sid, expression).exceptionally(e -> {
                log.debug("evaluate in recording world failed: {}", e.getMessage());
                return null;
            });
        }
    }

    public boolean isConnected() {
        return !closed && connection.isTransportOpen();
    }

    public void close() {
        closed = true;
        connection.close();
    }

    /** 当前附着页面数（含正在 setup 的）。 */
    public int attachedPageCount() {
        return sessionPages.size();
    }

    public int pageIdOf(String sessionId) {
        Integer p = sessionPages.get(sessionId);
        return p == null ? 0 : p;
    }

    // ---- CdpConnection.EventListener ----

    @Override
    public void onEvent(String method, JsonNode params, String sessionId) {
        if (closed) {
            return;
        }
        switch (method) {
            case "Target.attachedToTarget" -> handleAttached(params);
            case "Target.detachedFromTarget" -> handleDetached(params);
            case "Network.requestWillBeSent" ->
                    callbacks.onRequestWillBeSent(pageIdOf(sessionId), sessionId, params);
            case "Network.responseReceived" ->
                    callbacks.onResponseReceived(pageIdOf(sessionId), sessionId, params);
            case "Network.responseReceivedExtraInfo" -> callbacks.onResponseExtraInfo(sessionId, params);
            case "Network.loadingFailed" ->
                    callbacks.onLoadingFailed(pageIdOf(sessionId), sessionId, params);
            case "Runtime.bindingCalled" -> handleBinding(sessionId, params);
            case "Runtime.executionContextCreated" -> handleContextCreated(sessionId, params);
            case "Runtime.executionContextDestroyed" -> handleContextDestroyed(params);
            case "Page.frameNavigated" -> {
                // 仅主帧整页导航（子帧 frameNavigated 会误推状态并触发假「tamper」告警）
                if (sessionId != null && sessionPages.containsKey(sessionId)
                        && params.path("frame").path("parentId").asText("").isEmpty()) {
                    callbacks.onFrameNavigated(sessionId);
                }
            }
            default -> {
                // 未收录事件忽略
            }
        }
    }

    @Override
    public void onTransportClosed() {
        closed = true;
        sessionPages.clear();
        worldContexts.clear();
        callbacks.onConnectionClosed();
    }

    private void handleAttached(JsonNode params) {
        String sid = params.path("sessionId").asText(null);
        String type = params.path("targetInfo").path("type").asText("");
        if (sid == null) {
            return;
        }
        if (!"page".equals(type)) {
            // waitForDebuggerOnStart 会暂停所有被附着 target——立即放行，避免冻结浏览器内部组件。
            // OOPIF（跨域 iframe）的网络域独立：须额外 enable Network，否则其内 XHR 不进录制。
            if ("iframe".equals(type)) {
                connection.send("Network.enable", null, sid).exceptionally(e -> {
                    log.debug("enable network on iframe target failed: {}", e.getMessage());
                    return null;
                });
            }
            connection.send("Runtime.runIfWaitingForDebugger", null, sid).exceptionally(e -> {
                log.debug("release non-page target failed ({}): {}", type, e.getMessage());
                return null;
            });
            return;
        }
        int pageId = ++pageSeq;
        sessionPages.put(sid, pageId);
        callbacks.onPageAttached(pageId, sid);
        // waitForDebuggerOnStart 把新建 target 挂起在启动处：先放行再装配——装配链中
        // 带求值的命令（addScriptToEvaluateOnNewDocument 的 runImmediately）在暂停态下
        // 不应答，若放行被排在装配之后，录制中 window.open 的新标签页会互锁成
        // 永久空白 loading。放行先行的两个代价均有兜底：脚本晚到经 runImmediately 对已
        // 开始加载的文档补注入；binding 晚到由注入脚本 send() 的有界重试兜住
        connection.send("Runtime.runIfWaitingForDebugger", null, sid).exceptionally(ex -> {
            log.debug("release failed for page {}: {}", pageId, ex.getMessage());
            return null;
        });
        // 每页会话初始化：enable → 隔离世界注入（runImmediately 覆盖既有文档）
        CompletableFuture.allOf(
                        connection.send("Runtime.enable", null, sid),
                        connection.send("Page.enable", null, sid),
                        connection.send("Network.enable", null, sid),
                        connection.send("Runtime.addBinding", Map.of(
                                "name", BINDING_NAME,
                                "executionContextName", WORLD_NAME), sid),
                        connection.send("Page.addScriptToEvaluateOnNewDocument", Map.of(
                                "source", initScript,
                                "worldName", WORLD_NAME,
                                "runImmediately", true), sid))
                .whenComplete((v, e) -> {
                    if (e != null) {
                        log.warn("Session setup incomplete for page {}: {}", pageId, e.getMessage());
                    }
                    callbacks.onPageReady(pageId, sid);
                });
    }

    private void handleDetached(JsonNode params) {
        String sid = params.path("sessionId").asText(null);
        if (sid == null) {
            return;
        }
        Integer pageId = sessionPages.remove(sid);
        worldContexts.remove(sid);
        if (pageId != null) {
            callbacks.onPageDetached(sid);
        }
    }

    private void handleContextCreated(String sessionId, JsonNode params) {
        JsonNode context = params.path("context");
        if (WORLD_NAME.equals(context.path("name").asText(""))) {
            worldContexts.put(sessionId, context.path("id").asLong());
            callbacks.onRecordingWorldReady(sessionId);
        }
    }

    private void handleContextDestroyed(JsonNode params) {
        long id = params.path("executionContextId").asLong(-1);
        worldContexts.values().removeIf(v -> v == id);
    }

    private void handleBinding(String sessionId, JsonNode params) {
        if (!BINDING_NAME.equals(params.path("name").asText())) {
            return;
        }
        String payload = params.path("payload").asText(null);
        if (payload == null) {
            return;
        }
        String json = payload.trim();
        if (!json.startsWith("{")) {
            try {
                JsonNode parsed = RecorderJson.parse(json);
                json = parsed.toString();
            } catch (Exception ignored) {
                return; // 非本脚本回传的载荷丢弃
            }
        }
        callbacks.onBindingCalled(pageIdOf(sessionId), sessionId, json);
    }
}
