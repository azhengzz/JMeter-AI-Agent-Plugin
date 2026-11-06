package org.gitee.jmeter.ai.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the externally distributed {@code skills/jmeter-cli/} package against the defect classes
 * found in the 2026-11-05 audit (see docs/agent-sessions/2026-11-05-jmeter-cli-skill-optimization-final.md).
 *
 * <p>Unlike the rest of the suite this reads repository sources rather than compiled classes: the
 * package is prose shipped to third-party agents, so its correctness is a property of the text.
 * Each assertion pins one failure that actually made an external agent fail or silently mislead:
 * <ul>
 *   <li>a worked example that always errors ({@code find --query testplan}) — {@code testplan} is
 *       not a registry key, so an agent's first command died;</li>
 *   <li>"exit 0 means success" while {@code validate}/{@code run --mode smoke} exit 0 on FAIL —
 *       inverts the only verification signal the package has;</li>
 *   <li>no JMeter domain vocabulary at all (HTTP argument shapes, assertions, extractors);</li>
 *   <li>dangling relative links, which read as available knowledge but resolve to nothing.</li>
 * </ul>
 *
 * <p>Assertions are phrased as "must contain" rather than "must equal" so the package can be
 * rewritten freely as long as the guarantees survive.
 */
class JmeterCliSkillPackageTest {

    private static final Path PACKAGE_DIR = Paths.get("skills", "jmeter-cli");
    private static final Path SKILL = PACKAGE_DIR.resolve("SKILL.md");
    private static final Path REFERENCES = PACKAGE_DIR.resolve("references");

    @Test
    void packageShipsTheDocumentedReferences() {
        assertTrue(Files.isRegularFile(SKILL), "missing " + SKILL);
        for (String ref : List.of("cli-reference.md", "element-types.md",
                "property-recipes.md", "workflows.md")) {
            assertTrue(Files.isRegularFile(REFERENCES.resolve(ref)),
                    "missing reference: " + ref);
        }
    }

    /**
     * {@code testplan} is not in the element registry, so any example that runs
     * {@code find --searchBy elementType --query testplan} always fails. The text may still mention
     * the command — but only to warn about it, never inside a runnable block.
     */
    @Test
    void noRunnableExampleSearchesForTestplanAsAnElementType() throws IOException {
        String offending = "jmeter-cli find --searchBy elementType --query testplan";
        for (Path p : markdownFiles()) {
            String[] lines = Files.readString(p, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains(offending)) {
                    continue;
                }
                String line = lines[i];
                boolean isWarning = line.contains("always fails") || line.contains("always errors");
                assertTrue(isWarning, p + ":" + (i + 1)
                        + " uses the always-failing testplan search as a runnable example: " + line.trim());
            }
        }
    }

    /**
     * Both commands return {@code ToolResult.success(...)} regardless of the verdict, so a FAIL
     * still exits 0. Any statement that equates exit 0 with success must carry the caveat.
     */
    @Test
    void exitCodeZeroIsNeverDocumentedAsProofOfPass() throws IOException {
        String text = Files.readString(SKILL, StandardCharsets.UTF_8);
        assertFalse(text.contains("Exit code `0` means success"),
                "SKILL.md reintroduced the 'exit 0 == success' contract that validate/smoke violate");
        assertTrue(text.contains("Exit codes do not carry the verdict")
                        || text.contains("NOT that the plan is valid"),
                "SKILL.md must state that exit 0 does not imply a passing plan");
    }

    /** The domain vocabulary the internal agent has and the external package used to lack. */
    @Test
    void packageCarriesJmeterDomainVocabulary() throws IOException {
        String all = allMarkdown();
        assertTrue(countOccurrences(all, "HTTPsampler.Arguments") >= 3,
                "HTTP argument shapes (query/form/raw-body) must be documented");
        assertTrue(all.contains("postBodyRaw"), "raw JSON body recipe missing");
        assertTrue(all.contains("Asserion.test_strings") || all.contains("Assertion.test_strings"),
                "response assertion patterns must be documented");
        assertTrue(all.contains("HeaderManager.headers"), "header manager shape missing");
        assertTrue(all.contains("save_automatically_before_run"),
                "the run-saves-the-.jmx hazard must be documented");
        assertTrue(all.contains("No save command") || all.contains("no save command"),
                "the absence of a save command must be stated");
    }

    /** A skill that points at files it does not ship silently promises knowledge it cannot deliver. */
    @Test
    void linksResolveInsideThePackage() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path p : markdownFiles()) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            for (String target : extractMarkdownLinks(text)) {
                if (target.startsWith("http") || target.startsWith("#")) {
                    continue;
                }
                Path resolved = p.getParent().resolve(target).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(p + " -> " + target);
                }
            }
        }
        assertTrue(broken.isEmpty(), "dangling links in skill package: " + broken);
    }

    /** The repo's skill doctrine caps the always-loaded body at 500 lines. */
    @Test
    void skillBodyStaysWithinProgressiveDisclosureBudget() throws IOException {
        long lines = Files.readAllLines(SKILL, StandardCharsets.UTF_8).size();
        assertTrue(lines < 500, "SKILL.md is " + lines + " lines; split into references/ past 500");
    }

    /**
     * {@code update} runs the same unknown-parameter rejection as {@code create}
     * (UpdateJMeterElementTool -> validateUpdate -> validateUnknownParameters, aborting before any
     * write). Documenting it as an inert write taught agents the inverse failure model.
     */
    @Test
    void updateIsNotDocumentedAsSilentlyWritingUnknownProperties() throws IOException {
        String all = allMarkdown();
        assertFalse(all.contains("inert property"),
                "the package claims update writes unknown properties inertly; it rejects them");
        assertFalse(all.contains("inertly"),
                "the package claims unknown properties are written inertly");
    }

    /**
     * ComponentValidator.isValidType is exact: STRING requires a String, DOUBLE requires a
     * Double/Float. A JSON integer is rejected for a Double field, and a JSON number is rejected
     * for a String field. Both were documented the wrong way round.
     */
    @Test
    void valueTypeRulesDoNotPromiseCoercion() throws IOException {
        String all = allMarkdown();
        assertFalse(all.contains("and `500` is also accepted"),
                "claims a number is accepted for a String-typed field; the validator rejects it");
        assertTrue(all.contains("800.0") || all.contains("600.0"),
                "Double-typed fields need a documented decimal form (800.0, not 800)");
    }

    /** {@code __P} with no default yields the literal "1", and HTTPsampler.Files is lower-case. */
    @Test
    void jmeterGotchasAreStatedCorrectly() throws IOException {
        String all = allMarkdown();
        assertFalse(all.contains("${__P(token)}"),
                "${__P(name)} with no default returns the literal \"1\"; use ${__P(token,)}");
        assertFalse(all.contains("\"HTTPSampler.Files\""),
                "the file container is HTTPsampler.Files (lower-case s); HTTPSampler.Files is rejected");
        assertFalse(all.contains("${__P(data.dir)}"),
                "data.dir is not a configured JMeter property; use an absolute path");
    }

    // ---------- helpers ----------

    private static List<Path> markdownFiles() throws IOException {
        try (Stream<Path> s = Files.walk(PACKAGE_DIR)) {
            return s.filter(p -> p.toString().endsWith(".md")).collect(Collectors.toList());
        }
    }

    private static String allMarkdown() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : markdownFiles()) {
            sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Extracts link targets from {@code [text](target)}; ignores images and absolute URLs. */
    private static List<String> extractMarkdownLinks(String text) {
        List<String> targets = new ArrayList<>();
        int i = 0;
        while ((i = text.indexOf("](", i)) >= 0) {
            int start = i + 2;
            int end = text.indexOf(')', start);
            if (end < 0) {
                break;
            }
            String target = text.substring(start, end).trim();
            // strip an optional "title" suffix: (path "title")
            int space = target.indexOf(' ');
            if (space > 0) {
                target = target.substring(0, space);
            }
            if (!target.isEmpty()) {
                targets.add(target);
            }
            i = end;
        }
        return targets;
    }
}
