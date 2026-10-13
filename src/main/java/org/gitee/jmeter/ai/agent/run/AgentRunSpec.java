package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.hooks.AgentHook;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.gitee.jmeter.ai.agent.turn.InjectionItem;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Specification for running an agent.
 * Encapsulates all configuration needed for an agent run.
 */
public class AgentRunSpec {

    /**
     * Session key prefix marking an ephemeral subagent run.
     * Runs using this prefix must be fully isolated: no session persistence,
     * no memory consolidation, and no mid-turn injection of their own.
     */
    public static final String SUBAGENT_SESSION_PREFIX = "subagent:";

    private final String userMessage;
    private final String sessionKey;
    private final AgentHook hook;
    private final int maxIterations;
    private final boolean failOnToolError;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;
    private final String reasoningEffort;
    private final List<Message> initialMessages;
    private final AtomicBoolean abortFlag;
    private final Function<Integer, List<InjectionItem>> injectionCallback;
    private final boolean persistSession;
    private final boolean delegated;
    private final List<InstanceInfo> instanceMentions;
    private final Supplier<Long> resetEpochSupplier;
    private final boolean goalRequested;
    private final boolean hostSyntheticTrigger;
    private final Long turnId;
    private final org.gitee.jmeter.ai.agent.presenter.TurnOrigin origin;

    private AgentRunSpec(Builder builder) {
        this.userMessage = builder.userMessage;
        this.sessionKey = builder.sessionKey;
        this.hook = builder.hook;
        this.maxIterations = builder.maxIterations;
        this.failOnToolError = builder.failOnToolError;
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.reasoningEffort = builder.reasoningEffort;
        this.initialMessages = builder.initialMessages;
        this.abortFlag = builder.abortFlag;
        this.injectionCallback = builder.injectionCallback;
        this.persistSession = builder.persistSession;
        this.delegated = builder.delegated;
        this.instanceMentions = builder.instanceMentions;
        this.resetEpochSupplier = builder.resetEpochSupplier;
        this.goalRequested = builder.goalRequested;
        this.hostSyntheticTrigger = builder.hostSyntheticTrigger;
        this.turnId = builder.turnId;
        this.origin = builder.origin;
    }

    public String getUserMessage() { return userMessage; }
    public String getSessionKey() { return sessionKey; }
    public AgentHook getHook() { return hook; }
    public int getMaxIterations() { return maxIterations; }
    public boolean isFailOnToolError() { return failOnToolError; }
    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public String getReasoningEffort() { return reasoningEffort; }
    public List<Message> getInitialMessages() { return initialMessages; }
    public AtomicBoolean getAbortFlag() { return abortFlag; }
    public Function<Integer, List<InjectionItem>> getInjectionCallback() { return injectionCallback; }

    /**
     * Whether this run persists its messages to the session store and runs memory
     * consolidation. Subagent runs set this to false for complete isolation.
     */
    public boolean isPersistSession() { return persistSession; }

    /**
     * Whether this run executes a cross-instance delegated task (IPC {@code /agent}
     * with {@code delegated=true}). The runner arms {@code DelegationGuard} inside
     * the run task so tools executed in this turn refuse to delegate again.
     */
    public boolean isDelegated() { return delegated; }

    /**
     * Peer JMeter instances the user @-mentioned in this turn's message
     * (structured references rendered into the per-turn runtime context).
     * Empty list when the turn carries no mentions.
     */
    public List<InstanceInfo> getInstanceMentions() { return instanceMentions; }

    /**
     * 会话重置代数的活引用（{@code () -> currentEpoch(sessionKey)} 闭包，由
     * AgentLoop.startTurn 接线）。epoch 翻转 ⟺ 会话被重置（markConversationReset
     * 在栅栏锁下先翻代数再清空）——中止落盘/悬空尾懒收尾以「代数未翻转」为 RESET
     * 判别（对齐 republishLeftovers 的既有纪律），取代 CancelCause：cause 会被
     * signalCancel 的 abortVisible 守卫吞掉（Stop-后-//new 序列），epoch 不可被
     * 取消时序欺骗。null（子代理/直构测试）= 不判重置，中止落盘不执行。
     */
    public Supplier<Long> getResetEpochSupplier() { return resetEpochSupplier; }

    /**
     * 本回合由 {@code /goal <objective>} 触发（命令处理器打标）。开启 goal 变更
     * 权限（create_goal / update_goal replace 仅此类回合可用），并把 goal 请求态
     * 注入 Runtime Context。委派回合恒为 false。
     */
    public boolean isGoalRequested() { return goalRequested; }

    /**
     * 本回合的触发消息是宿主合成的（goal 续跑/交回投放的切片，非真实用户输入）。
     * 早落盘据此挂宿主合成标记，记忆/归档的公共视图不将其呈现为用户话语。
     */
    public boolean isHostSyntheticTrigger() { return hostSyntheticTrigger; }

    /**
     * 回合身份（进程唯一句柄 id），由 {@code AgentLoop.startTurn} 从 {@code TurnHandle}
     * 填入。null（子代理 / 直构测试运行）= 无回合身份，回合快照据此整体旁路。
     */
    public Long getTurnId() { return turnId; }

    /**
     * 回合来源（local/cli/delegated/republish），快照文件名的来源段。
     * null 与 {@link #getTurnId()} 同义：不参与回合快照。
     */
    public org.gitee.jmeter.ai.agent.presenter.TurnOrigin getOrigin() { return origin; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String userMessage;
        private String sessionKey;
        private AgentHook hook;
        private int maxIterations = 40;
        private boolean failOnToolError = false;
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String reasoningEffort;
        private List<Message> initialMessages;
        private AtomicBoolean abortFlag;
        private Function<Integer, List<InjectionItem>> injectionCallback;
        private boolean persistSession = true;
        private boolean delegated = false;
        private List<InstanceInfo> instanceMentions = List.of();
        private Supplier<Long> resetEpochSupplier;
        private boolean goalRequested = false;
        private boolean hostSyntheticTrigger = false;
        private Long turnId;
        private org.gitee.jmeter.ai.agent.presenter.TurnOrigin origin;

        public Builder userMessage(String message) {
            this.userMessage = message;
            return this;
        }

        public Builder sessionKey(String key) {
            this.sessionKey = key;
            return this;
        }

        public Builder hook(AgentHook hook) {
            this.hook = hook;
            return this;
        }

        public Builder maxIterations(int iterations) {
            this.maxIterations = iterations;
            return this;
        }

        public Builder failOnToolError(boolean fail) {
            this.failOnToolError = fail;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public Builder initialMessages(List<Message> messages) {
            this.initialMessages = messages;
            return this;
        }

        public Builder abortFlag(AtomicBoolean flag) {
            this.abortFlag = flag;
            return this;
        }

        public Builder injectionCallback(Function<Integer, List<InjectionItem>> callback) {
            this.injectionCallback = callback;
            return this;
        }

        /**
         * Whether to persist messages to the session store and run memory
         * consolidation. Defaults to true (main-agent behaviour).
         */
        public Builder persistSession(boolean persist) {
            this.persistSession = persist;
            return this;
        }

        /** Mark this run as a cross-instance delegated turn (arms DelegationGuard). */
        public Builder delegated(boolean delegated) {
            this.delegated = delegated;
            return this;
        }

        /** Peer instances @-mentioned by the user (per-turn runtime context). Null normalizes to empty. */
        public Builder instanceMentions(List<InstanceInfo> mentions) {
            this.instanceMentions = mentions == null ? List.of() : mentions;
            return this;
        }

        /**
         * 会话重置代数活引用（见 {@link #getResetEpochSupplier()} 的判别语义）。
         * null（默认）= 不判重置：中止落盘与悬空尾懒收尾不执行（子代理路径）。
         */
        public Builder resetEpochSupplier(Supplier<Long> supplier) {
            this.resetEpochSupplier = supplier;
            return this;
        }

        /** 本回合由 /goal 触发（goal 变更权限 + Runtime Context 请求态）。 */
        public Builder goalRequested(boolean goalRequested) {
            this.goalRequested = goalRequested;
            return this;
        }

        /** 触发消息为宿主合成（goal 续跑/交回切片直投）。 */
        public Builder hostSyntheticTrigger(boolean hostSyntheticTrigger) {
            this.hostSyntheticTrigger = hostSyntheticTrigger;
            return this;
        }

        /**
         * 回合身份（进程唯一句柄 id）+ 来源。null（默认）= 无回合身份：
         * 子代理与直构测试运行，回合快照旁路。
         */
        public Builder turnIdentity(Long turnId,
                                    org.gitee.jmeter.ai.agent.presenter.TurnOrigin origin) {
            this.turnId = turnId;
            this.origin = origin;
            return this;
        }

        public AgentRunSpec build() {
            Objects.requireNonNull(sessionKey, "sessionKey is required");

            // Enforce the subagent isolation invariants at construction time so a
            // mis-wired subagent run fails fast instead of silently polluting the
            // main session. Checked before the
            // userMessage requirement so a subagent gets the actionable error.
            if (sessionKey.startsWith(SUBAGENT_SESSION_PREFIX)) {
                if (persistSession) {
                    throw new IllegalArgumentException(
                        "Subagent run must set persistSession(false): " + sessionKey);
                }
                if (injectionCallback != null) {
                    throw new IllegalArgumentException(
                        "Subagent run must not have an injectionCallback: " + sessionKey);
                }
                if (initialMessages == null || initialMessages.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Subagent run requires non-empty initialMessages: " + sessionKey);
                }
            }

            // A run driven by initialMessages needs no userMessage; otherwise the
            // user turn is what the run is built from.
            if (initialMessages == null || initialMessages.isEmpty()) {
                Objects.requireNonNull(userMessage, "userMessage is required");
            }

            return new AgentRunSpec(this);
        }
    }
}
