package org.gitee.jmeter.ai.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 启动参数的预填与记忆。
 *
 * <p>预填优先级：上次输入 > {@code jmeter.ai.recorder.start.url / include.domains /
 * resource.types / max.body.bytes} 属性 > 空/属性默认。
 */
public final class RecorderParams {

    public record Start(String url, Set<String> domains,
                        Set<String> resourceTypes, Integer maxBodyBytes) {
    }

    private static final String KEY_URL = "url";
    private static final String KEY_DOMAINS = "domains";
    private static final String KEY_TYPES = "resource.types";
    private static final String KEY_MAX_BODY = "max.body.bytes";

    private RecorderParams() {
    }

    public static Start prefill(Path lastFile, String propUrl, String propDomains,
                                Set<String> propResourceTypes, int propMaxBody) {
        Properties last = load(lastFile);
        String url = firstNonBlank(last.getProperty(KEY_URL), propUrl);
        String domains = firstNonBlank(last.getProperty(KEY_DOMAINS), propDomains);
        Set<String> types = parseTypes(last.getProperty(KEY_TYPES));
        if (types.isEmpty() && propResourceTypes != null && !propResourceTypes.isEmpty()) {
            types = propResourceTypes;
        }
        Integer maxBody = null;
        String rawMax = last.getProperty(KEY_MAX_BODY);
        if (rawMax != null && !rawMax.isBlank()) {
            try {
                maxBody = Integer.parseInt(rawMax.trim());
            } catch (NumberFormatException ignored) {
                // 记忆值坏损：回退属性默认
            }
        }
        if (maxBody == null) {
            maxBody = propMaxBody;
        }
        return new Start(url == null ? "" : url, parseDomains(domains), types, maxBody);
    }

    public static void saveLast(Path lastFile, Start params) {
        try {
            if (lastFile.getParent() != null) {
                Files.createDirectories(lastFile.getParent());
            }
            Properties p = new Properties();
            p.setProperty(KEY_URL, params.url() == null ? "" : params.url());
            p.setProperty(KEY_DOMAINS, params.domains() == null ? "" : String.join(",", params.domains()));
            p.setProperty(KEY_TYPES, params.resourceTypes() == null ? "" : String.join(",", params.resourceTypes()));
            p.setProperty(KEY_MAX_BODY, params.maxBodyBytes() == null ? "" : String.valueOf(params.maxBodyBytes()));
            try (OutputStream out = Files.newOutputStream(lastFile)) {
                p.store(out, "browser-recording last used start params");
            }
        } catch (IOException ignored) {
            // 记忆失败不影响录制（best-effort）
        }
    }

    public static Set<String> parseDomains(String csv) {
        Set<String> domains = new LinkedHashSet<>();
        if (csv != null) {
            for (String d : csv.split(",")) {
                if (!d.isBlank()) {
                    domains.add(d.trim());
                }
            }
        }
        return domains;
    }

    /** 资源类型集合解析：小写归一（与 EventFilter 白名单的匹配口径一致）。 */
    public static Set<String> parseTypes(String csv) {
        Set<String> types = new LinkedHashSet<>();
        if (csv != null) {
            for (String t : csv.split(",")) {
                if (!t.isBlank()) {
                    types.add(t.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return types;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static Properties load(Path file) {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException ignored) {
                // 读失败按无记忆处理
            }
        }
        return p;
    }
}
