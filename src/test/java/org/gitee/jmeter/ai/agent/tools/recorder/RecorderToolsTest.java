package org.gitee.jmeter.ai.agent.tools.recorder;

import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.recorder.RecorderConfig;
import org.gitee.jmeter.ai.recorder.RecorderEngine;
import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务 5.1：四工具的参数校验、单会话互斥、既有会话接管与状态机驱动
 * （全栈走 FakeTransport，与 GUI 共用引擎语义）。
 */
class RecorderToolsTest {

    @TempDir
    Path workspace;

    private FakeTransport transport;
    private RecorderEngine engine;

    private RecorderEngine newEngine() {
        RecorderConfig cfg = RecorderConfig.from(k -> null);
        transport = new FakeTransport();
        RecorderEngine.BrowserProvider provider = (c, ws, url) -> new BrowserInstanceFinder.BrowserHandle() {
            @Override
            public String wsUrl() {
                return "ws://fake";
            }

            @Override
            public boolean own() {
                return true;
            }

            @Override
            public String fallbackReason() {
                return null;
            }

            @Override
            public void destroy() {
            }
        };
        engine = new RecorderEngine(cfg, new RecorderEngine.EngineListener() {
        }, workspace, "test-inst", provider, () -> transport, "// init");
        return engine;
    }

    private void attachPage() {
        transport.receive("{\"method\":\"Target.attachedToTarget\",\"params\":{"
                + "\"sessionId\":\"S1\",\"targetInfo\":{\"type\":\"page\",\"url\":\"about:blank\"}}}");
    }

    @Test
    void openBeginStopStatusLifecycle() throws Exception {
        RecorderEngine e = newEngine();
        OpenRecordingBrowserTool open = new OpenRecordingBrowserTool(() -> e);
        BeginRecordingTool begin = new BeginRecordingTool(() -> e);
        StopRecordingTool stop = new StopRecordingTool(() -> e);
        GetRecordingStatusTool status = new GetRecordingStatusTool(() -> e);

        assertTrue(open.execute(Map.of()).isSuccess());
        attachPage();
        assertEquals(RecorderEngine.State.ARMED, e.state());

        assertTrue(begin.execute(Map.of()).isSuccess());
        assertEquals(RecorderEngine.State.RECORDING, e.state());
        transport.receive("{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"R1\",\"type\":\"XHR\","
                + "\"request\":{\"method\":\"GET\",\"url\":\"https://a.io/x\",\"headers\":{}}}}");

        assertTrue(stop.execute(Map.of()).isSuccess());
        assertEquals(RecorderEngine.State.DONE, e.state());

        ToolResult st = status.execute(Map.of());
        assertTrue(st.isSuccess());
        assertTrue(st.getContent().contains("\"state\":\"DONE\""),
                "status output: " + st.getContent());
    }

    @Test
    void openRejectsWhenSessionActive() throws Exception {
        RecorderEngine e = newEngine();
        OpenRecordingBrowserTool open = new OpenRecordingBrowserTool(() -> e);
        assertTrue(open.execute(Map.of()).isSuccess());
        ToolResult second = open.execute(Map.of());
        assertFalse(second.isSuccess());
        assertTrue(second.getError().contains("already in progress"));
    }

    @Test
    void beginTakesOverGuiOpenedArmedSession() throws Exception {
        // GUI（面板按钮）打开的预备态会话，工具直接接管开始（同一引擎单会话）
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage();
        BeginRecordingTool begin = new BeginRecordingTool(() -> e);
        assertTrue(begin.execute(Map.of()).isSuccess());
        assertEquals(RecorderEngine.State.RECORDING, e.state());
    }

    @Test
    void beginWithoutSessionFails() {
        RecorderEngine e = newEngine();
        ToolResult r = new BeginRecordingTool(() -> e).execute(Map.of());
        assertFalse(r.isSuccess());
    }

    @Test
    void beginWhileRecordingFails() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        e.begin();
        ToolResult r = new BeginRecordingTool(() -> e).execute(Map.of());
        assertFalse(r.isSuccess());
    }

    @Test
    void stopInArmedCancels() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        ToolResult r = new StopRecordingTool(() -> e).execute(Map.of());
        assertTrue(r.isSuccess());
        assertEquals(RecorderEngine.State.CANCELLED, e.state());
    }

    @Test
    void stopWithoutSessionFails() {
        RecorderEngine e = newEngine();
        assertFalse(new StopRecordingTool(() -> e).execute(Map.of()).isSuccess());
    }

    @Test
    void openParsesDomainsAndUrl() throws Exception {
        RecorderEngine e = newEngine();
        OpenRecordingBrowserTool open = new OpenRecordingBrowserTool(() -> e);
        assertTrue(open.execute(Map.of(
                "url", "  https://example.com/ops ",
                "include_domains", List.of(" example.com ", "", "api.example.com"))).isSuccess());
        // 校验过滤器已带上域名（域名外请求不落盘）：手动推事件验证
        attachPage();
        e.begin();
        transport.receive("{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"R1\",\"type\":\"XHR\","
                + "\"request\":{\"method\":\"GET\",\"url\":\"https://other.io/x\",\"headers\":{}}}}");
        assertEquals(0, e.status().requests());
    }

    @Test
    void openAcceptsCommaSeparatedDomainString() {
        assertEquals(Set.of("a.com", "b.com"),
                OpenRecordingBrowserTool.parseDomains(" a.com , b.com "));
        assertEquals(Set.of("a.com"), OpenRecordingBrowserTool.parseDomains(List.of("a.com", "")));
        assertThrows(IllegalArgumentException.class,
                () -> OpenRecordingBrowserTool.parseDomains(42));
    }

    @Test
    void goalTurnCannotOpenBeginOrStop() {
        RecorderEngine e = newEngine();
        org.gitee.jmeter.ai.agent.run.AgentRunContext.set(
                new org.gitee.jmeter.ai.agent.run.AgentRunContext("gk", "run-1", true));
        try {
            assertFalse(new OpenRecordingBrowserTool(() -> e).execute(Map.of()).isSuccess());
            assertFalse(new BeginRecordingTool(() -> e).execute(Map.of()).isSuccess());
            // stop 会真实终止用户 GUI 会话：同样硬阻断（只读 status 保留）
            assertFalse(new StopRecordingTool(() -> e).execute(Map.of()).isSuccess());
            assertEquals(RecorderEngine.State.IDLE, e.state());
            assertTrue(new GetRecordingStatusTool(() -> e).execute(Map.of()).isSuccess());
        } finally {
            org.gitee.jmeter.ai.agent.run.AgentRunContext.clear();
        }
    }

    @Test
    void statusToolIsConcurrencySafeAndNamed() {
        GetRecordingStatusTool status = new GetRecordingStatusTool(() -> newEngine());
        assertEquals("get_recording_status", status.getName());
        assertTrue(status.isConcurrencySafe());
        assertFalse(new OpenRecordingBrowserTool(() -> newEngine()).isConcurrencySafe());
    }
}
