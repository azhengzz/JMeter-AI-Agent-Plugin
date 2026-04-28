---
name: skill-creator
description: Create or update JMeter AI AgentSkills. Use when designing, structuring, writing, or validating new SKILL.md skill packages with references, schemas, scripts, and assets.
---

# Skill Creator

This skill provides guidance for creating effective skills for the JMeter AI agent.

## About Skills

Skills are modular, self-contained packages that extend the agent's capabilities by providing specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific domains — they transform the agent from a general-purpose assistant into a specialist equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. **Specialized workflows** — Multi-step procedures for specific domains
2. **Tool integrations** — Instructions for working with JMeter elements and APIs
3. **Domain expertise** — Component schemas, naming conventions, best practices
4. **Bundled resources** — References, scripts, and assets for complex tasks

## Core Principles

### Conciseness is Key

The context window is a shared resource. Skills share it with the system prompt, conversation history, tool descriptions, and other skills' metadata.

**Default assumption: the agent is already very smart.** Only add context the agent doesn't already have. Challenge each piece of information: "Does the agent really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility:

- **High freedom (text-based instructions)** — When multiple approaches are valid ("how to organize a test plan")
- **Medium freedom (pseudocode or templates)** — When a preferred pattern exists but some variation is acceptable ("CSV Data Set Config template")
- **Low freedom (specific steps, exact property names)** — When operations are fragile and consistency is critical ("JMeter property names must match exactly")

### Progressive Disclosure

The JMeter AI skill system uses a three-level loading mechanism:

1. **Metadata (name + description)** — Always in context (~100 words)
2. **SKILL.md body** — Loaded when the skill triggers (< 500 lines recommended)
3. **Bundled resources** — Loaded by the agent as needed (unlimited)

## Anatomy of a Skill

Every skill consists of a required SKILL.md file and optional bundled resources:

```
skill-name/
├── SKILL.md              (required — skill definition)
├── references/           (optional — documentation loaded into context as needed)
│   ├── *.md              Component docs, API references, detailed guides
│   └── *.schema.yaml     Component parameter validation rules
├── scripts/              (optional — executable code)
└── assets/               (optional — templates, images, output resources)
```

### SKILL.md Format

```yaml
---
name: skill-name          # required, lowercase + digits + hyphens, max 64 chars
description: Skill desc   # required, includes WHAT and WHEN to use
always: false             # optional, auto-load into context when true
requires: {"bins": ["git"], "env": ["API_KEY"]}  # optional, runtime dependencies
---

# Skill Title

Body content (Markdown instructions and guidance)...
```

### Frontmatter Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Skill name in hyphen-case, must match directory name, max 64 chars |
| `description` | Yes | **Primary triggering mechanism** — includes WHAT the skill does and WHEN to use it |
| `always` | No | When `true`, always loaded into context (e.g., the `jmeter` skill); default `false` |
| `requires` | No | Runtime dependencies, e.g., `{"bins": ["python3"], "env": ["OPENAI_API_KEY"]}` |

### Bundled Resources

#### references/

Documentation loaded into context as needed to inform the agent's process.

- **When to include**: Component docs, API references, schema definitions, detailed workflow guides
- **Naming**: PascalCase for docs (`JSONPathAssertion.md`), `{Name}.schema.yaml` for schemas
- **Best practice**: For files > 10k words, include grep search patterns in SKILL.md
- **Avoid duplication**: Information should live in either SKILL.md or references, not both

#### scripts/

Executable code for deterministic, repeatable operations.

- **When to include**: When the same code is rewritten repeatedly or deterministic reliability is needed
- **Note**: The JMeter AI agent primarily uses tools; scripts serve as auxiliary automation

#### assets/

Files not loaded into context, but used within the output the agent produces.

- **When to include**: Templates, sample data, configuration files

#### What Not to Include

Do NOT create extraneous documentation:

- README.md, CHANGELOG.md, QUICK_REFERENCE.md, etc.
- Auxiliary context about the creation process
- Setup and testing procedures

A skill should only contain information needed for an AI agent to do the job at hand.

## Skill Creation Process

### Step 1: Understand the Skill with Concrete Examples

Gather concrete usage examples from the user:

- "What functionality should this skill support?"
- "Can you give some examples of how this skill would be used?"
- "What would a user say that should trigger this skill?"

Avoid asking too many questions in a single message. Start with the most important ones.

### Step 2: Plan Reusable Skill Contents

Analyze each usage example to identify needed resources:

- Information the agent will reference repeatedly → `references/`
- Parameters requiring strict validation → `*.schema.yaml`
- Operations needing precise templates → SKILL.md body

### Step 3: Initialize the Skill

Use file creation tools to create the directory and SKILL.md. See [skill-template.md](./references/skill-template.md) for ready-to-use templates.

**Deployment locations:**
- Built-in skills: `{JMETER_HOME}/bin/jmeter-agent/skills/{skill-name}/SKILL.md`
- Workspace skills: `{workspace}/skills/{skill-name}/SKILL.md`

Workspace skills take priority over built-in skills.

### Step 4: Edit the Skill

Consult [workflow-patterns.md](./references/workflow-patterns.md) to choose the right organizational pattern.

**Key principles:**
- Keep SKILL.md body under 500 lines
- Move detailed content to `references/`
- Reference all resource files from SKILL.md and describe when to load them
- Put all "when to use" information in `description` (the body is only loaded after triggering)

**Writing guidelines:** Always use imperative/infinitive form.

### Step 5: Validate the Skill

See [validation-checklist.md](./references/validation-checklist.md) for the complete checklist:

- Frontmatter format and required fields
- Directory name matches `name` field
- Description has no TODO placeholders, no angle brackets, length < 1024 chars
- Only allowed items in skill root: SKILL.md, references/, scripts/, assets/
- Schema files are well-formed (if any)

### Step 6: Iterate

After testing the skill on real tasks, refine based on what the agent struggled with.

## Skill Naming

- Lowercase letters, digits, and hyphens only (`api-autotest`)
- Max 64 characters
- Prefer short, verb-led phrases (`create-x`, `validate-y`)
- Namespace by tool when it improves clarity (`jmeter-http-request`)
- Name the skill folder exactly after the skill name

## Progressive Disclosure Patterns

When SKILL.md exceeds 500 lines, split content into references/:

**Pattern 1: By component category** (for JMeter component skills)
```
skill-name/
├── SKILL.md              # Overview + component index table
└── references/
    ├── controllers/      # Controller docs and schemas
    ├── samplers/         # Sampler docs and schemas
    └── assertions/       # Assertion docs and schemas
```

**Pattern 2: By domain** (for multi-domain skills)
```
skill-name/
├── SKILL.md              # Overview + navigation
└── references/
    ├── domain-a.md       # Domain A detailed guide
    └── domain-b.md       # Domain B detailed guide
```

**Pattern 3: By capability** (for multi-feature skills)
```
skill-name/
├── SKILL.md              # Core features + quick start
└── references/
    ├── advanced.md       # Advanced features (conditional loading)
    └── troubleshooting.md # Troubleshooting (on-demand loading)
```

**Important:** When splitting content, reference the files from SKILL.md and describe clearly when to read them. Keep references one level deep from SKILL.md.
