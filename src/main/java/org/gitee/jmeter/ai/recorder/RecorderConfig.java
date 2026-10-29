package org.gitee.jmeter.ai.recorder;

import org.gitee.jmeter.ai.utils.AiConfig;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;

/**
 * 录制配置（{@code jmeter.ai.recorder.*}）。
 *
 * <p>缺省值与 {@code jmeter-ai-sample.properties} 中的注释保持一致；
 * {@link #from(Function)} 供单测注入属性源，{@link #load()} 走 JMeter 属性。
 */
public final class RecorderConfig {

    public static final String KEY_ENABLED = "jmeter.ai.recorder.enabled";
    public static final String KEY_BROWSER = "jmeter.ai.recorder.browser";
    public static final String KEY_RESOURCE_TYPES = "jmeter.ai.recorder.resource.types";
    public static final String KEY_MAX_BODY_BYTES = "jmeter.ai.recorder.max.body.bytes";
    public static final String KEY_OUTPUT_DIR = "jmeter.ai.recorder.output.dir";
    public static final String KEY_START_URL = "jmeter.ai.recorder.start.url";
    public static final String KEY_INCLUDE_DOMAINS = "jmeter.ai.recorder.include.domains";

    public final boolean enabled;
    /** "chrome" / 可执行文件绝对路径（默认 chrome；Edge 支持已按用户要求移除） */
    public final String browser;
    /** resourceType 白名单（小写） */
    public final Set<String> resourceTypes;
    public final int maxBodyBytes;
    /** 录制数据输出目录，空串 = 默认 {workspace}/recordings */
    public final String outputDir;
    /** 参数窗属性预填（jmeter.ai.recorder.start.url） */
    public final String startUrlPrefill;
    /** 参数窗属性预填（jmeter.ai.recorder.include.domains，逗号分隔） */
    public final String includeDomainsPrefill;

    private RecorderConfig(boolean enabled, String browser,
                           Set<String> resourceTypes, int maxBodyBytes, String outputDir,
                           String startUrlPrefill, String includeDomainsPrefill) {
        this.enabled = enabled;
        this.browser = browser;
        this.resourceTypes = resourceTypes;
        this.maxBodyBytes = maxBodyBytes;
        this.outputDir = outputDir;
        this.startUrlPrefill = startUrlPrefill;
        this.includeDomainsPrefill = includeDomainsPrefill;
    }

    public static RecorderConfig from(Function<String, String> props) {
        boolean enabled = bool(props.apply(KEY_ENABLED), true);
        String browser = str(props.apply(KEY_BROWSER), "chrome");
        Set<String> types = new java.util.LinkedHashSet<>();
        String rawTypes = str(props.apply(KEY_RESOURCE_TYPES), "xhr,fetch");
        for (String t : rawTypes.split(",")) {
            if (!t.isBlank()) {
                // Locale.ROOT：与 EventFilter/RecorderParams 的白名单口径一致
                //（默认 locale 的 toLowerCase 在土耳其语等环境会静默改变 'I'）
                types.add(t.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        int maxBody = intVal(props.apply(KEY_MAX_BODY_BYTES), 65536);
        String outputDir = str(props.apply(KEY_OUTPUT_DIR), "");
        String startUrl = str(props.apply(KEY_START_URL), "");
        String includeDomains = str(props.apply(KEY_INCLUDE_DOMAINS), "");
        return new RecorderConfig(enabled, browser, types, maxBody, outputDir,
                startUrl, includeDomains);
    }

    public static RecorderConfig load() {
        return from(k -> AiConfig.getProperty(k, null));
    }

    /** 输出目录解析：配置为空回退 {workspace}/recordings。 */
    public Path resolveOutputDir(Path workspace) {
        if (outputDir != null && !outputDir.isEmpty()) {
            return Path.of(outputDir).toAbsolutePath().normalize();
        }
        return workspace.resolve("recordings");
    }

    /**
     * 专用实例持久 profile 目录（{workspace}/browser-profile，跨 JMeter 重启稳定——
     * 登录态跨会话保留是持久 profile 的存在理由，键随每次启动变化会使该承诺失效）。
     *
     * <p>Chrome 以 user-data-dir 为单例域：并行录制者不得同时 spawn 进本目录。
     * 占用仲裁由 BrowserInstanceFinder 的跨进程占用声明（FileLock）负责——
     * 拿不到声明的实例直接走临时目录回退，不进入对方的单例域。
     */
    public Path resolveProfileDir(Path workspace) {
        return workspace.resolve("browser-profile");
    }

    /** 供 GUI / 工具面判断是否注册（总开关）。 */
    public static boolean isEnabled() {
        return AiConfig.getBoolean(KEY_ENABLED, true);
    }

    private static String str(String v, String def) {
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static boolean bool(String v, boolean def) {
        return (v == null || v.isBlank()) ? def : Boolean.parseBoolean(v.trim());
    }

    private static int intVal(String v, int def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
