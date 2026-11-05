package org.gitee.jmeter.ai.utils;

import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to access application version information.
 */
public class VersionUtils {
    private static final Logger log = LoggerFactory.getLogger(VersionUtils.class);
    private static final String VERSION_PROPERTIES_PATH = "/org/gitee/jmeter/ai/version.properties";
    private static final String UNKNOWN_VERSION = "unknown";

    private static String version = null;

    /**
     * Gets the application version from version.properties, whose
     * ${project.version} placeholder is substituted by Maven resource filtering
     * at build time. The file is present both in the packaged JAR and in
     * target/classes, so every build artifact resolves the pom version.
     *
     * @return The application version string, or "unknown" when the file is
     *         missing or unfiltered (build anomaly, logged once)
     */
    public static String getVersion() {
        if (version == null) {
            try (InputStream is = VersionUtils.class.getResourceAsStream(VERSION_PROPERTIES_PATH)) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    String filtered = props.getProperty("version");
                    // An unsubstituted literal means filtering never ran; skip it
                    if (filtered != null && !filtered.isEmpty() && !filtered.startsWith("${")) {
                        version = filtered;
                        log.info("Found version in version.properties: {}", version);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read version.properties: {}", e.getMessage());
            }
            if (version == null) {
                log.warn("version.properties missing or unfiltered, reporting version as {}", UNKNOWN_VERSION);
                version = UNKNOWN_VERSION;
            }
        }
        return version;
    }

    /**
     * Gets the User-Agent string identifying this plugin in LLM API requests.
     *
     * @return product name plus resolved version, e.g. "jmeter-agent/0.5.1"
     */
    public static String userAgent() {
        return "jmeter-agent/" + getVersion();
    }
}
