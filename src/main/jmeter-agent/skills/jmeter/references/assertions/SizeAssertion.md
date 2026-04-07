# Size Assertion

## Description

Size Assertion validates that the response size meets specified criteria. It can check for exact size, size range, or compare to expected size.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `SizeAssertion.size` | Yes | Size to compare (bytes) | `1024` |
| `SizeAssertion.operator` | Yes | Comparison operator | `=`, `!=`, `>`, `<`, `>=`, `<=` |

## Usage Examples

### Example 1: Exact Size Match

```
create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应大小为100字节"
- properties:
  - SizeAssertion.size: "100"
  - SizeAssertion.operator: "="
```

### Example 2: Response Not Empty

```
create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应非空"
- properties:
  - SizeAssertion.size: "0"
  - SizeAssertion.operator: ">"
```

### Example 3: Response Under Limit

```
create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应小于10KB"
- properties:
  - SizeAssertion.size: "10240"
  - SizeAssertion.operator: "<"
```

### Example 4: Response Within Range

```
create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应大小在范围内"
- properties:
  - SizeAssertion.size: "100"
  - SizeAssertion.operator: ">"

create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应大小小于1MB"
- properties:
  - SizeAssertion.size: "1048576"
  - SizeAssertion.operator: "<"
```

## Operator Options

| Operator | Description | Example |
|----------|-------------|---------|
| `=` | Equals size | Size must be exactly 1024 bytes |
| `!=` | Not equal | Size must not be 1024 bytes |
| `>` | Greater than | Size must be > 100 bytes |
| `>=` | Greater or equal | Size must be >= 100 bytes |
| `<` | Less than | Size must be < 10000 bytes |
| `<=` | Less or equal | Size must be <= 10000 bytes |

## Size Calculations

| Unit | Bytes |
|------|-------|
| 1 KB | 1024 |
| 1 MB | 1,048,576 |
| 10 MB | 10,485,760 |
| 100 MB | 104,857,600 |

## Use Cases

### 1. Validate Non-Empty Response
```
Ensure API returns data
Size: > 0
```

### 2. Check Response Size Limit
```
Ensure response isn't too large
Size: < 1MB
```

### 3. Expected Response Size
```
Validate fixed-size responses
Size: = expected_bytes
```

### 4. Data Completeness
```
Ensure sufficient data returned
Size: > minimum_bytes
```

## Common Patterns

### Non-Empty Response
```
Operator: >
Size: 0
```

### Under 1MB
```
Operator: <
Size: 1048576
```

### At Least 100 Bytes
```
Operator: >=
Size: 100
```

### Exactly Expected Size
```
Operator: =
Size: ${expected_size}
```

## Best Practices

1. **Use ranges**: Prefer > or < over exact size
2. **Consider compression**: Responses may be compressed
3. **Dynamic content**: Allow size variance for dynamic data
4. **Baseline testing**: Establish normal size ranges first
5. **Combine with other assertions**: Use with content assertions

## Tips

1. **View Results Tree**: Check actual response size
2. **Compression aware**: Gzip affects response size
3. **Content variations**: Allow for dynamic content
4. **Header size**: Response includes headers
5. **Empty responses**: Use > 0 to detect empty

## Common Size Values by Content Type

| Content Type | Typical Size | Assertion |
|--------------|--------------|-----------|
| Simple JSON | 100-1000 bytes | > 100 |
| Large JSON array | 10KB-1MB | < 1048576 |
| Simple HTML | 1KB-10KB | > 1024 |
| Full HTML page | 50KB-500KB | < 512000 |
| Image file | Varies | Based on type |
| File download | File size | = expected |

## Example: Complete API Validation

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_用户列表"

// Check non-empty response
create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应非空"
- properties:
  - SizeAssertion.size: "0"
  - SizeAssertion.operator: ">"

// Check response under 100KB
create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应小于100KB"
- properties:
  - SizeAssertion.size: "102400"
  - SizeAssertion.operator: "<"

// Validate content
create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_包含用户数组"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.users"
```

## Notes

- Size is measured in bytes
- Includes headers + body
- Affected by compression (gzip)
- Use to detect empty responses
- Combine with content assertions for full validation
