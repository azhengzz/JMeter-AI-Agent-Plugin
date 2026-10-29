package org.gitee.jmeter.ai.recorder.cdp;

import org.gitee.jmeter.ai.recorder.testsupport.FakeTransport;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对抗性测试（CDP 层）：消息层垃圾输入、孤儿响应、外来 binding、非页面 target、
 * 端点规范化敌意形态。
 */
class AdversarialCdpTest {

    @Test
    void connectionSurvivesGarbageAndOrphanResponses() throws Exception {
        FakeTransport t = new FakeTransport();
        t.responder = msg -> Optional.empty();
        CdpConnection c = new CdpConnection(t);
        t.connect("ws://fake");
        StringBuilder log = new StringBuilder();
        c.setEventListener(new CdpConnection.EventListener() {
            @Override
            public void onEvent(String method, com.fasterxml.jackson.databind.JsonNode p, String s) {
                log.append(method).append(";");
            }

            @Override
            public void onTransportClosed() {
            }
        });
        // 非法 JSON / null / 纯数字 / 无 method 节点 / 孤儿响应 / 未知事件
        t.receive("not json {");
        t.receive("null");
        t.receive("42");
        t.receive("{\"id\":1,\"result\":{}}");
        t.receive("{\"params\":{}}");
        t.receive("{\"method\":\"Inspector.detached\",\"params\":{}}");
        assertEquals("Inspector.detached;", log.toString());
        assertTrue(c.isTransportOpen());
    }

    @Test
    void clientIgnoresForeignBindingsAndNonPageTargets() {
        FakeTransport t = new FakeTransport();
        CdpConnection conn = new CdpConnection(t);
        final int[] bindings = {0};
        CdpClient client = new CdpClient(conn, new CdpClient.Callbacks() {
            @Override
            public void onBindingCalled(int pageId, String sessionId, String payload) {
                bindings[0]++;
            }
        }, "// init");
        t.connect("ws://fake");
        t.receive("{\"method\":\"Target.attachedToTarget\",\"params\":{"
                + "\"sessionId\":\"S1\",\"targetInfo\":{\"type\":\"service_worker\",\"url\":\"x\"}}}");
        t.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"otherTool\",\"payload\":\"{}\"}}");
        t.receive("{\"method\":\"Runtime.bindingCalled\",\"sessionId\":\"S1\",\"params\":{"
                + "\"name\":\"__jaiRec\",\"payload\":\"{\\\"t\\\":\\\"ctrl\\\",\\\"a\\\":\\\"bogus\\\"}\"}}");
        assertEquals(1, bindings[0]); // 只透传本脚本 binding（未知 ctrl 动作由引擎忽略）
        assertEquals(0, client.attachedPageCount());
    }

    @Test
    void normalizeHostileCaseAndShapes() {
        assertEquals("HTTP://Host:81/json/version", DevToolsHttp.normalize("HTTP://Host:81"));
        assertEquals("HTTPS://h/json/version", DevToolsHttp.normalize("HTTPS://h"));
        assertEquals("http://127.0.0.1:9222/json/version", DevToolsHttp.normalize(" 9222 "));
        assertEquals("http://127.0.0.1:9222/json/version", DevToolsHttp.normalize("127.0.0.1:9222///"));
    }
}
