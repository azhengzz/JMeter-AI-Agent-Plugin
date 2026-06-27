# Variable Assertion
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

Variable Assertion validates JMeter variables and properties against expected values. It checks each row in the check table and compares the actual variable/property value with the expected value.

This assertion runs after each sampler and can verify that extracted variables (from JSON Extractor, Regex Extractor, etc.) or JMeter properties have the correct values.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `variablesCheckTable` | Yes | — | Table of variable/property check rules. Each row can check a variable, a property, or both. | (see examples) |

### variablesCheckTable Row Properties

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `variable` | No | `""` | JMeter variable name. Leave empty to skip variable check. | `"user_id"` |
| `property` | No | `""` | JMeter property name. Leave empty to skip property check. | `"server.port"` |
| `expect` | Yes | — | Expected value for the variable or property. | `"12345"` |

## Usage Examples

### Example 1: Assert Variable Value

```
create_jmeter_element with:
- elementType: "variableassertion"
- elementName: "断言_用户ID变量"
- properties:
  - variablesCheckTable:
    - variable: "user_id"
      expect: "1001"
```

### Example 2: Assert Multiple Variables

```
create_jmeter_element with:
- elementType: "variableassertion"
- elementName: "断言_登录状态变量"
- properties:
  - variablesCheckTable:
    - variable: "login_status"
      expect: "success"
    - variable: "token"
      expect: "${expected_token}"
    - variable: "user_role"
      expect: "admin"
```

### Example 3: Assert JMeter Property

```
create_jmeter_element with:
- elementType: "variableassertion"
- elementName: "断言_服务器配置"
- properties:
  - variablesCheckTable:
    - property: "server.mode"
      expect: "production"
```

### Example 4: Assert Both Variable and Property in One Row

```
create_jmeter_element with:
- elementType: "variableassertion"
- elementName: "断言_混合校验"
- properties:
  - variablesCheckTable:
    - variable: "env_name"
      property: "deploy.env"
      expect: "staging"
```

## Best Practices

1. **Check variables after extraction**: Place this assertion after the sampler that extracts the variable
2. **Use descriptive names**: Name the assertion to indicate what is being verified
3. **One row per check**: Each row should check a single variable or property for clarity
4. **Combine with extractors**: Use after JSON/Regex Extractor to validate extraction results

## Notes

- If both `variable` and `property` are filled in a row, the same `expect` value is used for both checks
- If a variable name is not found in the current thread context, the assertion fails with a "variable not found" message
- If a property name is not found, the assertion also fails
- Rows with empty `variable` and `property` fields are skipped
