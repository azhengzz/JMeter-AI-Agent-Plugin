package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.gitee.jmeter.ai.ipc.protocol.IpcRequest;
import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IpcClient} 的传输正确性与复用性。用一个 loopback {@link HttpServer} 捕获实际发出的
 * token header / JSON body 并回 canned {@link IpcResponse}。无 JMeter 依赖。
 */
class IpcClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private final AtomicReference<String> capturedToken = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        hits.incrementAndGet();
        capturedToken.set(ex.getRequestHeaders().getFirst("X-IPC-Token"));
        capturedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        IpcResponse resp = new IpcResponse();
        resp.setSuccess(true);
        resp.setContent("pong:" + hits.get());
        byte[] out = MAPPER.writeValueAsBytes(resp);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    @Test
    void postSendsTokenHeaderAndJsonBody() throws Exception {
        IpcClient client = new IpcClient("127.0.0.1", port, "secret-token");
        IpcRequest req = new IpcRequest();
        req.setOp("agent");
        req.setMessage("hi");

        HttpResponse<String> resp = client.post("/agent", req, 5000);

        assertEquals(200, resp.statusCode());
        assertEquals("secret-token", capturedToken.get(), "X-IPC-Token header must be sent");
        IpcRequest seen = MAPPER.readValue(capturedBody.get(), IpcRequest.class);
        assertEquals("agent", seen.getOp());
        assertEquals("hi", seen.getMessage());
    }

    @Test
    void nullHostDefaultsToLoopback() throws Exception {
        IpcClient client = new IpcClient(null, port, "t");
        IpcResponse resp = client.postAgent("hello", null, 5000);
        assertTrue(resp.isSuccess());
        assertEquals("pong:1", resp.getContent());
    }

    @Test
    void clientIsReusableAcrossCalls() throws Exception {
        IpcClient client = new IpcClient("", port, "t"); // empty host also defaults to 127.0.0.1

        IpcResponse r1 = client.postAgent("first", null, 5000);
        IpcResponse r2 = client.postAgent("second", "sess", 5000);

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertEquals(2, hits.get(), "the same client instance must be reusable for multiple calls");
        IpcRequest seen = MAPPER.readValue(capturedBody.get(), IpcRequest.class);
        assertEquals("sess", seen.getSession(), "postAgent must propagate the session param");
    }
}
