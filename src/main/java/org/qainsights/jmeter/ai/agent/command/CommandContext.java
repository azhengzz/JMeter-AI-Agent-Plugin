package org.qainsights.jmeter.ai.agent.command;

import org.qainsights.jmeter.ai.agent.AgentLoop;
import org.qainsights.jmeter.ai.agent.session.Session;

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
}
