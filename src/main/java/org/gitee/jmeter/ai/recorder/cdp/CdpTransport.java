package org.gitee.jmeter.ai.recorder.cdp;

/**
 * CDP 底层传输抽象（真实 WebSocket 与测试 fake 的接缝）。
 * 侦听器回调线程不保证；文本以完整消息为单位递交（分片由实现负责拼装）。
 */
public interface CdpTransport {

    /** 建立连接；失败抛 IOException（同步语义，调用方在打开会话的线程上等待）。 */
    void connect(String wsUrl) throws Exception;

    /** 发送一条完整文本消息（实现须保证线程安全与发送串行）。 */
    void send(String text);

    /** 关闭连接（幂等）。 */
    void close();

    boolean isOpen();

    void setListener(Listener listener);

    interface Listener {
        void onText(String message);

        void onClosed();
    }
}
