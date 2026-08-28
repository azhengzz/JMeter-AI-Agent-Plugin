package org.gitee.jmeter.ai.agent.swing;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * SwingWorker for executing Agent Loop operations in the background.
 * Provides typed progress updates to the UI during execution.
 */
public class AgentSwingWorker extends SwingWorker<AgentResponse, ProgressUpdate> {
    private static final Logger log = LoggerFactory.getLogger(AgentSwingWorker.class);

    private final AgentLoop agentLoop;
    private final String message;
    private final String sessionKey;
    private final Consumer<AgentResponse> callback;
    private final Consumer<ProgressUpdate> progressCallback;

    /**
     * Create an AgentSwingWorker
     */
    public AgentSwingWorker(
            AgentLoop agentLoop,
            String message,
            String sessionKey,
            Consumer<AgentResponse> callback) {
        this(agentLoop, message, sessionKey, callback, null);
    }

    /**
     * Create an AgentSwingWorker with progress callback
     */
    public AgentSwingWorker(
            AgentLoop agentLoop,
            String message,
            String sessionKey,
            Consumer<AgentResponse> callback,
            Consumer<ProgressUpdate> progressCallback) {
        this.agentLoop = agentLoop;
        this.message = message;
        this.sessionKey = sessionKey;
        this.callback = callback;
        this.progressCallback = progressCallback;
    }

    /**
     * SwingWorker 框架回调，无业务代码直接调用。
     * 完整调用链：
     * <pre>
     * AiChatPanel.startNormalSend()                // EDT
     *   └─ new AgentSwingWorker(...)
     *   └─ activeWorker.execute()                  // 立即返回，不阻塞 EDT
     *       └─ SwingWorker 内部线程池
     *           └─ doInBackground()                // 本方法，后台线程
     *               └─ agentLoop.processMessage(...)
     * 执行中 publish(update) → EDT 上回调 process()；结束 → EDT 上回调 done()。
     * </pre>
     */
    @Override
    protected AgentResponse doInBackground() throws Exception {
        log.info("Starting AgentSwingWorker for session: {}", sessionKey);

        // Per-turn callback binding: ProgressUpdate flows through SwingWorker's publish
        // 只绑定本回合（3 参 processMessage），不再写 loop 级残留字段——否则 GUI 发过
        // 消息后，经 IPC 到达的回合会把工具事件漏进旧 worker（半状态缺陷）。
        AgentResponse response = agentLoop
                .processMessage(message, sessionKey, update -> publish(update)).get();

        log.info("AgentSwingWorker completed for session: {}", sessionKey);
        return response;
    }

    /**
     * SwingWorker 框架回调，无业务代码直接调用。
     * 完整调用链：
     * <pre>
     * AgentLoop 工具事件/进度（后台线程）
     *   └─ update -> publish(update)               // doInBackground 传入 processMessage 的 lambda
     *       └─ SwingWorker 内部聚合队列（多次 publish 合并为一批 chunks）
     *           └─ EDT 上回调 process(chunks)      // 本方法
     *               └─ progressCallback.accept(update)
     *                   └─ AiChatPanel.handleProgress(u, generation)  // 渲染进度 + 代数过滤
     * </pre>
     */
    @Override
    protected void process(List<ProgressUpdate> chunks) {
        for (ProgressUpdate update : chunks) {
            try {
                if (progressCallback != null) {
                    progressCallback.accept(update);
                }
            } catch (Exception e) {
                log.warn("Error in progress callback", e);
            }
        }
    }

    /**
     * SwingWorker 框架回调，无业务代码直接调用。
     * 完整调用链（doInBackground 到达终态后，框架在 EDT 上回调）：
     * <pre>
     * doInBackground() 结束（正常返回 / 抛异常 / Stop 按钮 stopActiveTask()
     * 调 activeWorker.cancel(true)）
     *   └─ EDT 上回调 done()                       // 本方法
     *       ├─ isCancelled()：Stop 快路径已处理显示，直接返回
     *       ├─ get() 取结果 → callback.accept(response)
     *       │     └─ AiChatPanel.handleAgentResponse(r, generation)
     *       │           // 渲染最终回复、复位按钮、activeWorker 置 null
     *       └─ 异常 → callback.accept(AgentResponse.error(...))  // 同走 handleAgentResponse
     * </pre>
     */
    @Override
    protected void done() {
        // If cancelled, the UI fast-path already handled display — skip callback
        if (isCancelled()) {
            log.info("AgentSwingWorker was cancelled");
            return;
        }
        try {
            AgentResponse response = get();
            if (callback != null) {
                callback.accept(response);
            }
        } catch (Exception e) {
            // The run may have been cancelled via AgentLoop.signalCancel (e.g. /new
            // typed mid-run), which cancels the inner CompletableFuture — not the
            // SwingWorker itself, so isCancelled() stays false. It surfaces here as
            // an ExecutionException whose cause is a CancellationException. That is
            // benign, not a real failure; the command path (e.g. injectMessage)
            // owns the UI cleanup.
            if (e instanceof ExecutionException && e.getCause() instanceof CancellationException) {
                log.info("AgentSwingWorker run cancelled (inner future cancelled)");
                return;
            }
            log.error("Error in AgentSwingWorker", e);
            if (callback != null) {
                callback.accept(AgentResponse.error("Processing failed: " + e.getMessage()));
            }
        }
    }
}
