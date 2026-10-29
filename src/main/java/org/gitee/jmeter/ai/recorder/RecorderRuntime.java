package org.gitee.jmeter.ai.recorder;

import org.gitee.jmeter.ai.instance.InstanceContext;
import org.gitee.jmeter.ai.recorder.cdp.BrowserInstanceFinder;
import org.gitee.jmeter.ai.recorder.cdp.DevToolsHttp;
import org.gitee.jmeter.ai.recorder.cdp.WebSocketCdpTransport;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 录制引擎的进程级单会话持有者（面板可重建，引擎跨面板存活）。
 *
 * <p>引擎单次使用（DONE/CANCELLED 为终态），{@link #engine()} 在终态后自动换新实例；
 * UI 侦听器经中继挂接（面板重建时换靶，不重建引擎）。
 */
public final class RecorderRuntime {
    private static final Logger log = LoggerFactory.getLogger(RecorderRuntime.class);
    private static volatile RecorderRuntime instance;

    private final RelayListener relay = new RelayListener();
    private volatile RecorderEngine engine;
    private final Path workspace;
    private final String instanceId;
    /** 测试引擎工厂（null = 生产路径）；收中继侦听器，保证 UI 订阅链完整。 */
    private final java.util.function.Function<RecorderEngine.EngineListener, RecorderEngine> testEngineFactory;

    private RecorderRuntime() {
        this(WorkspacePaths.resolveWorkspace(), InstanceContext.instanceId(), null);
    }

    /** 测试装配：给定工作目录、实例标识与引擎工厂构造独立运行时（生产代码不得使用）。 */
    public static RecorderRuntime forTest(Path workspace, String instanceId,
            java.util.function.Function<RecorderEngine.EngineListener, RecorderEngine> engineFactory) {
        return new RecorderRuntime(workspace, instanceId, engineFactory);
    }

    private RecorderRuntime(Path workspace, String instanceId,
            java.util.function.Function<RecorderEngine.EngineListener, RecorderEngine> testEngineFactory) {
        this.workspace = workspace;
        this.instanceId = instanceId;
        this.testEngineFactory = testEngineFactory;
    }

    public static RecorderRuntime get() {
        RecorderRuntime r = instance;
        if (r == null) {
            synchronized (RecorderRuntime.class) {
                if (instance == null) {
                    instance = new RecorderRuntime();
                }
                r = instance;
            }
        }
        return r;
    }

    /** 面板注册 UI 侦听器（回调线程不保证，订阅端自投 EDT）。 */
    public void setUiListener(RecorderEngine.EngineListener listener) {
        relay.target = listener;
    }

    /** 当前引擎；终态（DONE/CANCELLED）后返回新实例（单会话、可连续多场）。 */
    public synchronized RecorderEngine engine() {
        if (engine == null || isTerminal(engine.state())) {
            engine = testEngineFactory != null ? testEngineFactory.apply(relay) : createEngine();
            log.debug("Recording engine created (single-session holder)");
        }
        return engine;
    }

    private BrowserInstanceFinder newFinder(RecorderConfig cfg) {
        return new BrowserInstanceFinder(cfg, new DevToolsHttp());
    }

    /** 「记住上次」持久化文件（输出目录下）。 */
    public Path lastParamsFile() {
        return RecorderConfig.load().resolveOutputDir(workspace).resolve("last-params.properties");
    }

    /** 只读当前引擎状态（不出于水合目的创建引擎）。 */
    public RecorderEngine.State peekState() {
        RecorderEngine e = engine;
        return e == null ? RecorderEngine.State.IDLE : e.state();
    }

    /** 终态快照：不触发引擎换新（status 工具在 DONE 后仍能观测产物目录与计数）。 */
    public RecorderEngine.Status statusSnapshot() {
        RecorderEngine e = engine;
        if (e == null) {
            return new RecorderEngine.Status(RecorderEngine.State.IDLE, 0, 0, 0, 0, null);
        }
        return e.status();
    }

    /** 请求计数快照（无锁，供 EDT 定时器轮询）。 */
    public long requestCountSnapshot() {
        RecorderEngine e = engine;
        return e == null ? 0 : e.requestCount();
    }

    public Path workspace() {
        return workspace;
    }

    public String instanceId() {
        return instanceId;
    }

    private RecorderEngine createEngine() {
        RecorderConfig cfg = RecorderConfig.load();
        BrowserInstanceFinder finder = newFinder(cfg);
        RecorderEngine.BrowserProvider provider = (c, ws, url) -> finder.open(ws, url);
        return new RecorderEngine(cfg, relay, workspace, instanceId, provider,
                WebSocketCdpTransport::new, RecorderScripts.INIT_SCRIPT);
    }

    /** 同秒重复另存的文件名去重（存在则 -1/-2 递增，防静默截断覆盖）。 */
    public static java.nio.file.Path uniquePath(java.nio.file.Path base) {
        if (!java.nio.file.Files.exists(base)) {
            return base;
        }
        String name = base.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        for (int i = 1; ; i++) {
            java.nio.file.Path candidate = base.resolveSibling(stem + "-" + i + ext);
            if (!java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static boolean isTerminal(RecorderEngine.State s) {
        return s == RecorderEngine.State.DONE || s == RecorderEngine.State.CANCELLED;
    }

    /** 引擎持有此中继；target 可换（面板重建），事件不多播。 */
    private static final class RelayListener implements RecorderEngine.EngineListener {
        volatile RecorderEngine.EngineListener target;

        @Override
        public void onState(RecorderEngine.State from, RecorderEngine.State to) {
            RecorderEngine.EngineListener t = target;
            if (t != null) {
                t.onState(from, to);
            }
        }

        @Override
        public void onFinalized(RecorderEngine.Status status) {
            RecorderEngine.EngineListener t = target;
            if (t != null) {
                t.onFinalized(status);
            }
        }

        @Override
        public void onCancelled(String reason) {
            RecorderEngine.EngineListener t = target;
            if (t != null) {
                t.onCancelled(reason);
            }
        }
    }
}
