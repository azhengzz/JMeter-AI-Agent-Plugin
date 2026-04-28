# Skill Design Patterns

Proven organizational patterns for structuring skills. Choose based on the skill's primary purpose.

## Pattern 1: Workflow-Based

Best for sequential processes with clear step-by-step procedures.

```markdown
# {Skill Title}

## Workflow

1. **Requirements Analysis** — [what to do]
2. **Structure Design** — [what to do]
3. **Build and Execute** — [what to do]
4. **Verify and Optimize** — [what to do]

## Step 1: Requirements Analysis

[Specific guidance]

## Step 2: Structure Design

[Specific guidance]
```

**Use when:** Test plan creation, script generation, data processing pipelines

**Real example:** The `jmeter` skill in this project

## Pattern 2: Task-Based

Best for collections of independent operations.

```markdown
# {Skill Title}

## Quick Start

[Most common usage]

## Task A

[Complete guidance for operation A]

## Task B

[Complete guidance for operation B]

## Task C

[Complete guidance for operation C]
```

**Use when:** Tool collections, multi-function operation panels

## Pattern 3: Reference/Guidelines

Best for standards, specifications, or coding conventions.

```markdown
# {Skill Title}

## Overview

[1-2 sentences]

## Standard A

### Rules

- [Rule 1]
- [Rule 2]

### Examples

[Concrete examples]
```

**Use when:** Coding standards, naming conventions, testing standards

**Real example:** The `memory` skill in this project

## Pattern 4: Capabilities-Based

Best for integrated systems with multiple interrelated features.

```markdown
# {Skill Title}

## Core Capabilities

### 1. Capability A

[Description + usage]

### 2. Capability B

[Description + usage]

### 3. Capability C

[Description + usage]
```

**Use when:** API integrations, multi-tool coordination, compound workflows

**Real example:** The `api-autotest` skill in this project

## Pattern 5: Component Reference

Best for JMeter component documentation (the most common complex type).

```markdown
# {Skill Title}

## Overview

[Brief description]

## Component Reference

### Category A

| elementType | Description | Docs | Schema |
|-------------|-------------|------|--------|
| `{type}` | [Description] | [Link](./references/cat/Name.md) | [Schema](./references/cat/Name.schema.yaml) |

### Category B

[Same table format]

## Usage Examples

[Real-world scenarios using the components]

## Best Practices

[Lessons learned]
```

**Use when:** JMeter component guides, tool usage manuals

**Real example:** The `jmeter` skill (most complete example in this project)

## Mixing Patterns

Most real skills combine multiple patterns:

| Skill Type | Recommended Mix |
|-----------|----------------|
| Component docs | Pattern 5 + Pattern 1 |
| Script generation | Pattern 1 + Pattern 2 |
| Standards/specs | Pattern 3 + Pattern 5 |
| Integration tools | Pattern 4 + Pattern 2 |

## Selection Guide

```
Is there a clear step-by-step sequence?
├── Yes → Workflow-Based (Pattern 1)
└── No → Are there multiple independent operations?
    ├── Yes → Task-Based (Pattern 2)
    └── No → Is it a standard or specification?
        ├── Yes → Reference/Guidelines (Pattern 3)
        └── No → Is it an integrated system?
            ├── Yes → Capabilities-Based (Pattern 4)
            └── No → Component Reference (Pattern 5)
```
