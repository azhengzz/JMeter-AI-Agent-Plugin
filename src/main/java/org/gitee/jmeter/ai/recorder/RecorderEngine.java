package org.gitee.jmeter.ai.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.cdp.CdpClient;
import org.gitee.jmeter.ai.recorder.cdp.CdpConnection;
import org.gitee.jmeter.ai.recorder.cdp.CdpTransport;
import org.gitee.jmeter.ai.recorder.model.RecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 录制引擎：两阶段状态机 {@code IDLE → ARMED → RECORDING → FINALIZING → DONE/CANCELLED}。
 *
 * <ul>
 *   <li>ARMED 预备态零采集；begin() 取 cookie 基线并等待至收官。</li>
 *   <li>双通道停止流程等价、保真分级：控制面停止刷新 cookie + 宽限补拉；关窗通道连接已断
 *       自然降级。宽限窗（FINALIZING）内到达的补拉结果照常落盘。</li>
 *   <li>悬浮条控制指令来自 CDP 隔离世界 binding（页面主世界不可见不可调）——
 *       无需令牌，且一律转后台执行器（WS 侦听线程不可跑收官）。</li>
 *   <li>录制期 Set-Cookie 从 {@code Network.responseReceivedExtraInfo} 获取（responseReceived
 *       的 headers 被 Chrome 裁剪），多值以 \n 拆分后按 sessionId|requestId 配对。</li>
 *   <li>JVM 退出钩子：RECORDING 先写 cookies/summary 再关 store；ARMED 关 store 并删空目录。</li>
 * </ul>
 */
public final class RecorderEngine implements CdpClient.Callbacks {
    private static final Logger log = LoggerFactory.getLogger(RecorderEngine.class);

    public enum State {IDLE, ARMED, RECORDING, FINALIZING, DONE, CANCELLED}

    /**
     * 打开参数。resourceTypes/maxBodyBytes 为 null 时沿用 {@link RecorderConfig} 默认
     * （Agent 工具路径）；GUI 参数窗按用户勾选/输入传入。
     */
    public record OpenParams(String url, Set<String> includeDomains,
                             Set<String> resourceTypes, Integer maxBodyBytes) {
        public OpenParams(String url, Set<String> includeDomains) {
            this(url, includeDomains, null, null);
        }
    }

    public record Status(State state, long requests, long responses, long uiEvents,
                         long startedAt, java.nio.file.Path dir) {
    }

    public interface EngineListener {
        default void onState(State from, State to) {
        }

        /** 收官产物就绪（events.jsonl / cookies.json 已落盘）。 */
        default void onFinalized(Status status) {
        }

        /** 预备态取消（零产物）。 */
        default void onCancelled(String reason) {
        }
    }

    /** 浏览器获取接缝（真实 = BrowserInstanceFinder 优先链；测试 = 预置句柄）。 */
    public interface BrowserProvider {
        BrowserInstanceFinder.BrowserHandle open(RecorderConfig config,
                                                 java.nio.file.Path workspace, String startUrl) throws IOException;
    }

    /** 传输工厂接缝（真实 = WebSocket；测试 = fake）。 */
    public interface TransportFactory {
        CdpTransport create();
    }

    private static final long SETUP_TIMEOUT_MS = 10000;
    private static final long BASELINE_WAIT_MS = 1500;
    /** 响应体超过该编码长度不拉取（防导出文件类大响应内存尖峰）。 */
    private static final long MAX_BODY_FETCH_BYTES = 4L * 1024 * 1024;
    private static final int MAX_UI_TEXT = 200;
    private static final int MAX_UI_SELECTOR = 300;
    private static final int MAX_UI_NAME = 100;
    private static final int MAX_UI_VALUE = 200;
    private static final int MAX_UI_URL = 2048;

    private final RecorderConfig config;
    private final EngineListener listener;
    private final java.nio.file.Path workspace;
    private final String instanceId;
    private final BrowserProvider browserProvider;
    private final TransportFactory transportFactory;
    private final String initScript;

    private final Object lock = new Object();
    private State state = State.IDLE;
    private RecordingStore store;
    private BrowserInstanceFinder.BrowserHandle browser;
    private CdpConnection connection;
    private CdpClient client;
    private Thread shutdownHook;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jai-rec-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong responseCount = new AtomicLong();
    private final AtomicLong uiEventCount = new AtomicLong();
    private volatile long startedAt;
    private final AtomicBoolean finalizing = new AtomicBoolean();

    private EventFilter filter;
    /** 已登记请求簿记，键 = sessionId|requestId（requestId 仅在单页面会话内唯一）；即用即清。 */
    private final Map<String, String> trackedUrls = new java.util.concurrent.ConcurrentHashMap<>();
    /** ExtraInfo 原始头缓存：键 = sessionId|requestId → Set-Cookie 值（已按 \n 拆分）。 */
    private final Map<String, List<String>> pendingSetCookies = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<CompletableFuture<?>> pendingAsync =
            java.util.Collections.synchronizedList(new ArrayList<>());
    /** cookie 三源：begin 基线 / 录制期 Set-Cookie 累积 / stop 刷新（取最新覆盖）。 */
    private volatile CompletableFuture<JsonNode> baselineFuture;
    private volatile JsonNode cookieBaseline;
    private volatile JsonNode cookieRefresh;
    private final List<ObjectNode> setCookieAccum = new ArrayList<>();

    public RecorderEngine(RecorderConfig config, EngineListener listener, java.nio.file.Path workspace,
                          String instanceId, BrowserProvider browserProvider,
                          TransportFactory transportFactory, String initScript) {
        this.config = config;
        this.listener = listener;
        this.workspace = workspace;
        this.instanceId = instanceId;
        this.browserProvider = browserProvider;
        this.transportFactory = transportFactory;
        this.initScript = initScript;
    }

    // ---- 公共 API（GUI 按钮 / Agent 工具共用；进程内单会话） ----

    /** 打开浏览器入预备态。非 IDLE 拒绝（单会话约束）；失败抛可读异常并保持 IDLE。 */
    public void open(OpenParams params) throws IOException {
        synchronized (lock) {
            if (state != State.IDLE) {
                throw new IllegalStateException("已有录制会话进行中（" + state + "）");
            }
            RecordingStore newStore = null;
            BrowserInstanceFinder.BrowserHandle newBrowser = null;
            try {
                newStore = RecordingStore.create(config.resolveOutputDir(workspace), instanceId);
                Set<String> domains = params.includeDomains() == null ? Set.of() : params.includeDomains();
                Set<String> types = params.resourceTypes() == null || params.resourceTypes().isEmpty()
                        ? config.resourceTypes : params.resourceTypes();
                int maxBody = params.maxBodyBytes() == null ? config.maxBodyBytes : params.maxBodyBytes();
                this.filter = new EventFilter(types, domains, maxBody);
                newBrowser = browserProvider.open(config, workspace, params.url());
                connection = new CdpConnection(transportFactory.create());
                connection.connect(newBrowser.wsUrl());
                client = new CdpClient(connection, this, initScript);
                // setAutoAttach 被拒/超时 = 死会话（永远采不到流量），必须在 open 就报可读错误
                try {
                    client.setup().get(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    throw new IOException("CDP 自动附着失败（Target.setAutoAttach 应答超时 "
                            + SETUP_TIMEOUT_MS + "ms）。常见原因：①此前录制失败残留的僵尸浏览器占着 profile"
                            + "（新实例被单实例转交、端点无响应）——请先关闭所有 Chrome 录制窗口"
                            + "或任务管理器结束 chrome 进程后重试；②组策略禁用远程调试"
                            + "（DevToolsRemoteDebuggingAllowed）。原始错误：" + rootMessage(e));
                }
                if (!newBrowser.own() && params.url() != null && !params.url().isBlank()) {
                    // attach 模式带起始 URL：新标签页打开（自有实例直接以 URL 参数启动）
                    client.createTarget(params.url()).exceptionally(e -> {
                        log.debug("createTarget failed: {}", e.getMessage());
                        return null;
                    });
                }
                this.store = newStore;
                this.browser = newBrowser;
                if (newBrowser.fallbackReason() != null) {
                    log.warn("Recording browser fell back to a temp profile ({}); "
                            + "login state will not persist for this session", newBrowser.fallbackReason());
                }
                transition(State.ARMED);
                registerShutdownHook();
                log.info("Recording session ARMED (dir={})", newStore.dir());
            } catch (Exception e) {
                cleanupAbortedOpen(newStore, newBrowser);
                throw new IOException("打开录制浏览器失败：" + e.getMessage(), e);
            }
        }
    }

    /** 显式开始录制（悬浮控制条 / 面板按钮 / Agent 工具）。ARMED→RECORDING 并取 cookie 基线。 */
    public void begin() {
        synchronized (lock) {
            if (state != State.ARMED) {
                return;
            }
            startedAt = System.currentTimeMillis();
            transition(State.RECORDING);
        }
        if (client != null) {
            // 基线快照：此刻连接必然存活、预备态登录 cookie 必然在场。
            // future 保留至收官等待——应答晚于收官不再静默丢基线
            baselineFuture = client.getCookies();
            baselineFuture.whenComplete((cookies, err) -> {
                if (err == null) {
                    cookieBaseline = filterCookies(cookies.path("cookies"));
                } else {
                    log.debug("Cookie baseline unavailable: {}", err.getMessage());
                }
            });
            client.evaluateOnAllPages(RecorderScripts.stateExpression("recording", 0));
        }
    }

    /** 控制面停止：RECORDING→收官（连接仍活 → 刷新 cookie + 2s 宽限补拉未完体）；ARMED→取消。 */
    public void stop() {
        synchronized (lock) {
            if (state == State.ARMED) {
                cancelLocked("stopped in armed state");
                return;
            }
        }
        finalizeRecording(true);
    }

    public Status status() {
        synchronized (lock) {
            return new Status(state, requestCount.get(), responseCount.get(), uiEventCount.get(),
                    startedAt, store == null ? null : store.dir());
        }
    }

    /** 请求计数快照（无锁：EDT 定时器轮询专用，避免与收官锁段竞争）。 */
    public long requestCount() {
        return requestCount.get();
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    // ---- 收官 / 取消 ----

    private void finalizeRecording(boolean refresh) {
        if (!finalizing.compareAndSet(false, true)) {
            return; // single-flight：并发触发只生效一次
        }
        synchronized (lock) {
            if (state != State.RECORDING) {
                finalizing.set(false);
                return;
            }
            transition(State.FINALIZING);
        }
        // 悬浮条复位（尽力而为；关窗通道连接已断时静默失败）
        if (client != null && client.isConnected()) {
            client.evaluateOnAllPages(RecorderScripts.stateExpression("armed", 0));
        }
        try {
            CompletableFuture<?> refreshFuture = null;
            if (refresh && client != null && client.isConnected()) {
                refreshFuture = client.getCookies().orTimeout(500, TimeUnit.MILLISECONDS)
                        .whenComplete((cookies, err) -> {
                            if (err == null) {
                                cookieRefresh = filterCookies(cookies.path("cookies"));
                            }
                        });
            }
            // 宽限 ≤2s：等未完响应体/请求体（关窗通道 future 立即异常完成，天然不等）
            awaitPendingAsync(2000);
            CompletableFuture<?> bf = baselineFuture;
            if (bf != null) {
                try {
                    bf.get(BASELINE_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // 超时：回退 Set-Cookie 累积
                }
            }
            if (refreshFuture != null) {
                try {
                    refreshFuture.get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 刷新失败回退基线 + Set-Cookie 累积
                }
            }
            synchronized (lock) {
                if (client != null) {
                    client.close();
                }
                if (browser != null && browser.own()) {
                    browser.destroy();
                }
                writeCookieArtifact();
                writeSummaryArtifact("completed");
                closeStoreQuietly();
                unregisterShutdownHook();
                transition(State.DONE);
                disposeBookkeeping();
                log.info("Recording finalized: {} requests, {} responses", requestCount.get(), responseCount.get());
                listener.onFinalized(status());
            }
        } catch (Exception e) {
            log.warn("Finalize error: {}", e.getMessage(), e);
            synchronized (lock) {
                // try 段在 browser.destroy() 之前抛出时的兜底销毁（双触发安全：destroy 不抛、
                // 声明释放幂等）——漏掉会让 chrome 与 JVM 持有的占用声明（FileLock）存活到
                // 进程退出，本 JVM 此后每场录制都被视同占用而永久临时回退
                if (browser != null && browser.own()) {
                    try {
                        browser.destroy();
                    } catch (Exception ignored) {
                        // 收尾兜底不得再抛
                    }
                }
                closeStoreQuietly();
                transition(State.DONE);
                disposeBookkeeping();
            }
        } finally {
            finalizing.set(false);
        }
    }

    private void cancelLocked(String reason) {
        // 调用方已持 lock
        transition(State.CANCELLED);
        if (client != null) {
            client.close();
        }
        if (browser != null && browser.own()) {
            browser.destroy();
        }
        if (store != null) {
            store.deleteQuietly();
        }
        unregisterShutdownHook();
        disposeBookkeeping();
        log.info("Recording cancelled: {}", reason);
        listener.onCancelled(reason);
    }

    /** 终态清理：簿记清空 + 调度器关闭（引擎单次使用，防每场录制泄一个空闲线程）。 */
    private void disposeBookkeeping() {
        trackedUrls.clear();
        pendingSetCookies.clear();
        pendingAsync.clear();
        scheduler.shutdownNow();
    }

    private void cleanupAbortedOpen(RecordingStore newStore, BrowserInstanceFinder.BrowserHandle newBrowser) {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        client = null;
        if (newBrowser != null && newBrowser.own()) {
            newBrowser.destroy();
        }
        if (newStore != null) {
            newStore.deleteQuietly();
        }
        baselineFuture = null;
    }

    private void awaitPendingAsync(long timeoutMs) {
        CompletableFuture<?>[] snapshot;
        synchronized (pendingAsync) {
            snapshot = pendingAsync.toArray(new CompletableFuture<?>[0]);
        }
        if (snapshot.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(snapshot).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // 超时/失败：未完响应体按无正文事件处理（spec 允许）
        }
    }

    private void writeCookieArtifact() {
        if (store == null) {
            return;
        }
        // 三源分立存储（转换器做 baseline ⊕ setCookies(时序) ⊕ refresh 精确合并）：
        // close 通道无 refresh、事件流覆盖不了预备期，但 baseline 必在
        ObjectNode root = RecorderJson.MAPPER.createObjectNode();
        root.set("baseline", cookieBaseline == null ? RecorderJson.MAPPER.nullNode() : cookieBaseline);
        root.set("refresh", cookieRefresh == null ? RecorderJson.MAPPER.nullNode() : cookieRefresh);
        ArrayNode acc = root.putArray("setCookies");
        synchronized (setCookieAccum) {
            setCookieAccum.forEach(acc::add);
        }
        store.writeCookies(root);
    }

    private void writeSummaryArtifact(String result) {
        if (store == null) {
            return;
        }
        ObjectNode s = RecorderJson.MAPPER.createObjectNode();
        s.put("result", result);
        s.put("startedAt", startedAt);
        s.put("endedAt", System.currentTimeMillis());
        s.put("requests", requestCount.get());
        s.put("responses", responseCount.get());
        s.put("uiEvents", uiEventCount.get());
        store.writeSummary(s);
    }

    private void closeStoreQuietly() {
        try {
            if (store != null) {
                store.close();
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private void transition(State to) {
        State from = state;
        state = to;
        if (from != to) {
            listener.onState(from, to);
        }
    }

    // ---- CdpClient.Callbacks（传输线程） ----

    @Override
    public void onPageAttached(int pageId, String sessionId) {
        // 页面附着本身不产生事件
    }

    @Override
    public void onPageReady(int pageId, String sessionId) {
        pushState(sessionId);
    }

    @Override
    public void onRecordingWorldReady(String sessionId) {
        // 隔离世界上下文就绪（既有文档首注入 / 导航后），推送当前悬浮条状态
        pushState(sessionId);
    }

    @Override
    public void onFrameNavigated(String sessionId) {
        // 主帧整页导航：新文档 = 新隔离世界，重推状态与计数
        pushState(sessionId);
    }

    private void pushState(String sessionId) {
        CdpClient c = client;
        if (c == null) {
            return;
        }
        boolean recording = state() == State.RECORDING;
        c.evaluateInRecordingWorld(sessionId, RecorderScripts.stateExpression(
                        recording ? "recording" : "armed", recording ? requestCount.get() : 0))
                .exceptionally(e -> {
                    log.debug("push recording state failed: {}", e.getMessage());
                    return null;
                });
    }

    @Override
    public void onPageDetached(String sessionId) {
        if (client != null && client.attachedPageCount() == 0) {
            // 全页面关闭：800ms 延迟复检兜住 Windows 浏览器后台驻留
            scheduler.schedule(() -> {
                if (state() == State.RECORDING && client != null && client.attachedPageCount() == 0) {
                    log.info("All pages closed → finalize (browser-closed channel)");
                    finalizeRecording(false);
                } else if (state() == State.ARMED && client != null && client.attachedPageCount() == 0) {
                    synchronized (lock) {
                        if (state == State.ARMED) {
                            cancelLocked("browser closed in armed state");
                        }
                    }
                }
            }, 800, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onRequestWillBeSent(int pageId, String sessionId, JsonNode params) {
        if (state() != State.RECORDING) {
            return; // ARMED 预备态零采集
        }
        String requestId = params.path("requestId").asText();
        String trackKey = sessionId + "|" + requestId;
        String type = params.path("type").asText("");
        JsonNode request = params.path("request");
        String url = request.path("url").asText();
        if ("Document".equals(type)) {
            // 导航事件同样受只采域名约束
            if (filter.domainAllowed(url)) {
                emit(pageId, "navigate", Map.of("url", url));
            }
            if (!filter.resourceTypeAllowed(type)) {
                return; // 未勾选 Document：仅记导航上下文，不作为请求采集（默认 xhr/fetch 的旧语义）
            }
            // 勾选了 Document：落入下方通用采集路径，导航请求也录成采样器
        }
        if (!filter.shouldCaptureRequest(type, url)) {
            return;
        }
        trackedUrls.put(trackKey, url);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("method", request.path("method").asText());
        data.put("url", url);
        data.put("resourceType", type);
        data.put("headers", flattenHeaders(request.path("headers")));
        String postData = request.hasNonNull("postData") ? request.path("postData").asText() : null;
        if (postData != null) {
            EventFilter.Truncated t = filter.truncateBody(postData);
            data.put("postData", t.value());
            if (t.truncated()) {
                data.put("postDataTruncated", true);
            }
        }
        emit(pageId, "request", data);
        if (postData == null && request.path("hasPostData").asBoolean(false)) {
            // CDP 对过大 POST 体不在内联字段携带：异步补拉（与响应体同构的宽限语义）
            fetchPostDataAsync(pageId, sessionId, requestId);
        }
    }

    @Override
    public void onResponseReceived(int pageId, String sessionId, JsonNode params) {
        if (state() != State.RECORDING) {
            return;
        }
        String requestId = params.path("requestId").asText();
        String trackKey = sessionId + "|" + requestId;
        String trackedUrl = trackedUrls.remove(trackKey); // 簿记即用即清
        if (trackedUrl == null) {
            return; // 白名单外请求的响应连带丢弃
        }
        JsonNode response = params.path("response");
        String url = response.path("url").asText();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("status", response.path("status").asInt());
        data.put("url", url);
        data.put("headers", flattenHeaders(response.path("headers")));
        emit(pageId, "response", data);
        accumulateSetCookies(sessionId, requestId, url, response.path("headers"));
        double encodedLength = response.path("encodedDataLength").asDouble(0);
        if (encodedLength > MAX_BODY_FETCH_BYTES) {
            log.debug("Skip response body fetch ({} bytes > cap) for {}", (long) encodedLength, url);
        } else {
            fetchBodyAsync(pageId, sessionId, requestId);
        }
    }

    @Override
    public void onResponseExtraInfo(String sessionId, JsonNode params) {
        if (state() != State.RECORDING) {
            return;
        }
        String requestId = params.path("requestId").asText();
        List<String> values = new ArrayList<>();
        for (String v : headerValues(params.path("headers"), "set-cookie")) {
            // ExtraInfo 的多值 Set-Cookie 以 \n 连接：先拆行
            for (String line : v.split("\n")) {
                if (!line.isBlank()) {
                    values.add(line.trim());
                }
            }
        }
        if (!values.isEmpty()) {
            pendingSetCookies.put(sessionId + "|" + requestId, values);
        }
    }

    @Override
    public void onLoadingFailed(int pageId, String sessionId, JsonNode params) {
        if (state() != State.RECORDING) {
            return;
        }
        String requestId = params.path("requestId").asText();
        String trackKey = sessionId + "|" + requestId;
        String url = trackedUrls.remove(trackKey);
        if (url == null) {
            return;
        }
        emit(pageId, "requestfailed", Map.of(
                "requestId", requestId,
                "url", url,
                "errorText", params.path("errorText").asText()));
    }

    @Override
    public void onBindingCalled(int pageId, String sessionId, String payload) {
        JsonNode p;
        try {
            p = RecorderJson.parse(payload);
        } catch (Exception e) {
            return;
        }
        String kind = p.path("t").asText("");
        if ("ctrl".equals(kind)) {
            // binding 仅存在于隔离世界（页面主世界不可见不可调），无需令牌即可信任其来源
            // 
            String action = p.path("a").asText();
            // 转后台执行器：收官会阻塞数秒，不能占着 WS 侦听线程（应答只能由同一线程分发）
            if ("begin".equals(action)) {
                CompletableFuture.runAsync(this::begin);
            } else if ("stop".equals(action)) {
                CompletableFuture.runAsync(this::stop);
            }
            return;
        }
        if (!"ui".equals(kind) || state() != State.RECORDING) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", cap(p.path("url").asText(""), MAX_UI_URL));
        if (p.hasNonNull("selector")) {
            data.put("selector", cap(p.path("selector").asText(), MAX_UI_SELECTOR));
        }
        if (p.hasNonNull("tag")) {
            data.put("tag", cap(p.path("tag").asText(), 40));
        }
        if (p.hasNonNull("text")) {
            data.put("text", cap(p.path("text").asText(), MAX_UI_TEXT));
        }
        if (p.hasNonNull("name")) {
            data.put("name", cap(p.path("name").asText(), MAX_UI_NAME));
        }
        if (p.hasNonNull("value")) {
            data.put("value", cap(p.path("value").asText(), MAX_UI_VALUE));
        }
        if (p.path("redacted").asBoolean(false)) {
            data.put("redacted", true); // 密码输入：只记动作不记值（注入脚本侧已脱敏）
        }
        emit(pageId, p.path("ev").asText("click"), data);
        uiEventCount.incrementAndGet();
    }

    /** 关浏览器通道：RECORDING → 无刷新收官（cookie 用开始基线）；ARMED → 取消。
     *  收官含数秒等待，转后台执行器——不占 WS 侦听线程。 */
    @Override
    public void onConnectionClosed() {
        State s = state();
        if (s == State.RECORDING) {
            CompletableFuture.runAsync(() -> finalizeRecording(false));
        } else if (s == State.ARMED) {
            CompletableFuture.runAsync(() -> {
                synchronized (lock) {
                    if (state == State.ARMED) {
                        cancelLocked("browser closed");
                    }
                }
            });
        }
    }

    // ---- 异步补拉 ----

    private void fetchBodyAsync(int pageId, String sessionId, String requestId) {
        if (client == null) {
            return;
        }
        CompletableFuture<JsonNode> bodyFuture = client.getResponseBody(sessionId, requestId)
                .whenComplete((result, err) -> {
                    // 宽限窗内（FINALIZING）到达的响应体也收——spec「宽限等待未完响应体」
                    if (err != null || !acceptingAsync()) {
                        return;
                    }
                    String body = result.path("body").asText(null);
                    if (body == null) {
                        return;
                    }
                    boolean base64 = result.path("base64Encoded").asBoolean(false);
                    emitTruncatedPayload(pageId, "responseBody", requestId, body, base64);
                });
        pendingAsync.add(bodyFuture);
    }

    private void fetchPostDataAsync(int pageId, String sessionId, String requestId) {
        CompletableFuture<JsonNode> postFuture = client.getRequestPostData(sessionId, requestId)
                .whenComplete((result, err) -> {
                    if (err != null || !acceptingAsync()) {
                        return;
                    }
                    String postData = result.path("postData").asText(null);
                    if (postData == null) {
                        return;
                    }
                    emitTruncatedPayload(pageId, "requestPostData", requestId, postData, false);
                });
        pendingAsync.add(postFuture);
    }

    private boolean acceptingAsync() {
        State s = state();
        return s == State.RECORDING || s == State.FINALIZING;
    }

    private void emitTruncatedPayload(int pageId, String type, String requestId, String payload, boolean base64) {
        String value;
        boolean truncated;
        if (base64) {
            // base64 载荷按 4 字符对齐截断，避免产生非法 base64（上限取本会话过滤器的值，
            // 与文本路径同源——弹窗按次覆盖须对 base64 响应体同样生效）
            if (filter.maxBodyBytes > 0 && payload.length() > filter.maxBodyBytes) {
                int aligned = (filter.maxBodyBytes / 4) * 4;
                value = payload.substring(0, aligned);
                truncated = true;
            } else {
                value = payload;
                truncated = false;
            }
        } else {
            EventFilter.Truncated t = filter.truncateBody(payload);
            value = t.value();
            truncated = t.truncated();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put(type.equals("responseBody") ? "body" : "postData", value);
        if (base64) {
            data.put("base64Encoded", true);
        }
        if (truncated) {
            data.put(type.equals("responseBody") ? "bodyTruncated" : "postDataTruncated", true);
        }
        emit(pageId, type, data);
    }

    private void accumulateSetCookies(String sessionId, String requestId, String url, JsonNode headers) {
        List<String> extra = pendingSetCookies.remove(sessionId + "|" + requestId);
        List<String> values = new ArrayList<>(extra == null ? List.of() : extra);
        // 回退：response.headers 可能含 set-cookie（旧浏览器/非裁剪场景）
        for (String v : headerValues(headers, "set-cookie")) {
            for (String line : v.split("\n")) {
                if (!line.isBlank()) {
                    values.add(line.trim());
                }
            }
        }
        if (values.isEmpty()) {
            return;
        }
        synchronized (setCookieAccum) {
            for (String v : values) {
                ObjectNode n = RecorderJson.MAPPER.createObjectNode();
                n.put("url", url);
                n.put("value", v);
                setCookieAccum.add(n);
            }
        }
    }

    /** 只采域名同样约束 cookie。 */
    private JsonNode filterCookies(JsonNode cookies) {
        if (cookies == null || !cookies.isArray() || filter == null) {
            return cookies;
        }
        ArrayNode kept = RecorderJson.MAPPER.createArrayNode();
        cookies.forEach(c -> {
            String domain = c.path("domain").asText("");
            String normalized = domain.startsWith(".") ? domain.substring(1) : domain;
            if (normalized.isEmpty() || filter.domainAllowed("https://" + normalized + "/")) {
                kept.add(c);
            }
        });
        return kept;
    }

    private void emit(int pageId, String type, Map<String, Object> data) {
        try {
            RecordedEvent event = RecordedEvent.of(seq.incrementAndGet(), pageId, type, data);
            if (type.equals("request")) {
                requestCount.incrementAndGet();
                broadcastCount();
            } else if (type.equals("response")) {
                responseCount.incrementAndGet();
            }
            RecordingStore s = store;
            if (s != null) {
                s.append(event);
            }
        } catch (IOException e) {
            log.warn("Failed to persist event {}: {}", type, e.getMessage());
        }
    }

    /** 悬浮条请求计数同步（spec「停止 + 计数」；人类节奏下每请求一次 evaluate 足够轻）。 */
    private void broadcastCount() {
        CdpClient c = client;
        if (c != null && state() == State.RECORDING) {
            c.evaluateOnAllPages(RecorderScripts.stateExpression("recording", requestCount.get()));
        }
    }

    private static String cap(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }

    // ---- JVM 退出收尾 ----

    private void registerShutdownHook() {
        shutdownHook = new Thread(this::shutdownOnce, "jai-rec-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM 已在关闭流程中
        }
    }

    private void unregisterShutdownHook() {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /**
     * 退出钩子：RECORDING → 先写 cookies/summary 再关 store；ARMED → 关 store 并删空目录（与取消语义一致）。
     */
    private void shutdownOnce() {
        synchronized (lock) {
            if (state != State.ARMED && state != State.RECORDING) {
                return;
            }
            if (client != null) {
                client.close();
            }
            if (browser != null && browser.own()) {
                browser.destroy();
            }
            if (state == State.RECORDING) {
                writeSummaryArtifact("interrupted");
                writeCookieArtifact();
                closeStoreQuietly();
            } else if (store != null) {
                store.deleteQuietly();
            }
        }
    }

    // ---- 工具 ----

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }

    private static Map<String, String> flattenHeaders(JsonNode headers) {
        Map<String, String> map = new LinkedHashMap<>();
        if (headers != null && headers.isObject()) {
            headers.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                map.put(e.getKey(), v.isTextual() ? v.asText() : v.toString());
            });
        }
        return map;
    }

    private static List<String> headerValues(JsonNode headers, String lowerName) {
        List<String> values = new ArrayList<>();
        if (headers != null && headers.isObject()) {
            headers.fields().forEachRemaining(e -> {
                if (e.getKey().equalsIgnoreCase(lowerName)) {
                    JsonNode v = e.getValue();
                    if (v.isArray()) {
                        v.forEach(x -> values.add(x.asText()));
                    } else {
                        values.add(v.asText());
                    }
                }
            });
        }
        return values;
    }
}
