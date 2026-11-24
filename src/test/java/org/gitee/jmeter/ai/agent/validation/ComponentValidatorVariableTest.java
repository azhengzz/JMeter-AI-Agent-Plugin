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
 * Tests for ${...} variable-reference acceptance in ComponentValidator.
 *
 * Contract (type-driven, no per-field switch): numeric fields (Integer/Long/
 * Float/Double/Number) accept a "${...}" reference string in place of a JSON
 * number — the engine resolves it at run time (JMeter precompiles the whole
 * tree into FunctionProperty before running). Boolean/enum fields stay strict.
 * The Boolean exclusion is a PLUGIN POLICY, not an engine limitation: JMeter
 * resolves ${...} on boolean fields too when the value is a stringProp. Kept
 * strict because an unresolved variable silently reads false.
 * Plain numeric strings ("50") are accepted and normalized; garbage ("abc")
 * is rejected with guidance.
 */
class ComponentValidatorVariableTest {

    private static ComponentValidator validator;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Path refsDir = tempDir.resolve("jmeter/references/testvar");
        Files.createDirectories(refsDir);
        Path schemaFile = refsDir.resolve("TestVar.schema.yaml");
        String yaml = """
                component:
                  type: testvar
                  name: Test Var Component
                  testClass: org.apache.jmeter.threads.ThreadGroup
                  guiClass: org.apache.jmeter.threads.gui.ThreadGroupGui
                  description: Test fixture for variable-reference validation

                properties:
                  - name: TestVar.num
                    type: Integer
                    min: 1
                    description: numeric field, variable support by type

                  - name: TestVar.ratio
                    type: Double
                    description: floating point field, variable support by type

                  - name: TestVar.flag
                    type: Boolean
                    default: false
                    description: boolean field, strict

                  - name: TestVar.mode
                    type: String
                    enum: ["a", "b"]
                    description: enum field, strict

                  - name: TestVar.controller
                    type: Object
                    testClass: org.apache.jmeter.control.LoopController
                    properties:
                      - name: LoopController.loops
                        type: Integer
                        min: -1
                        description: nested numeric field
                """;
        Files.writeString(schemaFile, yaml);
        validator = new ComponentValidator(new ComponentSchemaLoader(tempDir));
    }

    private static List<String> errorsOf(Map<String, Object> properties) {
        return validator.validate("testvar", properties).getErrors();
    }

    private static String joinErrors(Map<String, Object> properties) {
        return String.join("\n", errorsOf(properties));
    }

    // ---- numeric fields: variable references accepted (type-driven) ----

    @Test
    void integerFieldAcceptsVariableReference() {
        assertTrue(errorsOf(Map.of("TestVar.num", "${threadNum}")).isEmpty());
    }

    @Test
    void integerFieldAcceptsFunctionReference() {
        assertTrue(errorsOf(Map.of("TestVar.num", "${__P(threads,10)}")).isEmpty());
    }

    @Test
    void integerFieldAcceptsJsonNumber() {
        assertTrue(errorsOf(Map.of("TestVar.num", 50)).isEmpty());
    }

    @Test
    void doubleFieldAcceptsVariableReference() {
        assertTrue(errorsOf(Map.of("TestVar.ratio", "${rate}")).isEmpty());
    }

    // ---- literal numeric strings are accepted and normalized; garbage is rejected ----

    @Test
    void numericFieldAcceptsPlainNumericString() {
        assertTrue(errorsOf(Map.of("TestVar.num", "50")).isEmpty());
    }

    @Test
    void numericFieldRejectsGarbageStringWithGuidance() {
        String errors = joinErrors(Map.of("TestVar.num", "abc"));
        assertFalse(errors.isEmpty());
        assertTrue(errors.contains("Only a JSON number"), errors);
        assertTrue(errors.contains("variable reference"), errors);
    }

    @Test
    void numericFieldRejectsGarbageString() {
        assertFalse(errorsOf(Map.of("TestVar.num", "abc")).isEmpty());
    }

    // ---- range checks: kept for numbers, skipped for references ----

    @Test
    void numericRangeStillEnforcedForNumbers() {
        assertFalse(errorsOf(Map.of("TestVar.num", 0)).isEmpty());
    }

    @Test
    void numericRangeSkippedForVariableReference() {
        assertTrue(errorsOf(Map.of("TestVar.num", "${v}")).isEmpty());
    }

    // ---- Boolean/enum: literal strings accepted, ${...} references still rejected ----

    @Test
    void booleanFieldRejectsReferenceWithHint() {
        String errors = joinErrors(Map.of("TestVar.flag", "${useFlag}"));
        assertFalse(errors.isEmpty());
        assertTrue(errors.contains("Only a JSON boolean (true/false) is accepted"
                + " (the equivalent quoted string is also accepted and normalized)"), errors);
    }

    @Test
    void booleanFieldAcceptsLiteral() {
        assertTrue(errorsOf(Map.of("TestVar.flag", true)).isEmpty());
        assertTrue(errorsOf(Map.of("TestVar.flag", "true")).isEmpty());
    }

    @Test
    void enumFieldAcceptsLiteral() {
        assertTrue(errorsOf(Map.of("TestVar.mode", "a")).isEmpty());
    }

    @Test
    void enumFieldRejectsReference() {
        assertFalse(errorsOf(Map.of("TestVar.mode", "${mode}")).isEmpty());
    }

    @Test
    void enumFieldRejectsUnknownLiteral() {
        assertFalse(errorsOf(Map.of("TestVar.mode", "zz")).isEmpty());
    }

    // ---- nested object properties follow the same rules ----

    @Test
    void nestedNumericAcceptsReference() {
        Map<String, Object> nested = Map.of("LoopController.loops", "${loops}");
        assertTrue(errorsOf(Map.of("TestVar.controller", nested)).isEmpty());
    }

    @Test
    void nestedNumericAcceptsPlainNumericString() {
        Map<String, Object> nested = Map.of("LoopController.loops", "10");
        assertTrue(errorsOf(Map.of("TestVar.controller", nested)).isEmpty());
    }

    // ---- update path (validateUpdate) shares the behavior ----

    @Test
    void updatePathAcceptsReferenceAndPlainNumericString() {
        assertTrue(validator.validateUpdate("testvar", Map.of("TestVar.num", "${n}")).isValid());
        assertTrue(validator.validateUpdate("testvar", Map.of("TestVar.num", "50")).isValid());
    }

    // ---- sanity: the fixture schema actually loaded ----

    @Test
    void fixtureSchemaLoaded() {
        assertTrue(validator.validate("testvar", Map.of("TestVar.num", 1)).isValid());
    }
}
