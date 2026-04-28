# Skill Validation Checklist

Validate each item after creating or updating a skill.

## Frontmatter

- [ ] Enclosed in `---` delimiters
- [ ] `name` field exists and is a string
- [ ] `description` field exists and is a string
- [ ] `name` is hyphen-case: lowercase letters, digits, and hyphens only
- [ ] `name` length ≤ 64 characters
- [ ] `name` matches directory name exactly
- [ ] `description` contains no TODO placeholder text
- [ ] `description` contains no angle brackets (`<` or `>`)
- [ ] `description` length ≤ 1024 characters
- [ ] `description` includes both WHAT (functionality) and WHEN (trigger scenarios)
- [ ] `always` is a boolean value (if present)
- [ ] `requires` format is correct: `{"bins": ["..."], "env": ["..."]}` (if present)
- [ ] No unrecognized frontmatter keys

## Directory Structure

- [ ] Root contains only: `SKILL.md`, `references/`, `scripts/`, `assets/`
- [ ] No extraneous files (README.md, CHANGELOG.md, etc.)
- [ ] Directory name matches `name` field

## Body Content

- [ ] SKILL.md body ≤ 500 lines (split to references/ if exceeded)
- [ ] No redundant "when to use" info (belongs in description)
- [ ] References to files in references/ include when to load them
- [ ] Code examples and property names are accurate
- [ ] No duplicated content between SKILL.md and references/

## Reference Files

- [ ] Documentation files use PascalCase naming (e.g., `JSONPathAssertion.md`)
- [ ] Schema files named as `{ComponentName}.schema.yaml`
- [ ] Schema files contain `component` and `properties` top-level nodes
- [ ] Property definitions include `name`, `type`, and `required` fields
- [ ] Type values are within allowed set: String, Integer, Boolean, Number, Object, Array

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Name contains uppercase | Convert to all lowercase |
| Name contains spaces or underscores | Replace with hyphens |
| Description too short | Add WHEN trigger scenarios |
| Body too long without splitting | Split into references/ files |
| References not mentioned in SKILL.md | Add file references with loading guidance |
| Schema missing `type` | Add type to every property |
