package org.gitee.jmeter.ai.agent.validation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Array item-shape validation: per-item type checks for itemType-declared properties
 * (strictness mirrors the scalar path — itemType:String rejects JSON numbers, numeric
 * itemTypes keep the ${...} exemption) and the no-declaration invariant (supplied values
 * for an Array without any item-shape declaration are refused as a schema gap).
 */
class ComponentValidatorArrayShapeTest {

    private static ComponentValidator validator;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Path refsDir = tempDir.resolve("jmeter/references/arrayshape");
        Files.createDirectories(refsDir);
        String yaml = """
                component:
                  type: arrayshape
                  name: Array Shape Fixture
                  testClass: org.apache.jmeter.threads.ThreadGroup
                  guiClass: org.apache.jmeter.threads.gui.ThreadGroupGui
                  description: fixture for item-shape validation

                properties:
                  - name: Arr.flat
                    type: Array
                    itemType: String
                    description: flat string collection

                  - name: Arr.nums
                    type: Array
                    itemType: Integer
                    description: flat numeric collection

                  - name: Arr.undeclared
                    type: Array
                    description: no item shape declared
                """;
        Files.writeString(refsDir.resolve("ArrayShape.schema.yaml"), yaml);
        validator = new ComponentValidator(new ComponentSchemaLoader(tempDir));
    }

    private static List<String> errorsOf(Map<String, Object> properties) {
        return validator.validate("arrayshape", properties).getErrors();
    }

    // ---- V1: object items rejected with flat-list guidance ----

    @Test
    void v1_objectItemsRejectedWithGuidance() {
        var errors = errorsOf(Map.of("Arr.flat", List.of(Map.of("TestElement.name", "123"))));
        assertFalse(errors.isEmpty());
        String joined = String.join("\n", errors);
        assertTrue(joined.contains("Arr.flat"), joined);
        assertTrue(joined.contains("flat list of string"), joined);
        assertTrue(joined.contains("item #1"), joined);
    }

    // ---- V2: flat strings pass (regression anchor) ----

    @Test
    void v2_flatStringsPass() {
        assertTrue(errorsOf(Map.of("Arr.flat", List.of("200", "201"))).isEmpty());
    }

    // ---- V3: numbers rejected for itemType:String — same strictness as scalar type: String ----

    @Test
    void v3_numbersRejectedForStringItemType() {
        var errors = errorsOf(Map.of("Arr.flat", List.of(123)));
        assertFalse(errors.isEmpty());
        String joined = String.join("\n", errors);
        assertTrue(joined.contains("item #1 is Integer"), joined);
    }

    // ---- V4: any offending item rejects the call ----

    @Test
    void v4_mixedItemsRejected() {
        assertFalse(errorsOf(Map.of("Arr.flat", List.of("ok", Map.of("a", "b")))).isEmpty());
    }

    // ---- V5: undeclared item shape + supplied value = schema-gap hard error ----

    @Test
    void v5_undeclaredShapeWithSuppliedValueRejected() {
        var errors = errorsOf(Map.of("Arr.undeclared", List.of("x")));
        assertFalse(errors.isEmpty());
        assertTrue(String.join("\n", errors).contains("item shape is not declared"),
                () -> String.join("\n", errors));
    }

    @Test
    void v5b_undeclaredShapeAbsentCausesNoError() {
        // the undeclared property simply not supplied — no invariant error
        assertTrue(errorsOf(Map.of("Arr.flat", List.of("x"))).isEmpty());
    }

    // ---- V6: numeric itemType inherits the ${...} exemption ----

    @Test
    void v6_numericItemTypeAcceptsVariableReference() {
        assertTrue(errorsOf(Map.of("Arr.nums", List.of("${threads}", 5))).isEmpty());
    }

    @Test
    void v6numeric_stringLiteralsStillRejectedForNumericItemType() {
        assertFalse(errorsOf(Map.of("Arr.nums", List.of("50"))).isEmpty());
    }

    // ---- update path shares the behavior ----

    @Test
    void updatePathRejectsObjectItems() {
        assertFalse(validator.validateUpdate("arrayshape",
                Map.of("Arr.flat", List.of(Map.of("a", "b")))).isValid());
        assertTrue(validator.validateUpdate("arrayshape",
                Map.of("Arr.flat", List.of("200"))).isValid());
    }
}
