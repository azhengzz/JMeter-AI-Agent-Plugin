package org.gitee.jmeter.ai.agent.validation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Verifies that every loaded schema declares both testClass and guiClass.
 * Run after Phase 1 schema migration to catch missing fields before runtime.
 */
public class SchemaMetadataTest {
    public static void main(String[] args) {
        Path skillsDir = Paths.get("src/main/jmeter-agent/skills");
        ComponentSchemaLoader loader = new ComponentSchemaLoader(skillsDir);

        Set<String> types = loader.getLoadedSchemaTypes();
        System.out.println("Loaded " + types.size() + " schema entries (includes aliases).");

        int missing = 0;
        for (String type : types) {
            ComponentSchema schema = loader.loadSchema(type);
            if (schema == null) {
                continue;
            }
            String tc = schema.getTestClass();
            String gc = schema.getGuiClass();
            if (tc == null || tc.isEmpty() || gc == null || gc.isEmpty()) {
                System.out.printf("MISSING [type=%s]: testClass=%s, guiClass=%s%n",
                        type, tc, gc);
                missing++;
            }
        }

        if (missing == 0) {
            System.out.println("OK: all schemas have testClass + guiClass.");
        } else {
            System.out.println("FAIL: " + missing + " schema entries missing fields.");
            System.exit(1);
        }
    }
}
