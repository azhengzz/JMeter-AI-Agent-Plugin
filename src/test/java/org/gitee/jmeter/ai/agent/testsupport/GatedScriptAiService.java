package org.gitee.jmeter.ai.agent.testsupport;

import org.gitee.jmeter.ai.agent.model.GenerationSettings;
import org.gitee.jmeter.ai.agent.model.LLMResponse;
import org.gitee.jmeter.ai.agent.model.LlmCallOptions;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.model.StreamDeltaListener;
import org.gitee.jmeter.ai.agent.model.ToolDefinition;
import org.gitee.jmeter.ai.service.AiService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * 脚本化 fake AiService：立即应答队列 + 门控调用（{@link GatedCall}）+ 可整体替换的
 * busy 覆写（{@link #override}）。门控调用被 interrupt 后的行为由
 * {@link InterruptStrategy} 决定。
 */
public final class GatedScriptAiService implements AiService {

    /** 门控等待被 interrupt 后的行为策略。 */
    public enum InterruptStrategy {
        /** 恢复中断标记、照常返回脚本响应（普通门控默认）。 */
        RETURN_SCRIPTED,
        /**
         * Stop/Reset 确定性钉子：interrupt 后计数 {@link GatedCall#interrupted}、改挂
         * {@link GatedCall#hang}——signalCancel 得以在回合体到达终态发射点之前完成
         * 认领；测试断言 TURN_CANCELLED 已到、再放行 hang 验证「终态恰好一次」。
         */
        HANG_UNTIL_RELEASED
    }

    private final ConcurrentLinkedQueue<Object> calls = new ConcurrentLinkedQueue<>();
    private final InterruptStrategy interruptStrategy;

    /** 整体覆写（busy 场景：首个调用挂起直至测试放行，绕过脚本队列）。 */
    public volatile Supplier<LLMResponse> override;

    public GatedScriptAiService() {
        this(InterruptStrategy.RETURN_SCRIPTED);
    }

    public GatedScriptAiService(InterruptStrategy interruptStrategy) {
        this.interruptStrategy = interruptStrategy;
    }

    public void script(LLMResponse response) {
        calls.add(response);
    }

    public GatedCall scriptGated(LLMResponse response) {
        GatedCall call = new GatedCall(response);
        calls.add(call);
        return call;
    }

    /**
     * 流式脚本调用：向 options 的 delta 消费者逐片发射（模拟流式 provider——含
     * 片间 abort 轮询与中止分类），发射完毕返回完整响应（消费者在场时自动置
     * streamed=true，对齐真实 provider）。捕获的消费者引用暴露给测试（迟到
     * delta / 任意线程发射）。
     */
    public StreamScriptCall scriptStreamed(LLMResponse response) {
        StreamScriptCall call = new StreamScriptCall(response);
        calls.add(call);
        return call;
    }

    @Override
    public LLMResponse generateResponseWithTools(
            List<Message> messages, List<ToolDefinition> tools, LlmCallOptions options) {
        Supplier<LLMResponse> o = override;
        if (o != null) {
            return o.get();
        }
        Object next = calls.poll();
        if (next instanceof StreamScriptCall streamCall) {
            StreamDeltaListener listener = options != null ? options.getStreamDeltaListener() : null;
            streamCall.capturedListener = listener;
            if (listener == null) {
                return streamCall.response;
            }
            java.util.function.BooleanSupplier abort = options.getAbortCheck();
            for (int i = 0; i < streamCall.fragments.size(); i++) {
                if (i > 0) {
                    Runnable mid = streamCall.midFragmentAction;
                    if (mid != null) {
                        mid.run();
                    }
                }
                if (abort != null && abort.getAsBoolean()) {
                    // 对齐真实流式 provider 的 chunk 间中止分类
                    return LLMResponse.error("Interrupted", false, null, "aborted");
                }
                StreamScriptCall.Fragment fragment = streamCall.fragments.get(i);
                if (fragment.reasoning()) {
                    listener.onReasoningDelta(fragment.text());
                } else {
                    listener.onContentDelta(fragment.text());
                }
            }
            return streamCall.response.withStreamed(true);
        }
        if (next instanceof GatedCall call) {
            call.entered.countDown();
            try {
                call.release.await();
            } catch (InterruptedException e) {
                if (interruptStrategy == InterruptStrategy.HANG_UNTIL_RELEASED) {
                    call.interrupted.countDown();
                    try {
                        call.hang.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    Thread.currentThread().interrupt();
                }
            }
            return call.response;
        }
        return next != null ? (LLMResponse) next : LLMResponse.text("DEFAULT-FINAL");
    }

    @Override public String getName() {
        return "gated-script-fake";
    }

    @Override public GenerationSettings getGenerationSettings() {
        return new GenerationSettings(0.7, 4096, "medium");
    }

    @Override public void setGenerationSettings(GenerationSettings settings) { }

    @Override public boolean supportsToolCalling() {
        return true;
    }

    /** 流式脚本调用：片段序列（content/reasoning 原始分片）+ 片间动作 + 捕获的消费者。 */
    public static final class StreamScriptCall {
        final List<Fragment> fragments = new ArrayList<>();
        volatile Runnable midFragmentAction;
        public final LLMResponse response;
        /** 发射时捕获的 delta 消费者（无消费者为 null）——迟到 delta 测试入口。 */
        public volatile StreamDeltaListener capturedListener;

        StreamScriptCall(LLMResponse response) {
            this.response = response;
        }

        public StreamScriptCall content(String text) {
            fragments.add(new Fragment(false, text));
            return this;
        }

        public StreamScriptCall reasoning(String text) {
            fragments.add(new Fragment(true, text));
            return this;
        }

        /** 片间动作（第 2 片起、abort 轮询后执行）：供测试置 abort flag 模拟流中 Stop。 */
        public StreamScriptCall midFragment(Runnable action) {
            this.midFragmentAction = action;
            return this;
        }

        record Fragment(boolean reasoning, String text) {}
    }

    /** 门控调用：entered/release 双锁；HANG_UNTIL_RELEASED 策略下另挂 interrupted/hang。 */
    public static final class GatedCall {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
        public final LLMResponse response;
        /** 仅 HANG_UNTIL_RELEASED：门控等待被 interrupt 时计数。 */
        public final CountDownLatch interrupted = new CountDownLatch(1);
        /** 仅 HANG_UNTIL_RELEASED：interrupt 后改挂此锁，测试放行后回合体才收尾。 */
        public final CountDownLatch hang = new CountDownLatch(1);

        GatedCall(LLMResponse response) {
            this.response = response;
        }
    }
}
