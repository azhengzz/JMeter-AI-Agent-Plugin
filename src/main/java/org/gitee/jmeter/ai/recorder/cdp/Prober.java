package org.gitee.jmeter.ai.recorder.cdp;

import java.util.Optional;

/**
 * DevTools 端点探活接缝（真实实现 {@link DevToolsHttp}；测试注入 fake，避免真实网络）。
 */
public interface Prober {
    /** @return 浏览器级 webSocketDebuggerUrl；不可达返回 empty。 */
    Optional<String> probeWebSocketUrl(String endpoint);
}
