package org.gitee.jmeter.ai.ipc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 端口文件(PID + port + token)的读写与实例发现。
 *
 * <p>只接受 {@link File} 参数,**不引用任何 JMeter 类**,因此 CLI 进程可直接复用
 * (CLI 不应依赖 {@code JMeterUtils})。server 端负责把 {@code JMeterUtils.getJMeterHome()}
 * 解析出的目录传进来。
 *
 * <p>端口文件位于 {@code <jmeterHome>/bin/jmeter-agent/ipc/port-<pid>.json},文件名含 PID,
 * 支持多实例并存;正常退出由 server 的 shutdown hook 删除,异常退出(kill -9 / 强杀)残留的
 * 端口文件由 CLI 侧 {@link #listInstances} / {@link #findInstance} 经 TCP 探活({@link #isAlive})自清理。
 */
public final class InstanceRegistry {
    private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String PORT_FILE_PREFIX = "port-";
    public static final String PORT_FILE_SUFFIX = ".json";
    /** TCP 探活超时(ms):loopback 拒绝通常 <5ms 返回,500ms 仅封顶不可路由病态情况。 */
    public static final int PROBE_TIMEOUT_MS = 500;

    private InstanceRegistry() {
    }

    /**
     * 当前 JVM 的 PID。
     */
    public static String currentPid() {
        // RuntimeMXBean.getName() 形如 "12345@hostname"
        return ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
    }

    /**
     * IPC 目录:{@code <jmeterHome>/bin/jmeter-agent/ipc}。
     */
    public static File ipcDir(File jmeterHome) {
        return new File(new File(jmeterHome, "bin"), "jmeter-agent").toPath().resolve("ipc").toFile();
    }

    /**
     * 某实例的端口文件:{@code <ipcDir>/port-<pid>.json}。
     */
    public static File portFile(File ipcDir, String pid) {
        return new File(ipcDir, PORT_FILE_PREFIX + pid + PORT_FILE_SUFFIX);
    }

    /**
     * 原子写入端口文件(临时文件 + ATOMIC_MOVE)。返回写入的实例信息。
     *
     * @param instanceId 每实例会话键(格式 {@code {pid}-{startedAtMs}}),供他实例识别/委派;可为 null
     * @param jmxPath    当前打开的计划文件绝对路径;无计划传空串(后续由动作监听原子写回)
     */
    public static InstanceInfo writeInstance(File ipcDir, String pid, int port, String token, String bind,
                                             String instanceId, String jmxPath) throws IOException {
        if (!ipcDir.exists() && !ipcDir.mkdirs()) {
            throw new IOException("Cannot create IPC dir: " + ipcDir);
        }
        InstanceInfo info = new InstanceInfo();
        info.setPid(pid);
        info.setPort(port);
        info.setToken(token);
        info.setStartedAt(System.currentTimeMillis());
        info.setBind(bind);
        info.setInstanceId(instanceId);
        info.setJmxPath(jmxPath == null ? "" : jmxPath);

        atomicWrite(ipcDir, info);
        return info;
    }

    /**
     * 读改写地更新某实例端口文件的 {@code jmxPath}(保留其余字段:port/token/startedAt/bind/instanceId),
     * 供 jmx 打开/关闭/新建动作监听(EDT)原子写回。文件不存在(IPC 尚未启动)或读写失败返回 false,不抛。
     *
     * @return 是否成功写回
     */
    public static boolean updateJmxPath(File ipcDir, String pid, String jmxPath) {
        InstanceInfo info = readSilently(portFile(ipcDir, pid));
        if (info == null) {
            return false; // 端口文件尚未写入(IPC 未启动),无内容可改
        }
        info.setJmxPath(jmxPath == null ? "" : jmxPath);
        try {
            atomicWrite(ipcDir, info);
            return true;
        } catch (IOException e) {
            log.warn("Failed to update jmxPath in port file for pid {}: {}", pid, e.getMessage());
            return false;
        }
    }

    /**
     * 临时文件 + ATOMIC_MOVE 原子落盘实例信息(跨盘符/不支持原子移动时退化为普通替换)。
     */
    private static void atomicWrite(File ipcDir, InstanceInfo info) throws IOException {
        if (!ipcDir.exists() && !ipcDir.mkdirs()) {
            throw new IOException("Cannot create IPC dir: " + ipcDir);
        }
        File target = portFile(ipcDir, info.getPid());
        File tmp = new File(ipcDir, target.getName() + ".tmp");
        MAPPER.writeValue(tmp, info);
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 某些平台/跨盘符不支持原子移动,退化为普通替换
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 删除某实例的端口文件(尽力而为)。
     */
    public static boolean deleteInstance(File ipcDir, String pid) {
        File f = portFile(ipcDir, pid);
        return f.delete();
    }

    /**
     * 探活:对实例的 bind:port 做 TCP connect(短 timeout)。
     *
     * <p>依赖 {@code writeInstance} 仅在 HttpServer bind 成功后执行的契约,故:
     * <b>port 文件存在且 connect 失败 ⟹ IPC server 不可达</b>。是否据此清理残留,
     * 由调用方结合 {@link #isPidAlive} 双重确认(TCP 死且 PID 死才删),避免 GC 暂停、
     * 连接队列满、非 loopback 不可达等瞬时因素把活实例误判死而删其 port 文件。
     *
     * <p>越界 port、非法 host 等构造期 {@link RuntimeException}(如 {@link InetSocketAddress}
     * 对 port&gt;65535 抛 {@link IllegalArgumentException})也归为不可探活(返回 false),不向上抛。
     */
    public static boolean isAlive(InstanceInfo info) {
        if (info == null) {
            return false;
        }
        int port = info.getPort();
        if (port <= 0 || port > 65535) {
            return false;
        }
        String host = (info.getBind() == null || info.getBind().isEmpty())
                ? "127.0.0.1" : info.getBind();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException e) {
            // IOException: 端口无监听/超时;RuntimeException: 含 InetSocketAddress 对非法 host 抛 IAE
            return false;
        }
    }

    /**
     * PID 对应的进程是否仍存活(JDK 9+ {@link ProcessHandle})。
     *
     * <p>用于与 {@link #isAlive} 双重确认:仅当 TCP 不通<b>且</b> PID 已死时,调用方才判定
     * 实例确已退出并清理其残留 port 文件。PID 复用(新进程占用同一 pid)时返回 true → 不删,
     * 恰好避开 pid 复用导致的误清理。
     */
    public static boolean isPidAlive(String pid) {
        if (pid == null || pid.isEmpty()) {
            return false;
        }
        try {
            return ProcessHandle.of(Long.parseLong(pid)).isPresent();
        } catch (RuntimeException e) {
            return false;  // NumberFormatException 等
        }
    }

    /**
     * 尽力删除文件,绝不抛(用于清理死实例残留;并发删除/权限失败非致命)。
     */
    private static void deleteQuietly(File f) {
        if (f == null) {
            return;
        }
        try {
            f.delete();
        } catch (Exception ignore) {
            // best-effort
        }
    }

    /**
     * 列出目录下所有可读且**存活**的实例信息。
     *
     * <p>跳过损坏文件;对每个可读实例做 TCP 探活,不可达且 PID 已死的(残留端口文件)跳过并清理;
     * PID 仍活的只跳过不删(可能只是 GC 暂停/连接队列满/非 loopback 不可达等瞬时因素)。
     */
    public static List<InstanceInfo> listInstances(File ipcDir) {
        List<InstanceInfo> list = new ArrayList<>();
        File[] files = ipcDir.listFiles((dir, name) ->
                name.startsWith(PORT_FILE_PREFIX)
                        && (name.endsWith(PORT_FILE_SUFFIX) || name.endsWith(".tmp")));
        if (files == null) {
            return list;
        }
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) {
                deleteQuietly(f);          // 原子写遗留的临时文件,直接清理
                continue;
            }
            InstanceInfo info = readSilently(f);
            if (info == null) {
                continue;                  // 损坏文件:跳过,不探活不删除
            }
            if (isAlive(info)) {
                list.add(info);
            } else if (!isPidAlive(info.getPid())) {
                deleteQuietly(f);          // TCP 死 + PID 死 = 确认退出,清理残留
            }
            // else: TCP 不可达但 PID 仍在 → 保守跳过,不删
        }
        return list;
    }

    /**
     * 按 PID 精确查找实例,不存在/不可读/IPC 不可达均返回 null;
     * TCP 不可达且 PID 已死时清理残留文件,PID 仍活时保留文件待下次重试。
     */
    public static InstanceInfo findInstance(File ipcDir, String pid) {
        File f = portFile(ipcDir, pid);
        InstanceInfo info = readSilently(f);
        if (info != null && !isAlive(info)) {
            if (!isPidAlive(info.getPid())) {
                deleteQuietly(f);          // TCP 死 + PID 死 = 确认退出,清理残留
            }
            return null;                   // TCP 不可达即视为不可用(不连死端口);PID 仍活则保留文件待重试
        }
        return info;
    }

    /**
     * 当目录下恰好一个实例时返回它,否则(0 个或多个)返回 null。
     */
    public static InstanceInfo findSingleInstance(File ipcDir) {
        List<InstanceInfo> all = listInstances(ipcDir);
        return all.size() == 1 ? all.get(0) : null;
    }

    private static InstanceInfo readSilently(File f) {
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            return MAPPER.readValue(f, InstanceInfo.class);
        } catch (Exception e) {
            log.warn("Skipping unreadable port file {}: {}", f, e.getMessage());
            return null;
        }
    }

    /**
     * 端口文件内容:PID、端口、token、启动时间戳、绑定地址、每实例标识、当前打开的计划文件。
     *
     * <p>{@code instanceId}（格式 {@code {pid}-{startedAtMs}}）是会话键与实例的唯一锚;
     * {@code jmxPath} 是当前打开的 jmx 绝对路径(无计划时空串),由动作监听(EDT)原子写回,
     * 供他实例 {@code list_instances}/{@code delegate_to_instance} 据以寻址同任务的伙伴实例。
     *
     * <p>{@link JsonIgnoreProperties} 容忍未知字段:未来版本向端口文件新增字段(或外部工具
     * 写入额外字段)时,本类解析不会失败——实例仍可被发现,而非被 {@code readSilently}
     * 当损坏文件整条跳过。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InstanceInfo {
        private String pid;
        private int port;
        private String token;
        private long startedAt;
        private String bind;
        private String instanceId;
        private String jmxPath;

        public InstanceInfo() {
        }

        public String getPid() {
            return pid;
        }

        public void setPid(String pid) {
            this.pid = pid;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(long startedAt) {
            this.startedAt = startedAt;
        }

        public String getBind() {
            return bind;
        }

        public void setBind(String bind) {
            this.bind = bind;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getJmxPath() {
            return jmxPath;
        }

        public void setJmxPath(String jmxPath) {
            this.jmxPath = jmxPath;
        }
    }
}
