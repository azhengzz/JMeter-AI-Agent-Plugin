package org.gitee.jmeter.ai.utils;

import org.apache.jmeter.services.FileServer;
import org.gitee.jmeter.ai.agent.validation.ComponentSchema;
import org.gitee.jmeter.ai.agent.validation.ComponentSchemaLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton registry that merges component metadata from two sources:
 *   1. Schema files under {skillsDir}/jmeter/references/ (component.testClass/guiClass/name)
 *   2. legacy-elements.yaml next to the skills/jmeter directory (fallback for ~80 components without schema)
 *
 * Replaces JMeterElementManager's hardcoded ELEMENT_CLASS_MAP static block.
 * Initialized explicitly via loadFromSkillsDir() from AbstractJMeterElementTool's constructor,
 * with a lazy fallback to JMeterUtils.getJMeterHome() if accessed before explicit init.
 */
public final class ElementRegistry {
    private static final Logger log = LoggerFactory.getLogger(ElementRegistry.class);
    private static final String LEGACY_YAML_RELATIVE = "jmeter/legacy-elements.yaml";

    private static volatile ElementRegistry instance;

    private final Map<String, JMeterElementManager.ElementClassInfo> registry = new HashMap<>();
    private final Map<String, String> defaultNames = new HashMap<>();
    private volatile boolean loaded = false;

    private ElementRegistry() {}

    public static ElementRegistry getInstance() {
        ElementRegistry local = instance;
        if (local != null) {
            return local;
        }
        synchronized (ElementRegistry.class) {
            if (instance == null) {
                instance = new ElementRegistry();
            }
            return instance;
        }
    }

    /**
     * Initialize the registry from the given skills directory.
     * Idempotent: if already loaded via this method, subsequent calls are no-ops.
     * Use reload() to force re-loading.
     */
    public synchronized void loadFromSkillsDir(Path skillsDir) {
        if (loaded) {
            log.debug("ElementRegistry already loaded - skipping loadFromSkillsDir({})", skillsDir);
            return;
        }
        reload(skillsDir);
    }

    /**
     * Force (re)load the registry from the given skills directory.
     */
    public synchronized void reload(Path skillsDir) {
        registry.clear();
        defaultNames.clear();

        if (skillsDir == null || !Files.exists(skillsDir)) {
            log.warn("Skills directory not found: {} - registry will be empty", skillsDir);
            loaded = true;
            return;
        }

        ComponentSchemaLoader schemaLoader = new ComponentSchemaLoader(skillsDir);
        for (String type : schemaLoader.getLoadedSchemaTypes()) {
            ComponentSchema schema = schemaLoader.loadSchema(type);
            if (schema == null) {
                continue;
            }
            String testClass = schema.getTestClass();
            String guiClass = schema.getGuiClass();
            if (testClass == null || testClass.isEmpty() || guiClass == null || guiClass.isEmpty()) {
                log.warn("Schema '{}' missing testClass/guiClass - skipping registry registration", type);
                continue;
            }
            JMeterElementManager.ElementClassInfo info =
                    new JMeterElementManager.ElementClassInfo(testClass, guiClass);
            registry.put(type, info);

            // componentName is the same for the canonical type and all aliases
            // (they share the same ComponentSchema instance), so populate defaultNames
            // unconditionally — every type key gets the human-readable name.
            if (schema.getComponentName() != null) {
                defaultNames.put(type, schema.getComponentName());
            }
        }
        log.info("Loaded {} entries from schemas", registry.size());

        loadLegacyYaml(skillsDir);

        loaded = true;
        log.info("ElementRegistry loaded: {} entries (primary + aliases)", registry.size());
    }

    @SuppressWarnings("unchecked")
    private void loadLegacyYaml(Path skillsDir) {
        Path legacyPath = skillsDir.resolve(LEGACY_YAML_RELATIVE);
        if (!Files.exists(legacyPath)) {
            log.warn("Legacy yaml not found: {} - falling back to schema-only registry", legacyPath);
            return;
        }
        try {
            String content = Files.readString(legacyPath);
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(content);
            if (data == null || !data.containsKey("elements")) {
                log.warn("Legacy yaml missing 'elements' section: {}", legacyPath);
                return;
            }
            List<Map<String, Object>> elements = (List<Map<String, Object>>) data.get("elements");
            int registered = 0;
            for (Map<String, Object> entry : elements) {
                String elementType = stringValue(entry, "elementType");
                String testClass = stringValue(entry, "testClass");
                String guiClass = stringValue(entry, "guiClass");
                String defaultName = stringValue(entry, "defaultName");
                if (elementType == null || testClass == null || guiClass == null) {
                    log.warn("Legacy entry missing required field: {}", entry);
                    continue;
                }
                JMeterElementManager.ElementClassInfo info =
                        new JMeterElementManager.ElementClassInfo(testClass, guiClass);
                registry.put(JMeterElementManager.normalizeElementType(elementType), info);
                if (defaultName != null) {
                    defaultNames.put(JMeterElementManager.normalizeElementType(elementType), defaultName);
                }
                // Register aliases
                Object aliasesObj = entry.get("aliases");
                if (aliasesObj instanceof List) {
                    for (Object alias : (List<?>) aliasesObj) {
                        if (alias != null) {
                            String normalizedAlias = JMeterElementManager.normalizeElementType(alias.toString());
                            registry.put(normalizedAlias, info);
                            if (defaultName != null) {
                                defaultNames.put(normalizedAlias, defaultName);
                            }
                        }
                    }
                }
                registered++;
            }
            log.info("Loaded {} legacy entries from {}", registered, legacyPath);
        } catch (Exception e) {
            log.warn("Failed to parse legacy yaml: {}", legacyPath, e);
        }
    }

    /**
     * Lazy-load from JMeter's home if not yet loaded explicitly.
     * No-op if loadFromSkillsDir was already called.
     */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            Path skillsDir = resolveDefaultSkillsDir();
            loadFromSkillsDir(skillsDir);
        }
    }

    private Path resolveDefaultSkillsDir() {
        // FileServer.getBaseDir() returns JMeter's working directory (typically JMETER_HOME/bin)
        String baseDir = FileServer.getFileServer().getBaseDir();
        if (baseDir != null) {
            return Paths.get(baseDir, "jmeter-agent", "skills");
        }
        log.warn("FileServer base dir is null - cannot resolve skills dir");
        return null;
    }

    public JMeterElementManager.ElementClassInfo lookup(String normalizedType) {
        ensureLoaded();
        if (normalizedType == null) {
            return null;
        }
        return registry.get(normalizedType);
    }

    public Map<String, JMeterElementManager.ElementClassInfo> snapshot() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(registry));
    }

    public String resolveDefaultName(String normalizedType) {
        ensureLoaded();
        if (normalizedType == null) {
            return null;
        }
        return defaultNames.get(normalizedType);
    }

    public boolean isLoaded() {
        return loaded;
    }

    private String stringValue(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }
}
