# JSON Path Assertion

## Description

JSON Path Assertion validates JSON responses using JSONPath expressions. It checks if the JSON data exists and optionally validates its value.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `JSON_PATH_ASSERTION.jsonPath` | Yes | JSONPath expression | `$.status` |
| `JSON_PATH_ASSERTION.expectedValue` | No | Expected value (optional) | `success` |
| `JSON_PATH_ASSERTION.expectNull` | No | Expect null value | `false` |
| `JSON_PATH_ASSERTION.validateAsJSON` | No | Validate expected value as JSON | `false` |

## Usage Examples

### Example 1: Check Field Exists

```
// Response: {"status":"success","data":{...}}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_status字段存在"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.status"
```

### Example 2: Check Field Value

```
create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_status等于success"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.status"
  - JSON_PATH_ASSERTION.expectedValue: "success"
```

### Example 3: Check Nested Value

```
// Response: {"data":{"user":{"id":123,"name":"Alice"}}}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_用户ID正确"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.data.user.id"
  - JSON_PATH_ASSERTION.expectedValue: "123"
```

### Example 4: Check Array Item

```
// Response: {"users":[{"name":"Alice"},{"name":"Bob"}]}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_第一个用户名"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.users[0].name"
  - JSON_PATH_ASSERTION.expectedValue: "Alice"
```

### Example 5: Check Array Count

```
// Response: {"items":[1,2,3,4,5]}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_项目数量"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.items.length()"
  - JSON_PATH_ASSERTION.expectedValue: "5"
```

### Example 6: Expect Null

```
// Response: {"data":null}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_data为null"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.data"
  - JSON_PATH_ASSERTION.expectNull: "true"
```

### Example 7: Validate JSON Value

```
// Response: {"config":{"theme":"dark","lang":"en"}}

create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_配置对象"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.config"
  - JSON_PATH_ASSERTION.expectedValue: '{"theme":"dark"}'
  - JSON_PATH_ASSERTION.validateAsJSON: "true"
```

## JSONPath Syntax

| Expression | Description | Result |
|------------|-------------|--------|
| `$.field` | Root field | Field value |
| `$.parent.child` | Nested field | Nested value |
| `$..field` | Anywhere | All matching fields |
| `$.array[0]` | First item | First array element |
| `$.array[*]` | All items | All array elements |

## Common Patterns

### Field Exists
```
JSONPath: $.userId
Expected: (leave empty)
```

### Field Equals Value
```
JSONPath: $.status
Expected: success
```

### Nested Field
```
JSONPath: $.data.user.email
Expected: user@example.com
```

### Array Element
```
JSONPath: $.items[0].name
Expected: First Item
```

### Number Comparison
```
JSONPath: $.count
Expected: 10
```

### Boolean Check
```
JSONPath: $.active
Expected: true
```

## Validation Types

| Type | Description | Use Case |
|------|-------------|----------|
| Exist only | Field exists | Check structure |
| Expected value | Field equals value | Validate content |
| Null check | Field is null | Check missing data |
| JSON validation | Compare JSON objects | Complex validation |

## Best Practices

1. **Test JSONPath first**: Verify expression is correct
2. **Specific paths**: Use specific paths for reliability
3. **Type matching**: Ensure expected value type matches JSON
4. **Array bounds**: Check array length before accessing items
5. **Descriptive names**: Clear assertion names

## Tips

1. **Debug mode**: Use View Results Tree to test
2. **Simple first**: Start with existence checks
3. **Value types**: Numbers and booleans must match
4. **Array indexes**: Start from 0
5. **Null handling**: Use expectNull for null checks

## Common Issues

### Issue: Field Not Found
**Cause**: JSONPath is incorrect
**Solution**: Test JSONPath in View Results Tree

### Issue: Value Mismatch
**Cause**: Type or format mismatch
**Solution**: Match exact value (strings need quotes)

### Issue: Array Index Out of Bounds
**Cause**: Array smaller than expected
**Solution**: Check array length first

## Notes

- JSONPath validates response structure
- Expected value is string comparison by default
- Use validateAsJSON for object comparison
- Assertion fails if path doesn't exist (unless expectNull)
- More reliable than text assertions for JSON
