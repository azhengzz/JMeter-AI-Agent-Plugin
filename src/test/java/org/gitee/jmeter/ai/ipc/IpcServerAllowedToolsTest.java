package org.gitee.jmeter.ai.ipc;

import org.gitee.jmeter.ai.agent.tools.JMeterToolRegistry;
import org.gitee.jmeter.ai.agent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IPC /tool 白名单守卫:白名单条目必须全部存在于服务端注册表注册名集合
 * (手抖错名只会在首次调用时 400,本测试把拦截提前到合入前),
 * 且 fs/web/exec 封禁族恒不进入白名单。
 */
class IpcServerAllowedToolsTest {

    /** 与 IpcServer.registry() 同构的单参构造注册表(null sessionManager,goal 工具不在)。 */
    private static Set<String> registeredNames() {
        ToolRegistry registry = new ToolRegistry();
        JMeterToolRegistry.registerDefaultTools(registry);
        return new HashSet<>(registry.getToolNames());
    }

    @Test
    void allowlistIsSubsetOfRegisteredToolNames() {
        Set<String> registered = registeredNames();
        for (String tool : IpcServer.ALLOWED_TOOLS) {
            assertTrue(registered.contains(tool),
                    "IPC allowlist entry not registered server-side: " + tool);
        }
    }

    @Test
    void batchAndValidateToolsAreAllowlisted() {
        assertTrue(IpcServer.ALLOWED_TOOLS.containsAll(Set.of(
                "batch_delete_jmeter_elements", "batch_move_jmeter_elements",
                "batch_toggle_jmeter_elements", "validate_test_plan")),
                "newly opened tools must be in the allowlist");
    }

    @Test
    void dangerousToolFamiliesNeverAllowlisted() {
        for (String blocked : Set.of(
                // fs/web/exec 封禁族
                "read_file", "write_file", "edit_file", "list_dir", "web_search", "web_fetch", "exec",
                // 跨实例协作 / goal / 子代理族(默认注册但白名单外,与 docs/test §6.3 口径一致)
                "delegate_to_instance", "list_instances", "read_instance_session",
                "create_goal", "update_goal", "spawn", "subagent_status")) {
            assertFalse(IpcServer.ALLOWED_TOOLS.contains(blocked),
                    blocked + " must never be callable over IPC");
        }
    }

    /**
     * CLI dispatch 投递的工具名目录(22 个,与 JmeterCli 各 cmdXxx 的字面量同步维护)与
     * IPC 白名单集合相等:白名单漂移(误删/错拼)或新增动词未同步目录都会在此失败。
     */
    @Test
    void cliDispatchedToolCatalogMatchesAllowlistExactly() {
        Set<String> dispatched = Set.of(
                "get_script_info", "get_selected_element", "get_test_plan_tree",
                "parse_jmx_file", "open_jmx_file", "find_element",
                "create_jmeter_element", "update_jmeter_element", "batch_update_jmeter_elements",
                "delete_jmeter_element", "batch_delete_jmeter_elements",
                "move_jmeter_element", "batch_move_jmeter_elements",
                "copy_paste_jmeter_element",
                "toggle_jmeter_element", "batch_toggle_jmeter_elements",
                "query_element_properties", "get_log_panel_content",
                "run_test", "get_test_status", "get_test_results",
                "validate_test_plan");
        assertEquals(dispatched, IpcServer.ALLOWED_TOOLS,
                "CLI 投递目录与 IPC 白名单必须集合相等(新增动词/白名单变更须双侧同步)");
    }
}
