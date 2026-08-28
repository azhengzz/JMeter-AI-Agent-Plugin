package org.gitee.jmeter.ai.cli;

import org.gitee.jmeter.ai.ipc.protocol.IpcResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JmeterCli#printCancelled} / {@code printResp} 对取消载荷的识别:
 * 正常成功、被目标用户终止(附部分内容)、超时自取消三分支。
 */
class JmeterCliPrintCancelledTest {

    @Test
    void normalSuccessPrintsContentAndExitsZero() throws Exception {
        CapturedIO io = capture();
        int code;
        try {
            IpcResponse ok = new IpcResponse();
            ok.setSuccess(true);
            ok.setContent("final answer");
            code = JmeterCli.printResp(fakeResponse(200, ok), false);
        } finally {
            io.restore();
        }
        assertEquals(0, code);
        assertTrue(io.out().contains("final answer"));
        assertTrue(io.err().isEmpty(), "no error expected: " + io.err());
    }

    @Test
    void userStopPrintsReasonAndPartialContent() throws Exception {
        CapturedIO io = capture();
        int code;
        try {
            IpcResponse cancelled = new IpcResponse();
            cancelled.setSuccess(false);
            cancelled.setError("turn cancelled before completion");
            cancelled.setCancelled(true);
            cancelled.setCancelReason(IpcResponse.CANCEL_REASON_USER_STOP);
            cancelled.setPartialContent("已产生的部分回复");
            code = JmeterCli.printResp(fakeResponse(409, cancelled), false);
        } finally {
            io.restore();
        }
        assertEquals(1, code);
        assertTrue(io.err().contains("cancelled by the target instance user"), io.err());
        assertTrue(io.err().contains("(HTTP 409)"), io.err());
        assertTrue(io.out().contains("已产生的部分回复"), io.out());
        assertTrue(io.out().contains("partial reply"), io.out());
    }

    @Test
    void timeoutPrintsReasonWithoutPartialSection() throws Exception {
        CapturedIO io = capture();
        int code;
        try {
            IpcResponse cancelled = new IpcResponse();
            cancelled.setSuccess(false);
            cancelled.setError("agent timeout after 120000ms (turn cancelled)");
            cancelled.setCancelled(true);
            cancelled.setCancelReason(IpcResponse.CANCEL_REASON_TIMEOUT);
            code = JmeterCli.printCancelled(cancelled, 504);
        } finally {
            io.restore();
        }
        assertEquals(1, code);
        assertTrue(io.err().contains("timed out"), io.err());
        assertTrue(io.err().contains("(HTTP 504)"), io.err());
        assertFalse(io.out().contains("partial reply"), "no partial section when nothing accumulated: " + io.out());
    }

    // ---------- helpers ----------

    private static HttpResponse<String> fakeResponse(int status, IpcResponse body) throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public String body() {
                return json;
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }

            @Override
            public java.net.URI uri() {
                return java.net.URI.create("http://127.0.0.1/agent");
            }

            @Override
            public java.net.http.HttpRequest request() {
                return java.net.http.HttpRequest.newBuilder(uri()).build();
            }

            @Override
            public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
            }

            @Override
            public java.util.Optional<HttpResponse<String>> previousResponse() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                return java.util.Optional.empty();
            }
        };
    }

    private static CapturedIO capture() {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        return new CapturedIO(out, err, origOut, origErr);
    }

    private record CapturedIO(ByteArrayOutputStream outBuf, ByteArrayOutputStream errBuf,
                              PrintStream origOut, PrintStream origErr) {
        String out() {
            return outBuf.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return errBuf.toString(StandardCharsets.UTF_8);
        }

        void restore() {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }
}
