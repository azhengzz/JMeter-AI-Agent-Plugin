# JSON Path Assertion

## Description

JSON Path Assertion validates JSON responses using JSONPath expressions. It checks if the JSON data exists at the specified path and optionally validates its value against an expected value or regular expression pattern.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `JSON_PATH` | Yes | — | JSONPath expression to extract value from JSON response | `$.status` |
| `EXPECTED_VALUE` | No | — | The expected value to compare against. Used when JSONVALIDATION is true. Can be literal value or regex pattern. | `success` |
| `JSONVALIDATION` | No | `false` | Enable value validation. When true, validates the extracted value against EXPECTED_VALUE. | `true` |
| `EXPECT_NULL` | No | `false` | When true, asserts that the JSONPath result is null. | `false` |
| `INVERT` | No | `false` | Negate the assertion - causes assertion to fail if conditions are met, pass otherwise. | `false` |
| `ISREGEX` | No | `true` | Treat EXPECTED_VALUE as a regular expression pattern for matching. | `true` |

## Usage Examples

### Example 1: Check Field Exists

```
// Response: {"status":"success","data":{...}}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_status字段存在"
- properties:
  - JSON_PATH: "$.status"
```

### Example 2: Check Field Equals Value

```
create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_status等于success"
- properties:
  - JSON_PATH: "$.status"
  - EXPECTED_VALUE: "success"
  - JSONVALIDATION: "true"
  - ISREGEX: "false"
```

### Example 3: Check Nested Value with Regex

```
// Response: {"data":{"user":{"id":123,"name":"Alice"}}}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_用户ID为数字"
- properties:
  - JSON_PATH: "$.data.user.id"
  - EXPECTED_VALUE: "\\d+"
  - JSONVALIDATION: "true"
  - ISREGEX: "true"
```

### Example 4: Check Array Item

```
// Response: {"users":[{"name":"Alice"},{"name":"Bob"}]}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_第一个用户名"
- properties:
  - JSON_PATH: "$.users[0].name"
  - EXPECTED_VALUE: "Alice"
  - JSONVALIDATION: "true"
  - ISREGEX: "false"
```

### Example 5: Expect Null

```
// Response: {"data":null}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_data为null"
- properties:
  - JSON_PATH: "$.data"
  - EXPECT_NULL: "true"
```

### Example 6: Invert Assertion

```
// Assert that error field does NOT exist

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_无error字段"
- properties:
  - JSON_PATH: "$.error"
  - INVERT: "true"
```

## Best Practices

1. **Test JSONPath first**: Verify your JSONPath expression in View Results Tree before using it in assertions.
2. **Use specific paths**: Prefer `$.data.user.id` over `$..id` for reliability.
3. **Disable ISREGEX for exact matches**: Set `ISREGEX: "false"` when you want exact string comparison.
4. **Use INVERT for negative checks**: Use `INVERT: "true"` to assert that a field does NOT exist or does NOT match.
5. **Descriptive names**: Use clear assertion names like "断言_状态码为200".

## Notes

- When JSONVALIDATION is false, the assertion only checks if the JSONPath matches any element.
- When JSONVALIDATION is true, the extracted value is compared against EXPECTED_VALUE.
- ISREGEX defaults to true, so EXPECTED_VALUE is treated as a regex pattern unless explicitly set to false.
- EXPECT_NULL is used to specifically check for null values in the JSON response.
- The assertion fails if the JSONPath does not match any element (unless INVERT is true).
