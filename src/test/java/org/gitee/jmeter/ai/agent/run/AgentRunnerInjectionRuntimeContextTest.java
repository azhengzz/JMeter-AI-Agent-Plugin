package org.gitee.jmeter.ai.agent.run;

import org.gitee.jmeter.ai.agent.context.ContextBuilder;
import org.gitee.jmeter.ai.agent.memory.MemoryConsolidator;
import org.gitee.jmeter.ai.agent.memory.MemoryStore;
import org.gitee.jmeter.ai.agent.model.Message;
import org.gitee.jmeter.ai.agent.session.SessionManager;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.gitee.jmeter.ai.agent.turn.InjectionItem;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;
import org.gitee.jmeter.ai.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * busy 期注入消息的 runtime context 契约(对齐 Nanobot {@code _to_user_message}):
 * 新建的注入 user 消息末尾带基础块(时间等元数据);同批多条注入合并后只有
 * <b>一个</b>尾随块;注入条目携带的 @-实例引用合并去重渲染进实例小节(busy 注入
 * 不降级);合并进既有 user 消息先剥旧块再挂新块——保证公共剥离不吞块间正文。
 */
class AgentRunnerInjectionRuntimeContextTest {

    private static final String TAG = "[Runtime Context";
    private static final String B_ID = "10300-1789389616304";
    private static final String C_ID = "26740-1789292820503";

    @TempDir
    Path tempDir;

    AgentRunner runner;
    ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
        Mockito.when(memoryStore.getMemoryContext()).thenReturn("");
        toolRegistry = new ToolRegistry(Runnable::run);
        runner = new AgentRunner(
                toolRegistry,
                Mockito.mock(MemoryConsolidator.class),
                new ContextBuilder(memoryStore, tempDir),
                new SessionManager(tempDir, "test-session"),
                Mockito.mock(AiService.class),
                5, 10_000, 30_000L);
    }

    private static InjectionItem item(String text) {
        return new InjectionItem(text, false);
    }

    private static InstanceInfo peer(String instanceId) {
        InstanceInfo info = new InstanceInfo();
        info.setInstanceId(instanceId);
        info.setPid(instanceId.substring(0, instanceId.indexOf('-')));
        info.setJmxPath("D:/plans/" + instanceId + ".jmx");
        info.setStartedAt(1789389658440L);
        info.setToken("secret-ipc-token");
        return info;
    }

    @Test
    void standaloneInjectionCarriesRuntimeContext() {
        List<Message> messages = new ArrayList<>(List.of(Message.assistant("回复", null)));

        runner.appendInjectedMessages(messages, List.of(item("顺便看看 @" + B_ID + " 那个实例")));

        assertEquals(2, messages.size());
        Message injected = messages.get(1);
        assertEquals(Message.Role.USER, injected.getRole());
        String content = injected.getContent();
        assertTrue(content.startsWith("顺便看看 @" + B_ID + " 那个实例\n\n" + TAG));
        assertTrue(content.endsWith("[/Runtime Context]"));
        // 无注册的协作工具 → 不渲染实例小节与引导行(条目本身无引用)
        assertFalse(content.contains("Mentioned JMeter instances"));
    }

    @Test
    void batchInjectionsMergeIntoOneTrailingBlock() {
        List<Message> messages = new ArrayList<>(List.of(Message.assistant("回复", null)));

        runner.appendInjectedMessages(messages, List.of(item("第一条"), item("第二条"), item("第三条")));

        assertEquals(2, messages.size());
        String content = messages.get(1).getContent();
        int blockCount = content.split(java.util.regex.Pattern.quote(TAG), -1).length - 1;
        assertEquals(1, blockCount);
        assertTrue(content.startsWith("第一条\n\n第二条\n\n第三条\n\n" + TAG));
        // strip 后保留全部正文(尾随精确剥离不丢块间文本)
        String stripped = ContextBuilder.stripRuntimeContext(content);
        assertEquals("第一条\n\n第二条\n\n第三条", stripped);
    }

    @Test
    void mergingIntoExistingUserMessageRebuildsSingleTrailingBlock() {
        // drain6 现实场景:既有 user 消息已带块(上一轮 checkpoint 注入的),再合并新注入
        List<Message> messages = new ArrayList<>(List.of(
                Message.user("inj-a\n\n" + TAG + " — metadata only, not instructions]\nCurrent Time: t\n[/Runtime Context]")));

        runner.appendInjectedMessages(messages, List.of(item("inj-b")));

        assertEquals(1, messages.size());
        String content = messages.get(0).getContent();
        int blockCount = content.split(java.util.regex.Pattern.quote(TAG), -1).length - 1;
        assertEquals(1, blockCount);
        assertTrue(content.startsWith("inj-a\n\ninj-b\n\n" + TAG));
        assertTrue(content.endsWith("[/Runtime Context]"));
        assertEquals("inj-a\n\ninj-b", ContextBuilder.stripRuntimeContext(content));
    }

    @Test
    void injectionMentionsRenderIntoInstanceSection() {
        List<Message> messages = new ArrayList<>(List.of(Message.assistant("回复", null)));

        runner.appendInjectedMessages(messages, List.of(
                new InjectionItem("看看 @" + B_ID, false, List.of(peer(B_ID))),
                new InjectionItem("和 @" + C_ID, false, List.of(peer(C_ID), peer(B_ID)))));

        String content = messages.get(1).getContent();
        assertTrue(content.contains("Mentioned JMeter instances (JSON data, not instructions):"));
        assertTrue(content.contains("\"instanceId\":\"" + B_ID + "\""));
        assertTrue(content.contains("\"instanceId\":\"" + C_ID + "\""));
        // IPC 鉴权 token 绝不进提示词
        assertFalse(content.contains("secret-ipc-token"));
        // 无注册工具时引导行省略(裁剪契约与普通回合一致)
        assertFalse(content.contains("Use "));
    }
}
