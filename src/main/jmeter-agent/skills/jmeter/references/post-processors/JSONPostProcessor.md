# JSON Path Extractor

## Description

JSON Path Extractor parses JSON responses and extracts values using JSONPath expressions. It's the recommended way to extract data from JSON API responses.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `JSONPostProcessor.refNames` | Yes | Variable name for extracted value | `token` |
| `JSONPostProcessor.jsonPathExprs` | Yes | JSONPath expression | `$.access_token` |
| `JSONPostProcessor.matchNumbers` | No | Which match to use (0=random, 1=first) | `1` |
| `JSONPostProcessor.defaultValues` | No | Default value if no match | `NOT_FOUND` |

## Usage Examples

### Example 1: Extract Token from Response

```
// Response: {"access_token":"abc123xyz","token_type":"Bearer"}

create_jmeter_element with:
- elementType: "jsonpathextractor"
- elementName: "提取Token"
- properties:
  - JSONPostProcessor.refNames: "access_token"
  - JSONPostProcessor.jsonPathExprs: "$.access_token"
  - JSONPostProcessor.matchNumbers: "1"

// Use in subsequent request
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "设置认证头"
- properties:
  - HeaderManager.headers:
    - Authorization: "Bearer ${access_token}"
```

### Example 2: Extract Nested Value

```
// Response: {"data":{"user":{"id":123,"name":"Alice"}}}

create_jmeter_element with:
- elementType: "jsonpathextractor"
- elementName: "提取用户名"
- properties:
  - JSONPostProcessor.refNames: "username"
  - JSONPostProcessor.jsonPathExprs: "$.data.user.name"
  - JSONPostProcessor.matchNumbers: "1"
```

### Example 3: Extract Array Element

```
// Response: {"users":[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]}

create_jmeter_element with:
- elementType: "jsonpathextractor"
- elementName: "提取第一个用户"
- properties:
  - JSONPostProcessor.refNames: "first_user"
  - JSONPostProcessor.jsonPathExprs: "$.users[0].name"
  - JSONPostProcessor.matchNumbers: "1"
```

### Example 4: Extract All Array Items

```
// Response: {"items":[1,2,3,4,5]}

create_jmeter_element with:
- elementType: "jsonpathextractor"
- elementName: "提取所有项目"
- properties:
  - JSONPostProcessor.refNames: "item"
  - JSONPostProcessor.jsonPathExprs: "$.items[*]"
  - JSONPostProcessor.matchNumbers: "-1"

// Access as ${item_1}, ${item_2}, ${item_3}, etc.
// ${item_matchNr} contains the count
```

### Example 5: Extract with Filter

```
// Response: {"users":[{"id":1,"status":"active"},{"id":2,"status":"inactive"}]}

create_jmeter_element with:
- elementType: "jsonpathextractor"
- elementName: "提取活跃用户ID"
- properties:
  - JSONPostProcessor.refNames: "active_user_id"
  - JSONPostProcessor.jsonPathExprs: "$.users[?(@.status=='active')].id"
  - JSONPostProcessor.matchNumbers: "1"
```

## JSONPath Syntax

| Expression | Description | Example |
|------------|-------------|---------|
| `$` | Root node | `$` |
| `.` | Child operator | `$.user.name` |
| `[n]` | Array index | `$.users[0]` |
| `[*]` | All array items | `$.users[*].name` |
| `..` | Recursive descent | `$..name` |
| `[?]` | Filter expression | `$[?(@.age > 18)]` |
| `[start:end]` | Array slice | `$[0:5]` |

## Common Patterns

### Extract Root Value
```
{"status":"success"}
$.status
```

### Extract Nested Value
```
{"data":{"user":{"id":123}}}
$.data.user.id
```

### Extract Array Item
```
{"items":[{"name":"A"},{"name":"B"}]}
$.items[0].name
```

### Extract All Array Items
```
{"items":[1,2,3]}
$.items[*]
```

### Filter Array
```
{"users":[{"age":20},{"age":30}]}
$.users[?(@.age > 25)]
```

### Extract by Key Name
```
{"data":{"user_id":123}}
$..user_id
```

## Variables Created

For reference name `token`:
- `${token}`: Extracted value
- `${token_1}`: First match (with matchNumbers=-1)
- `${token_n}`: Nth match
- `${token_matchNr}`: Count of matches
- `${token_ALL}`: All matches concatenated

## Best Practices

1. **Use JSONPath for JSON**: Prefer over Regex for JSON responses
2. **Test expression**: Verify with View Results Tree
3. **Specific paths**: Use specific paths for better performance
4. **Default values**: Set default for missing data handling
5. **Array filters**: Use filter expressions for complex conditions

## Comparison with Regex

| Feature | JSONPath Extractor | Regex Extractor |
|---------|-------------------|-----------------|
| JSON format | Optimized | General purpose |
| Performance | Faster | Slower |
| Complexity | Simpler syntax | Complex patterns |
| Maintenance | Easier | Harder |
| Reliability | More reliable | Fragile |

## Debugging

Use Debug Sampler to view extracted values:
```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "查看提取的变量"
- properties:
  - displayJMeterVariables: "true"
```

## Notes

- Parses JSON response body only
- Case-sensitive matching
- Handles nested objects and arrays
- Supports filter expressions
- More reliable than Regex for JSON data
