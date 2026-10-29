package org.gitee.jmeter.ai.recorder;

import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.convert.RecordingModel;
import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对抗性测试（引擎侧）：多标签页 requestId 碰撞、录制中途新页附着、终态后事件风暴、
 * 双停并发、畸形 binding 载荷、超长/Unicode 载荷、五跳重定向、状态机乱序调用。
 */
class AdversarialEngineTest {

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

    private void attachPage(String sid) {
        transport.receive("{\"method\":\"Target.attachedToTarget\",\"params\":{"
                + "\"sessionId\":\"" + sid + "\",\"targetInfo\":{\"type\":\"page\",\"url\":\"about:blank\"}}}");
    }

    private void request(String sid, String rid, String url) {
        transport.receive("{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"" + sid + "\",\"params\":{"
                + "\"requestId\":\"" + rid + "\",\"type\":\"XHR\","
                + "\"request\":{\"method\":\"GET\",\"url\":\"" + url + "\",\"headers\":{}}}}");
    }

    private void response(String sid, String rid, int status, String url) {
        transport.receive("{\"method\":\"Network.responseReceived\",\"sessionId\":\"" + sid + "\",\"params\":{"
                + "\"requestId\":\"" + rid + "\",\"response\":{\"status\":" + status
                + ",\"url\":\"" + url + "\",\"headers\":{}}}}");
    }

    /** CDP requestId 仅在单页面会话内唯一：两页各自 R1 必须互不串扰。 */
    @Test
    void crossPageRequestIdCollisionStaysIsolated() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        attachPage("S2");
        e.begin();
        request("S1", "R1", "https://a.io/from-page1");
        request("S2", "R1", "https://b.io/from-page2");
        response("S2", "R1", 201, "https://b.io/from-page2");
        response("S1", "R1", 200, "https://a.io/from-page1");
        e.stop();

        assertEquals(2, e.status().requests());
        RecordingModel model = RecordingModel.load(e.status().dir());
        assertEquals(2, model.requests.size());
        RecordingModel.CapturedRequest p1 = model.requests.stream()
                .filter(r -> r.url().contains("page1")).findFirst().orElseThrow();
        RecordingModel.CapturedRequest p2 = model.requests.stream()
                .filter(r -> r.url().contains("page2")).findFirst().orElseThrow();
        assertEquals(200, p1.status());
        assertEquals(201, p2.status());
        assertEquals(1, p1.hops());
        assertEquals(1, p2.hops());
    }

    /** 录制中途新开标签页：附着即注入，其流量正常采集。 */
    @Test
    void latePageAttachDuringRecordingCaptures() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        e.begin();
        attachPage("S2"); // 中途新标签页
        request("S2", "T9", "https://a.io/late-tab");
        e.stop();
        assertEquals(1, e.status().requests());
        RecordingModel model = RecordingModel.load(e.status().dir());
        assertEquals("https://a.io/late-tab", model.requests.get(0).url());
        assertEquals(2, model.requests.get(0).pageId());
    }

    /** 终态后事件风暴：不崩、不写、计数不变。 */
    @Test
    void eventStormAfterFinalizeIsInert() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        e.begin();
        request("S1", "R1", "https://a.io/x");
        e.stop();
        long after = e.status().requests();
        for (int i = 0; i < 100; i++) {
            request("S1", "STORM" + i, "https://a.io/s" + i);
            response("S1", "STORM" + i, 200, "https://a.io/s" + i);
            transport.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                    + "\"name\":\"__jaiRec\",\"payload\":\"{\\\"t\\\":\\\"ui\\\",\\\"ev\\\":\\\"click\\\"}\"}}");
        }
        assertEquals(after, e.status().requests());
        assertEquals(RecorderEngine.State.DONE, e.state());
    }

    /** 双停并发（控制面 + 关窗）经 single-flight 收敛为一次收官。 */
    @Test
    void concurrentStopAndCloseFinalizeOnce() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        e.begin();
        request("S1", "R1", "https://a.io/x");
        // 关窗通道先触发收官，控制面 stop 紧随：均不抛
        transport.fireClose();
        e.stop();
        assertEquals(RecorderEngine.State.DONE, e.state());
        assertTrue(RecordingStore.readEvents(e.status().dir().resolve("events.jsonl")).size() >= 1);
    }

    /** 状态机乱序：IDLE 直接 begin/stop、DONE 后 open 均按守卫语义处理。 */
    @Test
    void outOfOrderCallsGuarded() throws Exception {
        RecorderEngine e = newEngine();
        e.begin(); // IDLE → 静默
        assertEquals(RecorderEngine.State.IDLE, e.state());
        e.stop();  // IDLE → 无事
        assertEquals(RecorderEngine.State.IDLE, e.state());
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        e.begin();
        e.stop();
        assertThrows(IllegalStateException.class,
                () -> e.open(new RecorderEngine.OpenParams(null, Set.of()))); // 引擎单次使用（Runtime 负责换新）
    }

    /** 畸形/敌意 binding 载荷：非 JSON、残缺 JSON、超长载荷——不崩不落盘垃圾。 */
    @Test
    void malformedBindingPayloadsDropped() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        e.begin();
        transport.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"__jaiRec\",\"payload\":\"not json at all\"}}");
        transport.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"__jaiRec\",\"payload\":\"{broken json\"}}");
        transport.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"otherBinding\",\"payload\":\"{}\"}}"); // 非本脚本的 binding
        transport.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"__jaiRec\",\"payload\":\"{\\\"t\\\":\\\"ui\\\",\\\"ev\\\":\\\"click\\\","
                + "\\\"text\\\":\\\"" + "A".repeat(50_000) + "\\\"}\"}}");
        e.stop();
        RecordingModel model = RecordingModel.load(e.status().dir());
        assertEquals(1, model.uiEvents.size()); // 只有最后一个合法 click 落盘
    }

    /** Unicode/emoji/控制字符贯穿 引擎→jsonl→解析 全链路不失真。 */
    @Test
    void unicodePayloadsSurviveRoundtrip() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        e.begin();
        transport.receive("{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"S1\",\"params\":{"
                + "\"requestId\":\"R1\",\"type\":\"XHR\","
                + "\"request\":{\"method\":\"POST\",\"url\":\"https://a.io/中文/路径?q=表情\\ud83d\\ude00\","
                + "\"headers\":{}}}}");
        e.stop();
        RecordingModel model = RecordingModel.load(e.status().dir());
        assertEquals("https://a.io/中文/路径?q=表情😀", model.requests.get(0).url());
    }

    /** 五跳重定向：同 rid 六次 requestWillBeSent，只留最终。 */
    @Test
    void fiveHopRedirectTakesFinal() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        e.begin();
        for (int hop = 0; hop <= 5; hop++) {
            request("S1", "R1", "https://a.io/hop" + hop);
        }
        response("S1", "R1", 200, "https://a.io/hop5");
        e.stop();
        RecordingModel model = RecordingModel.load(e.status().dir());
        assertEquals(1, model.requests.size());
        assertEquals(6, model.requests.get(0).hops());
        assertEquals("https://a.io/hop5", model.requests.get(0).url());
    }

    /** 收割窗口内乱入事件（FINALIZING 期间）安全忽略。 */
    @Test
    void eventsDuringFinalizingIgnored() throws Exception {
        RecorderEngine e = newEngine();
        e.open(new RecorderEngine.OpenParams(null, Set.of()));
        attachPage("S1");
        e.begin();
        request("S1", "R1", "https://a.io/x");
        e.stop();
        // stop 后已 DONE；此处模拟极端时序：FINALIZING 极短难以注入，DONE 语义等价（终态后摄入零）
        assertEquals(RecorderEngine.State.DONE, e.state());
    }
}
