package org.gitee.jmeter.ai.agent.command;

import org.gitee.jmeter.ai.agent.AgentLoop;
import org.gitee.jmeter.ai.agent.session.Session;

/**
 * Everything a command handler needs to produce a response.
 * Java equivalent of Nanobot's CommandContext dataclass.
 */
public class CommandContext {
    private final String raw;
    private String args;
    private final Session session;
    private final String sessionKey;
    private final AgentLoop loop;
    /** /goal 附件：命令处理器置位，Phase 3 回合体读取后透传进 AgentRunSpec。 */
    private boolean goalRequested;
    /**
     * 本命令派发发生在「本回合自己的回合体内」（Phase 3 executor 路径）而非忙期
     * 直派（Phase 2）。/goal 借此区分「闲时触发」（放行进 agent 回合）与「忙期
     * 到达」（拒绝并提示先 Stop）——Phase 3 派发时注册表里只有自己的回合。
     */
    private boolean turnOwner;

    public CommandContext(String raw, String args, Session session, String sessionKey, AgentLoop loop) {
        this.raw = raw;
        this.args = args != null ? args : "";
        this.session = session;
        this.sessionKey = sessionKey;
        this.loop = loop;
    }

    public String getRaw() {
        return raw;
    }

    public String getArgs() {
        return args;
    }

    public void setArgs(String args) {
        this.args = args != null ? args : "";
    }

    public Session getSession() {
        return session;
    }

    /** Get session, creating one if null (for priority commands). */
    public Session getSessionOrCreate() {
        if (session != null) {
            return session;
        }
        return loop.getSessionManager().getOrCreate(sessionKey);
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public AgentLoop getLoop() {
        return loop;
    }

    public boolean isGoalRequested() {
        return goalRequested;
    }

    public void setGoalRequested(boolean goalRequested) {
        this.goalRequested = goalRequested;
    }

    public boolean isTurnOwner() {
        return turnOwner;
    }

    public void markTurnOwner() {
        this.turnOwner = true;
    }
}
