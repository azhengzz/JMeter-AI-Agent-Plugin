package org.gitee.jmeter.ai.ipc;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.AgentLoopFactory;
import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.GatedCall;
import org.gitee.jmeter.ai.agent.testsupport.GatedScriptAiService.InterruptStrategy;
import org.gitee.jmeter.ai.agent.testsupport.NoopTool;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.ipc.protocol.IpcRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route-isolation regression: while several {@code /agent} requests hold handler
 * threads waiting on long turns, {@code /health} must still answer immediately.
 *
 * <p>Under the previous fixed 4-thread {@code ipc-worker} pool, 4+ concurrent
 * long {@code /agent} waits starved every other route (head-of-line blocking on
 * the shared bounded pool). The cached pool removes that structural starvation.
 *
 * <p>Each POST uses a distinct session (same-session requests would be
 * REJECTED_BUSY immediately and hold no thread). The first turn is gated inside
 * its LLM call; subsequent turns queue on the loop's single-thread executor —
 * either way their handler threads sit blocked in {@code future.get}, which is
 * exactly the occupation the old pool could not absorb.
 */
class IpcServerRouteIsolationConcurrencyTest {

    private static final long WAIT_SECONDS = 10;
    private static final String TOKEN_KEY = "jmeter.ai.ipc.token";
    private static final String FIXED_TOKEN = "route-isolation-token";

    /** 类级共享：与 IpcServerAgentEnvelopeWireTest 同款真实单例 server(临时 home)。 */
    @TempDir
    static Path sharedDir;

    @TempDir
    Path tempDir;

    private static String prevHome;
    private static String tokenInEffect;

    AgentLoop loop;
    GatedScriptAiService aiService;
    Object originalFactoryInstance;

    @BeforeAll
    static void bootRealIpcServer() throws Exception {
        ensureJMeterProps();
        JMeterUtils.setProperty("jmeter.ai.ipc.enabled", "true");
        JMeterUtils.setProperty(TOKEN_KEY, FIXED_TOKEN);
        prevHome = JMeterUtils.getJMeterHome();
        JMeterUtils.setJMeterHome(sharedDir.toString());
        Field toolRegistryField = IpcServer.class.getDeclaredField("toolRegistry");
        toolRegistryField.setAccessible(true);
        toolRegistryField.set(IpcServer.getInstance(), new ToolRegistry());

        IpcServer.getInstance().start();
        // 单例可能已被同 JVM 的其他测试类先行启动:反射读活 token,断言独立于启动顺序
        tokenInEffect = readExpectedToken();
    }

    @AfterAll
    static void restoreGlobalState() {
        if (prevHome != null) {
            JMeterUtils.setJMeterHome(prevHome);
        }
        JMeterUtils.getJMeterProperties().remove(TOKEN_KEY);
    }

    @BeforeEach
    void setUpLoop() throws Exception {
        aiService = new GatedScriptAiService(InterruptStrategy.HANG_UNTIL_RELEASED);
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        ToolRegistry registry = new ToolRegistry(Runnable::run);
        registry.register(new NoopTool());
        loop = new AgentLoop(registry, memoryStore, Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"), aiService);
        originalFactoryInstance = swapFactoryInstance(loop);
    }

    @AfterEach
    void tearDownLoop() throws Exception {
        swapFactoryInstance(originalFactoryInstance);
        loop.shutdown();
    }

    @Test
    @Timeout(60)
    void healthStaysResponsiveWhileConcurrentAgentWaitsHoldHandlers() throws Exception {
        // 首回合:门控挂起(占住 loop 线程);其余 5 发各自独立会话 → 在 loop 单线程
        // executor 上排队,handler 线程各自阻塞在 future.get —— 6 个 ipc-worker 被占
        GatedCall held = aiService.scriptGated(LLMResponse.text("held-final"));
        List<CompletableFuture<HttpResponse<String>>> posts = new ArrayList<>();
        CountDownLatch firstEntered = held.entered;
        posts.add(postAsync("/agent", agentRequest("route-isolation: held turn", "route-sess-0")));
        assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the held turn must be in flight");
        for (int i = 1; i <= 5; i++) {
            aiService.script(LLMResponse.text("queued-final-" + i));
            posts.add(postAsync("/agent", agentRequest("route-isolation: queued " + i, "route-sess-" + i)));
        }

        // 行为锚:6 个长等待在途时 /health 立即应答(旧固定 4 池在此饿死——
        // 第 5 个等待起 /health 排在无界队列尾,数分钟内无响应)
        long t0 = System.currentTimeMillis();
        HttpResponse<String> health = healthGet();
        long elapsedMs = System.currentTimeMillis() - t0;
        assertTrue(health.statusCode() == 200,
                "/health must answer while agent waits hold handlers: " + health.statusCode());
        assertTrue(elapsedMs < 3_000,
                "/health must answer immediately, took " + elapsedMs + "ms");

        // 收尾:放行门控 + 停 held 回合,排水全部 POST(不留在测试 JVM 里烧等待)
        held.hang.countDown();
        AgentLoopFactory.signalCancelAny("route-sess-0");
        for (CompletableFuture<HttpResponse<String>> post : posts) {
            post.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(60)
    void healthStaysResponsiveWhileSlowToolRequestsHoldHandlers() throws Exception {
        // spec instance-coordination 场景二:多个 /tool 慢工具执行(每个占住一个
        // handler 线程,量级同 run_test 冒烟 ~200s)在途期间 /health 立即应答——
        // /tool 与 /agent 共享同一执行池,池扩容对两者同解。慢工具以白名单名
        // (find_element)注册进 server registry(handleTool 先校验 ALLOWED_TOOLS)。
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        ToolRegistry serverRegistry = serverToolRegistry();
        serverRegistry.register(new SlowWhitelistedTool(gate, entered));
        List<CompletableFuture<HttpResponse<String>>> posts = new ArrayList<>();
        try {
            IpcRequest toolReq = new IpcRequest();
            toolReq.setOp("tool");
            toolReq.setTool("find_element");
            for (int i = 0; i < 5; i++) {
                posts.add(postAsync("/tool", toolReq));
            }
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                    "at least one slow /tool request must be in flight before probing /health");

            long t0 = System.currentTimeMillis();
            HttpResponse<String> health = healthGet();
            long elapsedMs = System.currentTimeMillis() - t0;
            assertTrue(health.statusCode() == 200,
                    "/health must answer while slow /tool requests hold handlers: " + health.statusCode());
            assertTrue(elapsedMs < 3_000, "/health must answer immediately, took " + elapsedMs + "ms");
        } finally {
            gate.countDown();
            serverRegistry.unregister("find_element"); // 恢复共享 server registry,防跨类污染
            for (CompletableFuture<HttpResponse<String>> post : posts) {
                post.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    // ---- helpers(与 IpcServerAgentEnvelopeWireTest 同配方) ----

    /**
     * 经 /tool 路由的慢工具:以白名单名 find_element 注册(过 handleTool 的
     * ALLOWED_TOOLS 门),进入即发 entered 信号、挂 gate 模拟长阻塞(量级同
     * run_test 冒烟 ~200s)。命名静态类而非匿名类——匿名 AbstractTool 子类经
     * ipc-worker 线程反射派发时曾有 AbstractMethodError 解析错配。
     */
    static final class SlowWhitelistedTool extends AbstractTool {
        private final CountDownLatch gate;
        private final CountDownLatch entered;

        SlowWhitelistedTool(CountDownLatch gate, CountDownLatch entered) {
            this.gate = gate;
            this.entered = entered;
        }

        @Override
        public String getName() {
            return "find_element";
        }

        @Override
        public String getDescription() {
            return "test-only slow tool";
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }

        @Override
        protected ToolResult executeInternal(Map<String, Object> parameters) {
            entered.countDown();
            try {
                gate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("slow-done");
        }
    }

    /** 反射取 server 单例的 ToolRegistry(慢工具注册用)。 */
    private static ToolRegistry serverToolRegistry() throws Exception {
        Field f = IpcServer.class.getDeclaredField("toolRegistry");
        f.setAccessible(true);
        return (ToolRegistry) f.get(IpcServer.getInstance());
    }

    private static IpcRequest agentRequest(String message, String session) {
        IpcRequest req = new IpcRequest();
        req.setOp("agent");
        req.setMessage(message);
        req.setSession(session);
        return req;
    }

    private static CompletableFuture<HttpResponse<String>> postAsync(String endpoint, IpcRequest req) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new IpcClient("127.0.0.1", IpcServer.getInstance().getPort(), tokenInEffect)
                        .post(endpoint, req, 30_000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static HttpResponse<String> healthGet() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + IpcServer.getInstance().getPort() + "/health"))
                .header("X-IPC-Token", tokenInEffect)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void ensureJMeterProps() throws Exception {
        Field f = JMeterUtils.class.getDeclaredField("appProperties");
        f.setAccessible(true);
        if (f.get(null) == null) {
            f.set(null, new Properties());
        }
    }

    private static String readExpectedToken() throws Exception {
        Field f = IpcServer.class.getDeclaredField("expectedToken");
        f.setAccessible(true);
        return (String) f.get(IpcServer.getInstance());
    }

    private static Object swapFactoryInstance(Object value) {
        try {
            Field field = AgentLoopFactory.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object previous = field.get(null);
            field.set(null, value);
            return previous;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
