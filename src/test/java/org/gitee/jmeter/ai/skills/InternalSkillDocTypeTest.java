package org.gitee.jmeter.ai.skills;

import org.gitee.jmeter.ai.agent.validation.ComponentSchema;
import org.gitee.jmeter.ai.agent.validation.ComponentValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the IN-PLUGIN skill docs ({@code src/main/jmeter-agent/skills/jmeter/references/})
 * against type drift: worked examples must use field names that exist in a schema, and
 * their values must be representable in the schema-declared type.
 *
 * <p>Quoted strings are legal under the lenient scalar contract (e.g. {@code "true"} for a
 * Boolean field is accepted and normalized), so this test does NOT forbid quotes. It pins
 * the invariants that still matter:
 * <ul>
 *   <li>a Double/Float example must show a decimal-point form (a bare {@code 60} is not a
 *       valid Double even though a string {@code "60"} is coercible);</li>
 *   <li>a Boolean example must be {@code true}/{@code false}, not {@code 1}/{@code yes};</li>
 *   <li>numeric examples must be parseable to the declared type.</li>
 * </ul>
 *
 * <p>Only parameter-table Example cells and fenced {@code field: value} lines are checked.
 * Prose, Groovy/BeanShell script bodies, and {@code ${...}} expressions are skipped.
 * Fields whose declared type is String are excluded by construction (they are not in the
 * non-String field set), so their quoted examples are never flagged.
 */
class InternalSkillDocTypeTest {

    private static final Path SKILLS = Paths.get("src", "main", "jmeter-agent", "skills");
    private static final Path REFERENCES = SKILLS.resolve("jmeter").resolve("references");

    /** field name -> declared non-String type, aggregated across all 73 schemas. */
    private static final Map<String, ComponentSchema.PropertyType> FIELD_TYPES = new HashMap<>();
    /** field names declared as String in ANY schema (ambiguous names are skipped entirely). */
    private static final Set<String> STRING_FIELDS = new HashSet<>();

    @BeforeAll
    static void loadSchemas() {
        // Walk every schema file directly to collect all field declarations including
        // itemProperties / nested properties (the loader's cache is keyed by component type).
        try (Stream<Path> s = Files.walk(REFERENCES)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".schema.yaml")).toList()) {
                collectFromYaml(p);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan schemas", e);
        }
        // Names that appear as both String and non-String are ambiguous → skip to avoid
        // false positives (e.g. LoopController.loops: Integer in native, String in gitee-qa).
        FIELD_TYPES.keySet().removeAll(STRING_FIELDS);
    }

    /**
     * Read the YAML text (not the loader's cache) so we see every property declaration,
     * including those the loader may ignore (itemType conflicts, aliases, etc.).
     */
    private static void collectFromYaml(Path file) throws IOException {
        Pattern prop = Pattern.compile("^\\s*-?\\s*name:\\s*['\"]?([^'\"#\\n]+?)['\"]?\\s*$");
        Pattern type = Pattern.compile("^\\s*type:\\s*([A-Za-z_0-9]+)\\s*$");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String pendingName = null;
        for (String line : lines) {
            Matcher nm = prop.matcher(line);
            if (nm.matches()) {
                pendingName = nm.group(1).trim();
                continue;
            }
            Matcher tm = type.matcher(line);
            if (tm.matches() && pendingName != null) {
                String t = tm.group(1).toUpperCase();
                if (t.equals("STRING")) {
                    STRING_FIELDS.add(pendingName);
                } else if (t.equals("BOOLEAN") || t.equals("INTEGER") || t.equals("LONG")
                        || t.equals("FLOAT") || t.equals("DOUBLE") || t.equals("NUMBER")) {
                    FIELD_TYPES.putIfAbsent(pendingName, ComponentSchema.PropertyType.valueOf(t));
                }
                pendingName = null;
            }
        }
    }

    private static List<Path> markdownFiles() throws IOException {
        try (Stream<Path> s = Files.walk(REFERENCES)) {
            return s.filter(p -> p.toString().endsWith(".md")).toList();
        }
    }

    /** A field:value pair extracted from a table Example cell or a fenced code line. */
    private record Example(String file, int line, String field, String rawValue) {}

    private static final Pattern FENCED = Pattern.compile(
            "^\\s*-?\\s*[\"']?([A-Za-z][A-Za-z0-9_.\\-]*)[\"']?\\s*:\\s*(.+?)\\s*,?\\s*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|.*\\|$");

    private static List<Example> examplesOf(Path file) throws IOException {
        List<Example> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            String value = null;
            String field = null;
            if (inFence) {
                Matcher m = FENCED.matcher(line);
                if (m.matches()) {
                    field = m.group(1);
                    value = m.group(2);
                }
            } else if (TABLE_ROW.matcher(line).matches()) {
                // "| `Field` | ... | `\"value\"` |" → last cell is the Example column
                String[] cells = line.split("\\|", -1);
                if (cells.length >= 6) {
                    String first = cells[1].trim();
                    Matcher fn = Pattern.compile("`([A-Za-z][A-Za-z0-9_.\\-]*)`").matcher(first);
                    if (fn.find()) {
                        field = fn.group(1);
                        value = cells[cells.length - 2].trim();
                    }
                }
            }
            if (field == null || value == null || value.contains("${")) {
                continue;
            }
            out.add(new Example(file.getFileName().toString(), i + 1, field, value));
        }
        return out;
    }

    private static String unquote(String raw) {
        String v = raw.trim();
        // Strip markdown backticks and JSON quotes.
        v = v.replace("`", "").trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    @Test
    void everyTypedExampleIsConsistentWithItsSchemaType() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Path file : markdownFiles()) {
            for (Example ex : examplesOf(file)) {
                ComponentSchema.PropertyType type = FIELD_TYPES.get(ex.field());
                if (type == null || STRING_FIELDS.contains(ex.field())) {
                    continue;
                }
                String value = unquote(ex.rawValue());
                if (value.isEmpty() || value.startsWith("See ") || value.startsWith("e.g.")
                        || value.contains(" ") || value.startsWith("[") || value.startsWith("{")) {
                    continue; // descriptive cell / structured example, not a scalar literal
                }
                checked++;
                String where = ex.file() + ":" + ex.line() + " " + ex.field() + " = " + ex.rawValue();
                switch (type) {
                    case BOOLEAN -> {
                        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                            problems.add(where + " — Boolean example must be true/false");
                        }
                    }
                    case FLOAT, DOUBLE -> {
                        // A Double example must show a decimal-point form. Strings are coerced
                        // at runtime, but documentation should teach the canonical literal.
                        if (!value.contains(".") && !value.matches("-?\\d+[eE][-+]?\\d+")) {
                            problems.add(where + " — " + type + " example needs a decimal point (e.g. 60.0)");
                        } else if (!isNumeric(value)) {
                            problems.add(where + " — not a parseable " + type);
                        }
                    }
                    default -> {
                        if (!ComponentValidator.isCoercibleString(value, type)) {
                            problems.add(where + " — not a parseable " + type);
                        }
                    }
                }
            }
        }
        assertTrue(checked > 50, "guard must actually inspect examples; checked=" + checked);
        assertTrue(problems.isEmpty(),
                () -> "Typed skill-doc examples inconsistent with their schema:\n  " + String.join("\n  ", problems));
    }

    private static boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
