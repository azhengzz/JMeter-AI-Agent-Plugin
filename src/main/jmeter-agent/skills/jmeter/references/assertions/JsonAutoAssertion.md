# JSON Auto Assertion

## Description

JSON Auto Assertion automatically compares the full JSON response against an expected JSON string. It uses JSONAssert for structural comparison and supports **regex patterns** in expected values using `/pattern/g` syntax.

This assertion is useful for validating the complete JSON response structure and values in one step, without writing individual JSONPath expressions for each field.

### Comparison Modes

The combination of `extensible` and `strictOrdering` determines the comparison mode:

| extensible | strictOrdering | Mode | Behavior |
|------------|---------------|------|----------|
| `true` | `false` | LENIENT | Allows extra fields, ignores order |
| `true` | `true` | STRICT_ORDER | Allows extra fields, enforces order |
| `false` | `false` | NON_EXTENSIBLE | No extra fields allowed, ignores order |
| `false` | `true` | STRICT | No extra fields allowed, enforces order |

### Regex in Expected Values

Use `/pattern/g` syntax in expected JSON values to match with regex:

```json
{
  "id": "/\\d{10}/g",
  "date": "/\\d{4}-\\d{2}-\\d{2}/g",
  "value": 10
}
```

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `jsonStr` | Yes | — | Expected JSON string to compare against. Supports regex patterns in values. | `'{"status":"success","id":"/\\d+/g"}'` |
| `extensible` | No | `true` | Allow extra fields in actual response not present in expected JSON. | `false` |
| `strictOrdering` | No | `false` | Enforce strict field ordering. | `false` |

## Usage Examples

### Example 1: Basic JSON Comparison (Lenient)

```
create_jmeter_element with:
- elementType: "jsonautoassertion"
- elementName: "断言_用户信息JSON"
- properties:
  - jsonStr: '{"name":"Alice","age":30}'
```

### Example 2: Strict Structure (No Extra Fields)

```
create_jmeter_element with:
- elementType: "jsonautoassertion"
- elementName: "断言_严格结构校验"
- properties:
  - jsonStr: '{"code":0,"message":"success","data":{}}'
  - extensible: false
```

### Example 3: With Regex Patterns

```
create_jmeter_element with:
- elementType: "jsonautoassertion"
- elementName: "断言_订单响应（正则）"
- properties:
  - jsonStr: '{"order_id":"/\\d{10,20}/g","create_time":"/\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}/g","status":"created"}'
```

### Example 4: Strict Mode (No Extra Fields + Strict Order)

```
create_jmeter_element with:
- elementType: "jsonautoassertion"
- elementName: "断言_严格全匹配"
- properties:
  - jsonStr: '{"id":1,"name":"test"}'
  - extensible: false
  - strictOrdering: true
```

### Example 5: Nested JSON

```
create_jmeter_element with:
- elementType: "jsonautoassertion"
- elementName: "断言_嵌套JSON响应"
- properties:
  - jsonStr: '{"code":0,"data":{"user":{"name":"Bob","email":"/.+@.+\\.com/g"}}}'
  - extensible: true
```

## Best Practices

1. **Use lenient mode by default**: Keep `extensible: true` to avoid brittle assertions when API adds new fields
2. **Use regex for dynamic fields**: Wrap dynamic values like IDs, timestamps, UUIDs in `/pattern/g`
3. **Keep expected JSON minimal**: Only include fields you actually need to verify
4. **Use strict mode for contracts**: Set `extensible: false` when testing API contracts that must not change
5. **Escape backslashes**: In JSON strings, use `\\d` for regex `\d`

## Notes

- Regex pattern format: `/your_regex/g` — the value must match this exact format
- When regex matching fails, the assertion reports the field name, expected value, and actual value
- Extra fields in the response (when `extensible: false`) are reported as "多余字段"
- Missing fields in the response are reported as "缺少字段"
- If the response is not valid JSON, the assertion fails with an error
