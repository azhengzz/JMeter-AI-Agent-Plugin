package org.gitee.jmeter.ai.agent.tools.ipc;

import org.apache.jmeter.util.JMeterUtils;
import org.gitee.jmeter.ai.agent.tools.AbstractTool;
import org.gitee.jmeter.ai.agent.model.ToolResult;
import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.ipc.InstanceRegistry;
import org.gitee.jmeter.ai.ipc.InstanceRegistry.InstanceInfo;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 列出本机其他存活的 JMeter AI 实例(跨实例协作的发现工具)。
 *
 * <p>读取本实例的 IPC 目录,经 {@link InstanceRegistry#listInstances} 做 TCP+PID 双确认存活过滤,
 * 返回每行的 instanceId、PID、port、当前打开的 jmx、启动时间,并标注当前实例自身(self)。
 * 主代理据此挑选 delegate_to_instance 的目标(按 instanceId 或 jmx)。
 *
 * <p>仅在 IPC 与实例协作开关均开启时注册(Group 7 门控)。运行于工具执行线程,不阻塞 EDT。
 */
public class ListInstancesTool extends AbstractTool {
    public static final String NAME = "list_instances";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "List all live JMeter AI instances running on this machine (including yourself), "
                + "for cross-instance coordination. Each row shows instanceId, pid, port, the .jmx test "
                + "plan currently open in that instance (or '(none)'), and when it started; your own "
                + "instance is marked '(self)'. Use this to find which peer holds a given script before "
                + "delegating a task with delegate_to_instance.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {}
                }
                """;
    }

    @Override
    protected ToolResult executeInternal(Map<String, Object> parameters) {
        List<InstanceInfo> all = InstanceRegistry.listInstances(ipcDir());
        if (all.isEmpty()) {
            return ToolResult.success("No JMeter AI instances are currently running (IPC registry is empty).");
        }
        String self = InstanceContext.instanceId();
        StringBuilder sb = new StringBuilder();
        sb.append("Live JMeter AI instances (").append(all.size()).append("):\n\n");
        sb.append("| instanceId | pid | port | open jmx | started (UTC) | self |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (InstanceInfo i : all) {
            String jmx = i.getJmxPath();
            if (jmx == null || jmx.isEmpty()) {
                jmx = "(none)";
            }
            boolean isSelf = self.equals(i.getInstanceId());
            sb.append("| ").append(i.getInstanceId())
                    .append(" | ").append(i.getPid())
                    .append(" | ").append(i.getPort())
                    .append(" | ").append(jmx)
                    .append(" | ").append(Instant.ofEpochMilli(i.getStartedAt()))
                    .append(" | ").append(isSelf ? "(self)" : "")
                    .append(" |\n");
        }
        sb.append("\nDelegate a task to a peer with delegate_to_instance (by its instanceId or jmx). "
                + "Do not delegate to yourself.");
        return ToolResult.success(sb.toString());
    }

    private static File ipcDir() {
        return InstanceRegistry.ipcDir(new File(JMeterUtils.getJMeterHome()));
    }
}
