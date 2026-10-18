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
 * Tests for JMeter historical property-name quirk normalization wired into the
 * validator entry points.
 *
 * JMeter stores ResponseAssertion's pattern collection under the misspelled key
 * "Asserion.test_strings" (kept for jmx backward compatibility), and the schema
 * faithfully registers that key. Callers naturally send the correct spelling
 * "Assertion.test_strings" — validation must accept both spellings through
 * ComponentSchema.normalizePropertyName, while genuinely unknown names stay
 * rejected and type/required checks still see the normalized key.
 */
class ComponentValidatorQuirkNameTest {

    private static ComponentValidator validator;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Path refsDir = tempDir.resolve("jmeter/references/quirkassert");
        Files.createDirectories(refsDir);
        Path schemaFile = refsDir.resolve("QuirkAssert.schema.yaml");
        String yaml = """
                component:
                  type: quirkassert
                  name: Quirk Assertion Component
                  testClass: org.apache.jmeter.assertions.ResponseAssertion
                  guiClass: org.apache.jmeter.assertions.gui.AssertionGui
                  description: Fixture with a historically misspelled property key

                properties:
                  - name: Asserion.test_strings
                    type: Array
                    itemType: String
                    required: true
                    description: patterns, stored under JMeter's historical misspelling

                  - name: Assertion.test_field
                    type: String
                    enum: ["Assertion.response_code"]
                    description: scalar field for negative control
                """;
        Files.writeString(schemaFile, yaml);
        validator = new ComponentValidator(new ComponentSchemaLoader(tempDir));
    }

    // ---- the quirk: correct spelling accepted, like the stored one ----

    @Test
    void correctSpellingAcceptedByValidate() {
        assertTrue(validator.validate("quirkassert",
                Map.of("Assertion.test_strings", List.of("pattern"))).isValid());
    }

    @Test
    void storedSpellingStillAccepted() {
        assertTrue(validator.validate("quirkassert",
                Map.of("Asserion.test_strings", List.of("pattern"))).isValid());
    }

    @Test
    void correctSpellingAcceptedByValidateUpdate() {
        assertTrue(validator.validateUpdate("quirkassert",
                Map.of("Assertion.test_strings", List.of("pattern"))).isValid());
    }

    // ---- entry normalization reaches every sub-validation pass ----

    @Test
    void correctSpellingSatisfiesRequiredCheck() {
        // required is declared on the misspelled key; the correct spelling must satisfy it
        assertTrue(validator.validate("quirkassert",
                Map.of("Assertion.test_strings", List.of("p"))).isValid());
    }

    @Test
    void missingQuirkPropertyStillFailsRequired() {
        assertFalse(validator.validate("quirkassert",
                Map.of("Assertion.test_field", "Assertion.response_code")).isValid());
    }

    @Test
    void wrongTypeUnderCorrectSpellingRejected() {
        // type validation must also see the normalized key, not silently skip it
        assertFalse(validator.validate("quirkassert",
                Map.of("Assertion.test_strings", "plain-string")).isValid());
    }

    // ---- genuinely unknown names stay rejected ----

    @Test
    void unknownNameStillRejected() {
        var result = validator.validate("quirkassert", Map.of(
                "Assertion.test_strings", List.of("p"),
                "Assertion.no_such", "x"));
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("not defined")),
                () -> String.join("\n", result.getErrors()));
    }
}
