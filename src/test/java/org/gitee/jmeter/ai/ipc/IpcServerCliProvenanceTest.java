package org.gitee.jmeter.ai.ipc;

import org.gitee.jmeter.ai.agent.command.BuiltinCommands;
import org.gitee.jmeter.ai.agent.command.CommandRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link IpcServer#applyCliProvenance}:CLI 直连非命令消息加 {@code [from cli]} 前缀,
 * 斜杠命令豁免(否则前缀挡死命令的精确匹配分发),委派载荷原样。
 */
class IpcServerCliProvenanceTest {

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        router = new CommandRouter();
        BuiltinCommands.registerBuiltinCommands(router);
    }

    @Test
    void plainCliMessageGetsPrefix() {
        assertEquals("[from cli] 运行测试",
                IpcServer.applyCliProvenance(router, "运行测试", false));
    }

    @Test
    void slashCommandsAreExempt() {
        // /status(priority)、/new、/help(exact)均不得加前缀,否则命令分发被破坏
        assertEquals("/status", IpcServer.applyCliProvenance(router, "/status", false));
        assertEquals("/new", IpcServer.applyCliProvenance(router, "/new", false));
        assertEquals("/help", IpcServer.applyCliProvenance(router, "/help", false));
    }

    @Test
    void unregisteredSlashTextStillGetsPrefix() {
        // 未注册的斜杠文本不是命令——按普通消息加前缀
        assertEquals("[from cli] /not-a-command",
                IpcServer.applyCliProvenance(router, "/not-a-command", false));
    }

    @Test
    void delegatedPayloadUntouched() {
        String payload = "[delegated-from instanceId=abc-1 pid=123 script=/x.jmx] analyze the plan";
        assertEquals(payload, IpcServer.applyCliProvenance(router, payload, true));
    }
}
