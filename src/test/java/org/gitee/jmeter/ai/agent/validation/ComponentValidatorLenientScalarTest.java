package org.gitee.jmeter.ai.agent.validation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lenient scalar-string contract: literal "true"/"false" and numeric strings are
 * accepted for Boolean/numeric schema fields and normalized to the declared JSON
 * type. Unparseable strings and genuinely incompatible shapes still fail with a
 * precise error (expected type, received value, correct example).
 */
class ComponentValidatorLenientScalarTest {

    private static ComponentValidator validator;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Path refsDir = tempDir.resolve("jmeter/references/lenient");
        Files.createDirectories(refsDir);
        String yaml = """
                component:
                  type: lenient
                  name: Lenient Scalar Fixture
                  testClass: org.apache.jmeter.threads.ThreadGroup
                  guiClass: org.apache.jmeter.threads.gui.ThreadGroupGui
                  description: Fixture for lenient scalar-string acceptance

                properties:
                  - name: Fixture.flag
                    type: Boolean
                    default: false
                  - name: Fixture.count
                    type: Integer
                  - name: Fixture.bigCount
                    type: Long
                  - name: Fixture.ratio
                    type: Double
                  - name: Fixture.small
                    type: Float
                  - name: Fixture.mode
                    type: String
                    enum: ["a", "b"]
                  - name: Fixture.controller
                    type: Object
                    properties:
                      - name: LoopController.loops
                        type: Integer
                  - name: Fixture.rows
                    type: Array
                    itemClass: org.apache.jmeter.config.Argument
                    itemProperties:
                      - name: Row.enabled
                        type: Boolean
                      - name: Row.count
                        type: Integer
                  - name: Fixture.flags
                    type: Array
                    itemType: Boolean
                """;
        Files.writeString(refsDir.resolve("Lenient.schema.yaml"), yaml);
        validator = new ComponentValidator(new ComponentSchemaLoader(tempDir));
    }

    private static List<String> errorsOf(Map<String, Object> props) {
        return validator.validate("lenient", props).getErrors();
    }

    private static String joinErrors(Map<String, Object> props) {
        return String.join("\n", errorsOf(props));
    }

    // ---- Boolean: literal strings accepted, real incompatibles rejected ----

    @Test
    void booleanAcceptsLiteralStrings() {
        assertTrue(errorsOf(Map.of("Fixture.flag", "true")).isEmpty());
        assertTrue(errorsOf(Map.of("Fixture.flag", "TRUE")).isEmpty());
        assertTrue(errorsOf(Map.of("Fixture.flag", " false ")).isEmpty());
        assertTrue(errorsOf(Map.of("Fixture.flag", true)).isEmpty());
    }

    @Test
    void booleanRejectsUnparseableAndIncompatibleValues() {
        assertFalse(errorsOf(Map.of("Fixture.flag", "yes")).isEmpty());
        assertFalse(errorsOf(Map.of("Fixture.flag", 1)).isEmpty());
        assertFalse(errorsOf(Map.of("Fixture.flag", List.of(true))).isEmpty());
    }

    @Test
    void booleanStillRejectsVariableReference() {
        assertFalse(errorsOf(Map.of("Fixture.flag", "${flag}")).isEmpty());
    }

    // ---- Numeric strings accepted, garbage rejected ----

    @Test
    void numericStringsAccepted() {
        assertTrue(errorsOf(Map.of("Fixture.count", "50")).isEmpty());
        assertTrue(errorsOf(Map.of("Fixture.bigCount", "50")).isEmpty());
        assertTrue(errorsOf(Map.of("Fixture.ratio", "800.0")).isEmpty());
        assertTrue(errorsOf(Map.of("Fixture.small", "1.5")).isEmpty());
    }

    @Test
    void numericGarbageStillRejected() {
        assertFalse(errorsOf(Map.of("Fixture.count", "abc")).isEmpty());
        assertFalse(errorsOf(Map.of("Fixture.count", "50x")).isEmpty());
        assertFalse(errorsOf(Map.of("Fixture.ratio", "nope")).isEmpty());
    }

    @Test
    void numericVariableReferenceStillAccepted() {
        assertTrue(errorsOf(Map.of("Fixture.count", "${n}")).isEmpty());
    }

    // ---- Nested + array items follow the same contract ----

    @Test
    void nestedAndArrayItemStringsAccepted() {
        assertTrue(errorsOf(Map.of("Fixture.controller", Map.of("LoopController.loops", "10"))).isEmpty());
        assertTrue(errorsOf(Map.of("Fixture.rows", List.of(
                Map.of("Row.enabled", "false", "Row.count", "3")))).isEmpty(),
                () -> String.join("\n", errorsOf(Map.of("Fixture.rows", List.of(
                        Map.of("Row.enabled", "false", "Row.count", "3"))))));
        assertTrue(errorsOf(Map.of("Fixture.flags", List.of("true", "false"))).isEmpty());
    }

    @Test
    void arrayItemWrongTypeRejected() {
        assertFalse(errorsOf(Map.of("Fixture.rows", List.of(Map.of("Row.enabled", List.of(true))))).isEmpty());
    }

    @Test
    void updatePathSharesContract() {
        assertTrue(validator.validateUpdate("lenient", Map.of("Fixture.flag", "true")).isValid());
        assertTrue(validator.validateUpdate("lenient", Map.of("Fixture.count", "50")).isValid());
        assertFalse(validator.validateUpdate("lenient", Map.of("Fixture.flag", "yes")).isValid());
    }

    // ---- Normalization produces the real JSON types ----

    @Test
    void normalizeProducesTypedValues() {
        Map<String, Object> out = validator.normalizeScalarStrings("lenient", Map.of(
                "Fixture.flag", "true", "Fixture.count", "50", "Fixture.ratio", "800.0"));
        assertEquals(Boolean.TRUE, out.get("Fixture.flag"));
        assertEquals(50, out.get("Fixture.count"));
        assertEquals(800.0, out.get("Fixture.ratio"));
    }

    @Test
    void normalizeLeavesGarbageUntouched() {
        Map<String, Object> out = validator.normalizeScalarStrings("lenient", Map.of("Fixture.count", "abc"));
        assertEquals("abc", out.get("Fixture.count"));
    }

    // ---- Error text precision (the original TODO ask) ----

    @Test
    void typeErrorEchoesValueAndShowsWorkedExample() {
        String errors = joinErrors(Map.of("Fixture.flag", "yes"));
        assertTrue(errors.contains("Expected BOOLEAN"), errors);
        assertTrue(errors.contains("got String"), errors);
        assertTrue(errors.contains("received: yes"), errors);
        assertTrue(errors.contains("Example: {\"Fixture.flag\": true}"), errors);
    }

    @Test
    void doubleExampleRequiresDecimalPoint() {
        String errors = joinErrors(Map.of("Fixture.ratio", 800));
        assertTrue(errors.contains("e.g. 800.0"), errors);
        assertTrue(errors.contains("Example: {\"Fixture.ratio\": 800.0}"), errors);
    }

    @Test
    void longValueEchoIsBounded() {
        String longValue = "x".repeat(500);
        String errors = joinErrors(Map.of("Fixture.count", longValue));
        assertTrue(errors.contains("(truncated)"), errors);
        assertFalse(errors.contains(longValue), "raw long value must not be echoed unbounded");
    }

    @Test
    void numericHintDoesNotContradictLenientStringAcceptance() {
        // An earlier hint said "; plain strings are not" while the same sentence also said
        // quoted strings are accepted and normalized. Both numeric and Boolean hints must
        // state the lenient contract without the contradictory clause.
        String numeric = joinErrors(Map.of("Fixture.count", "abc"));
        assertFalse(numeric.contains("plain strings are not"), numeric);
        assertTrue(numeric.contains("equivalent quoted string is also accepted and normalized"), numeric);

        String bool = joinErrors(Map.of("Fixture.flag", "yes"));
        assertFalse(bool.contains("plain strings are not"), bool);
        assertTrue(bool.contains("equivalent quoted string is also accepted and normalized"), bool);
    }
}
