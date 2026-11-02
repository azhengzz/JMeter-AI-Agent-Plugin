package org.gitee.jmeter.ai.agent.tools.exec;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 子进程输出解码启发式回归:严格 UTF-8 优先、失败回退给定编码。回退编码显式
 * 注入 GBK——断言不依赖宿主 JVM 的 default charset(否则测试只在中文 Windows
 * 上绿,UTF-8 缺省的 JVM 上会假红)。
 */
class ExecToolDecodeTest {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void utf8Output_decodesStrictlyWithoutFallback() {
        byte[] bytes = "已发送 = 345，已接收 = 345".getBytes(StandardCharsets.UTF_8);
        assertEquals("已发送 = 345，已接收 = 345", ExecTool.decodeProcessOutput(bytes, GBK));
    }

    @Test
    void gbkConsoleOutput_fallsBackToFallbackCharset() {
        // cmd /c ping 等 Windows 控制台命令的管道输出:GBK 字节在 UTF-8 严格
        // 解码下必失败(已 = D2 D1,首字节是 UTF-8 双字节前导但尾字节 > 0xBF)
        byte[] bytes = "已发送 = 345，丢失 = 0 (0% 丢失)".getBytes(GBK);
        assertEquals("已发送 = 345，丢失 = 0 (0% 丢失)", ExecTool.decodeProcessOutput(bytes, GBK));
    }

    @Test
    void asciiOutput_isEncodingAgnostic() {
        byte[] bytes = "Packets: Sent = 345, Received = 345, Lost = 0 (0% loss)"
                .getBytes(StandardCharsets.US_ASCII);
        assertEquals("Packets: Sent = 345, Received = 345, Lost = 0 (0% loss)",
                ExecTool.decodeProcessOutput(bytes, GBK));
    }

    @Test
    void emptyOutput_decodesToEmptyString() {
        assertEquals("", ExecTool.decodeProcessOutput(new byte[0], GBK));
    }
}
