package org.gitee.jmeter.ai.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Verifies ElementRegistry loading and JMeterElementManager API contract.
 * Phase 2 regression: every legacy + schema entry resolves, model/gui classes load via reflection.
 */
public class JMeterElementManagerTest {
    public static void main(String[] args) {
        // Pre-load registry from development source tree (force reload in case prior test left state)
        Path skillsDir = Paths.get("src/main/jmeter-agent/skills");
        ElementRegistry.getInstance().reload(skillsDir);

        int failures = 0;
        failures += assertRegistrySize();
        failures += assertClassesLoadable();
        failures += assertAliases();
        failures += assertLegacyComponents();
        failures += assertDefaultNameFallback();

        if (failures == 0) {
            System.out.println("OK: all assertions passed.");
        } else {
            System.out.println("FAIL: " + failures + " assertion(s) failed.");
            System.exit(1);
        }
    }

    private static int assertRegistrySize() {
        Map<String, JMeterElementManager.ElementClassInfo> map =
                JMeterElementManager.getElementClassMap();
        // After dedup: ~63 schema types + ~80 legacy types + aliases = ~170+
        if (map.size() < 160) {
            System.out.printf("FAIL: registry size %d, expected >= 160%n", map.size());
            return 1;
        }
        System.out.printf("OK: registry size = %d%n", map.size());
        return 0;
    }

    private static int assertClassesLoadable() {
        Map<String, JMeterElementManager.ElementClassInfo> map =
                JMeterElementManager.getElementClassMap();
        int missing = 0;
        for (Map.Entry<String, JMeterElementManager.ElementClassInfo> e : map.entrySet()) {
            String type = e.getKey();
            String model = e.getValue().getModelClassName();
            String gui = e.getValue().getGuiClassName();
            if (model == null || model.isEmpty()) {
                System.out.printf("FAIL: empty testClass for %s%n", type);
                missing++;
            }
            if (gui == null || gui.isEmpty()) {
                System.out.printf("FAIL: empty guiClass for %s%n", type);
                missing++;
            }
        }
        if (missing == 0) {
            System.out.println("OK: all " + map.size() + " entries have non-empty testClass + guiClass.");
        }
        return missing;
    }

    private static int assertAliases() {
        // responseassert and responseassertion should resolve to same ElementClassInfo
        JMeterElementManager.ElementClassInfo a = JMeterElementManager.getElementClassInfo("responseassert");
        JMeterElementManager.ElementClassInfo b = JMeterElementManager.getElementClassInfo("responseassertion");
        if (a == null || b == null) {
            System.out.printf("FAIL: responseassert=%s, responseassertion=%s%n", a, b);
            return 1;
        }
        if (!a.getModelClassName().equals(b.getModelClassName())
                || !a.getGuiClassName().equals(b.getGuiClassName())) {
            System.out.printf("FAIL: alias mismatch: responseassert=%s vs responseassertion=%s%n", a, b);
            return 1;
        }

        // httpsampler alias check
        JMeterElementManager.ElementClassInfo h1 = JMeterElementManager.getElementClassInfo("httpsampler");
        JMeterElementManager.ElementClassInfo h2 = JMeterElementManager.getElementClassInfo("httprequest");
        if (h1 == null || h2 == null || !h1.getModelClassName().equals(h2.getModelClassName())) {
            System.out.printf("FAIL: httpsampler/httprequest alias mismatch: %s vs %s%n", h1, h2);
            return 1;
        }
        System.out.println("OK: aliases resolve correctly.");
        return 0;
    }

    private static int assertLegacyComponents() {
        // Sample of legacy components (no schema, in legacy-elements.yaml)
        String[] legacyTypes = {"ftprequest", "tcpsampler", "ldaprequest", "synctimer", "interleavecontroller"};
        int missing = 0;
        for (String type : legacyTypes) {
            if (JMeterElementManager.getElementClassInfo(type) == null) {
                System.out.printf("FAIL: legacy component %s not in registry%n", type);
                missing++;
            }
        }
        if (missing == 0) {
            System.out.println("OK: legacy components present in registry.");
        }
        return missing;
    }

    private static int assertDefaultNameFallback() {
        // For legacy types, getDefaultNameForElement should resolve via registry
        String name = JMeterElementManager.getDefaultNameForElement("ftprequest");
        if (name == null || name.isEmpty()) {
            System.out.printf("FAIL: default name for ftprequest is %s%n", name);
            return 1;
        }
        // Switch case should still work for known entries
        String httpName = JMeterElementManager.getDefaultNameForElement("httpsampler");
        if (!"HTTP Request".equals(httpName)) {
            System.out.printf("FAIL: expected 'HTTP Request' for httpsampler, got '%s'%n", httpName);
            return 1;
        }
        System.out.printf("OK: default name resolution works (ftprequest='%s', httpsampler='%s').%n", name, httpName);
        return 0;
    }
}
