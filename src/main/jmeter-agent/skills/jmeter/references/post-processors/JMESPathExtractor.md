# JMESPath Extractor

## Description

JMESPath Extractor uses JMESPath expressions to extract data from JSON responses. JMESPath is a powerful query language for JSON, ideal for complex filtering and transformations.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `JMESPathExtractor.refNames` | Yes | Variable name for extracted value | `user_id` |
| `JMESPathExtractor.jmesPathExprs` | Yes | JMESPath expression | `users[0].id` |
| `JMESPathExtractor.matchNumbers` | No | Which match to use | `1` |
| `JMESPathExtractor.defaultValues` | No | Default value if no match | `NOT_FOUND` |

## Usage Examples

### Example 1: Extract Simple Value

```
// Response: {"data":{"userId":123}}

create_jmeter_element with:
- elementType: "jmespathextractor"
- elementName: "提取用户ID"
- properties:
  - JMESPathExtractor.refNames: "user_id"
  - JMESPathExtractor.jmesPathExprs: "data.userId"
  - JMESPathExtractor.matchNumbers: "1"
```

### Example 2: Extract Array Item

```
// Response: {"users":[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]}

create_jmeter_element with:
- elementType: "jmespathextractor"
- elementName: "提取第一个用户ID"
- properties:
  - JMESPathExtractor.refNames: "first_user_id"
  - JMESPathExtractor.jmesPathExprs: "users[0].id"
```

### Example 3: Filter Array

```
// Response: {"orders":[{"status":"completed","total":100},{"status":"pending","total":50}]}

create_jmeter_element with:
- elementType: "jmespathextractor"
- elementName: "提取已完成订单总额"
- properties:
  - JMESPathExtractor.refNames: "completed_total"
  - JMESPathExtractor.jmesPathExprs: "orders[?status=='completed'].total | [0]"
```

### Example 4: Extract Multiple Fields

```
// Response: {"user":{"id":123,"name":"Alice","email":"alice@example.com"}}

create_jmeter_element with:
- elementType: "jmespathextractor"
- elementName: "提取用户信息"
- properties:
  - JMESPathExtractor.refNames: "user_info"
  - JMESPathExtractor.jmesPathExprs: "user.{id: id, name: name, email: email}"
  - JMESPathExtractor.matchNumbers: "1"

// Returns JSON: {"id":123,"name":"Alice","email":"alice@example.com"}
```

### Example 5: Pipe Expression

```
// Response: {"data":[1,2,3,4,5]}

create_jmeter_element with:
- elementType: "jmespathextractor"
- elementName: "获取第一个元素"
- properties:
  - JMESPathExtractor.refNames: "first_value"
  - JMESPathExtractor.jmesPathExprs: "data | [0]"
```

## JMESPath Syntax

| Expression | Description | Result |
|------------|-------------|--------|
| `user.name` | Field access | Value of `name` field |
| `users[0]` | Index | First item in `users` array |
| `users[*]` | All items | All items in `users` array |
| `users[?status=='active']` | Filter | Items where `status` equals `active` |
| `length(users)` | Function | Count of `users` array |
| `users[*].id` | Projection | All `id` values from `users` |
| `{newName: oldName}` | Multi-select | Rename field |
| `expr | [0]` | Pipe | Pass result to next expression |

## Common Patterns

### Extract Nested Field
```
{"a":{"b":{"c":"value"}}}
a.b.c
```

### Filter Array
```
{"items":[{"price":10},{"price":20}]}
items[?price >= `15`]
```

### Project Fields
```
{"user":{"id":1,"name":"A","age":25}}
user.{id: id, name: name}
```

### Get First Element
```
{"items":[1,2,3]}
items | [0]
```

### Flatten Array
```
{"data":[[1,2],[3,4]]}
data[]
```

### Length of Array
```
{"items":[1,2,3]}
length(items)
```

## Comparison: JMESPath vs JSONPath

| Feature | JMESPath | JSONPath |
|---------|----------|----------|
| Filtering | `[?expr]` | `[?(@.expr)]` |
| Projection | `{a: a, b: b}` | Not available |
| Pipe expressions | Yes | No |
| Functions | Built-in | Limited |
| Complexity | More powerful | Simpler |

## Best Practices

1. **Use for complex queries**: When JSONPath isn't enough
2. **Test expressions**: Use JMESPath online tester
3. **Pipe expressions**: Chain operations with `|`
4. **Quote strings**: Use backticks for string literals
5. **Handle arrays**: Use `[0]` for first element

## When to Use

### Use JMESPath When:
- Complex filtering needed
- Array transformations required
- Multiple field extraction
- Pipe operations useful

### Use JSONPath When:
- Simple field access
- Basic array filtering
- More familiar syntax
- Wider tool support

## Tips

1. **String literals**: Use backticks for strings (`'active'`)
2. **Numbers**: No quotes for numbers (`price >= 10`)
3. **Boolean**: Use `true`/`false` literals
4. **Test online**: Use jmespath.org for testing
5. **Debug sampler**: Verify extracted values

## Notes

- JMESPath is more powerful than JSONPath
- Supports complex queries and transformations
- Pipe expressions allow chaining
- Use for advanced JSON manipulation
