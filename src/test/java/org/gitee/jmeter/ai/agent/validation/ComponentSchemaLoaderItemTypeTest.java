package org.gitee.jmeter.ai.agent.validation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * itemType parsing and lint semantics for Array properties.
 *
 * Error forms (itemType on a non-Array property, typo/non-scalar value, conflict with a
 * container or table declaration) must leave itemType UNSET — the model state is the asserted
 * contract; the loader's accompanying log warnings are lint-only and not unit-asserted.
 */
class ComponentSchemaLoaderItemTypeTest {

    @TempDir
    static Path tempDir;

    private static ComponentSchemaLoader loader;

    @BeforeAll
    static void setUp() throws Exception {
        Path refsDir = tempDir.resolve("jmeter/references/itemtypetest");
        Files.createDirectories(refsDir);
        String yaml = """
                component:
                  type: itemtypetest
                  name: Item Type Test
                  testClass: org.apache.jmeter.threads.ThreadGroup
                  guiClass: org.apache.jmeter.threads.gui.ThreadGroupGui
                  description: itemType fixture

                properties:
                  - name: Ok.flat
                    type: Array
                    itemType: String
                    description: valid declaration

                  - name: Ok.nums
                    type: Array
                    itemType: Integer
                    description: numeric declaration

                  - name: Bad.onObject
                    type: String
                    itemType: String
                    description: itemType on non-Array — ignored

                  - name: Bad.typo
                    type: Array
                    itemType: Strng
                    description: misspelled value — ignored, not silently STRING

                  - name: Bad.nonscalar
                    type: Array
                    itemType: Object
                    description: non-scalar value — ignored

                  - name: Bad.conflictContainer
                    type: Array
                    testClass: org.apache.jmeter.config.Arguments
                    itemType: String
                    description: container declaration wins — itemType ignored

                  - name: Bad.conflictTable
                    type: Array
                    itemClass: org.apache.jmeter.config.Argument
                    itemType: String
                    itemProperties:
                      - name: Argument.name
                        type: String
                    description: table declaration wins — itemType ignored
                """;
        Files.writeString(refsDir.resolve("ItemTypeTest.schema.yaml"), yaml);
        loader = new ComponentSchemaLoader(tempDir);
    }

    private static ComponentSchema.PropertyDefinition prop(String name) {
        ComponentSchema schema = loader.loadSchema("itemtypetest");
        assertNotNull(schema, "fixture schema must load");
        ComponentSchema.PropertyDefinition propDef = schema.getProperty(name);
        assertNotNull(propDef, "property must exist: " + name);
        return propDef;
    }

    @Test
    void validStringDeclarationParsed() {
        assertEquals(ComponentSchema.PropertyType.STRING, prop("Ok.flat").getItemType());
        assertTrue(prop("Ok.flat").hasItemType());
    }

    @Test
    void validNumericDeclarationParsed() {
        assertEquals(ComponentSchema.PropertyType.INTEGER, prop("Ok.nums").getItemType());
    }

    @Test
    void itemTypeOnNonArrayIgnored() {
        assertFalse(prop("Bad.onObject").hasItemType(), "itemType must be ignored off Array properties");
    }

    @Test
    void typoValueIgnoredNotSilentlyString() {
        assertFalse(prop("Bad.typo").hasItemType(), "misspelled itemType must not fall back to STRING");
    }

    @Test
    void nonScalarValueIgnored() {
        assertFalse(prop("Bad.nonscalar").hasItemType(), "Object/Array are not valid itemType values");
    }

    @Test
    void containerConflictUnsetsItemType() {
        assertFalse(prop("Bad.conflictContainer").hasItemType(),
                "container className declares the item shape — itemType is ignored");
        assertEquals("org.apache.jmeter.config.Arguments", prop("Bad.conflictContainer").getClassName());
    }

    @Test
    void tableConflictUnsetsItemType() {
        assertFalse(prop("Bad.conflictTable").hasItemType(),
                "itemClass+itemProperties declare the item shape — itemType is ignored");
        assertTrue(prop("Bad.conflictTable").hasItemProperties());
    }

    // ---- real-schema regression: the three stock generic properties carry itemType ----

    @Test
    void realSchemas_declaredStringItemTypes() {
        ComponentSchemaLoader real = new ComponentSchemaLoader(Paths.get("src/main/jmeter-agent/skills"));
        assertEquals(ComponentSchema.PropertyType.STRING,
                real.loadSchema("responseassertion").getProperty("Asserion.test_strings").getItemType());
        assertEquals(ComponentSchema.PropertyType.STRING,
                real.loadSchema("modulecontroller").getProperty("ModuleController.node_path").getItemType());
        assertEquals(ComponentSchema.PropertyType.STRING,
                real.loadSchema("userparameters").getProperty("UserParameters.names").getItemType());
    }
}
