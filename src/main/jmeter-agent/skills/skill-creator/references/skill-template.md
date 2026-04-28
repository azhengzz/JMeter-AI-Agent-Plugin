# Skill Templates

Ready-to-use templates for creating new skills.

## Basic Template

Create file: `{skill-name}/SKILL.md`

```markdown
---
name: {skill-name}
description: [Complete description of what the skill does and when to use it. Include both WHAT and WHEN — this is the only field the agent reads to decide whether to activate the skill.]
always: false
---

# {Skill Title}

## Overview

[1-2 sentences explaining what this skill enables]

## Workflow

1. [Step 1]
2. [Step 2]
3. [Step 3]

## Resources

- [Related docs](./references/xxx.md) — [When to load this file]
```

## Component Template with Schema

For skills involving JMeter components that need parameter validation.

Create file: `{skill-name}/SKILL.md`

```markdown
---
name: {skill-name}
description: [Describe component functionality and trigger scenarios]
always: false
---

# {Skill Title}

## Overview

[Brief description of the component's purpose]

## Component Reference

| elementType | Description | Docs | Schema |
|-------------|-------------|------|--------|
| `{elementtype}` | [Description] | [Docs](./references/{category}/{Name}.md) | [Schema](./references/{category}/{Name}.schema.yaml) |

## Usage Examples

### Scenario 1: [Common Scenario]

[Specific steps and parameter configuration]
```

## Schema File Template

Create file: `{skill-name}/references/{category}/{ComponentName}.schema.yaml`

```yaml
component:
  type: {elementType}
  name: "{Display Name}"
  description: "{Component description}"

properties:
  - name: "{property.name}"
    type: "String"              # String | Integer | Boolean | Number | Object | Array
    required: true
    default: ""
    description: "{Property description}"
    # Optional constraints:
    # enum: ["value1", "value2"]
    # min: 0
    # max: 100
    # pattern: "^[a-zA-Z]+$"
```

## Full Example: API Testing Skill

```markdown
---
name: api-testing
description: Generate and debug REST API test scripts. Use when users need to create API test scripts, configure HTTP requests, set up assertions, handle authentication, or debug API responses.
always: false
---

# API Testing

## Overview

Generate standardized API test scripts supporting the full RESTful testing lifecycle.

## Workflow

1. Analyze API documentation, extract request parameters and expected responses
2. Create thread group with concurrency settings
3. Add HTTP Sampler with URL, method, and body configuration
4. Configure Header Manager (Content-Type, Authorization, etc.)
5. Add JSON Assertions to validate response structure
6. Add JSON Extractors for dynamic data correlation
7. Add View Results Tree for debugging

## Authentication

### Bearer Token
```
Header Manager:
  Authorization: Bearer ${token}
```

### API Key
```
Header Manager:
  X-API-Key: ${api_key}
```

## Best Practices

- Name requests as `{METHOD}_{description}` (e.g., `POST_Login`)
- Use CSV Data Set Config for data parameterization
- Add assertions to all critical requests
- Correlate dynamic data via JSON Extractor
```

## Directory Structure Examples

### Simple Skill (guidance-only)

```
my-skill/
└── SKILL.md
```

### Medium Skill (with references)

```
my-skill/
├── SKILL.md
└── references/
    └── component-guide.md
```

### Complex Skill (with schemas and categorized references)

```
my-skill/
├── SKILL.md
└── references/
    ├── category-a/
    │   ├── ComponentA.md
    │   └── ComponentA.schema.yaml
    ├── category-b/
    │   ├── ComponentB.md
    │   └── ComponentB.schema.yaml
    └── best-practices.md
```
