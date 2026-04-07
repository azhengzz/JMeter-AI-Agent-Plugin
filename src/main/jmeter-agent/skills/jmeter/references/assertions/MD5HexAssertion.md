# MD5Hex Assertion

## Description

MD5Hex Assertion validates the MD5 checksum of response data. It's used to verify response integrity or ensure exact content match.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `MD5HexAssertion_MD5Hex` | Yes | Expected MD5 hex digest | `5d41402abc4b2a76b9719d911017c592` |

## Usage Examples

### Example 1: Verify Exact Response

```
create_jmeter_element with:
- elementType: "md5hexassertion"
- elementName: "断言_响应MD5匹配"
- properties:
  - MD5HexAssertion_MD5Hex: "5d41402abc4b2a76b9719d911017c592"
```

### Example 2: Verify Static Content

```
// For a known static response
create_jmeter_element with:
- elementType: "md5hexassertion"
- elementName: "断言_静态内容未变化"
- properties:
  - MD5HexAssertion_MD5Hex: "a1b2c3d4e5f6..."
```

### Example 3: Verify File Download

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "下载文件"

create_jmeter_element with:
- elementType: "md5hexassertion"
- elementName: "断言_文件完整性"
- properties:
  - MD5HexAssertion_MD5Hex: "${expected_md5}"
```

## How MD5 Works

1. Calculate MD5 hash of response data
2. Convert to hexadecimal string
3. Compare with expected MD5 value
4. Pass if match, fail if different

## Generating MD5 Values

### Using JMeter Functions
```
// In debug mode, use __md5 function
${__md5(test data)} = 5d41402abc4b2a76b9719d911017c592
```

### Using Online Tools
- MD5 generators available online
- Use to calculate expected checksum

### Using Command Line
```bash
echo -n "test data" | md5sum
```

## Use Cases

### 1. Exact Match Validation
```
Verify response hasn't changed
```

### 2. Regression Testing
```
Detect content changes
```

### 3. File Integrity
```
Verify downloaded file matches expected
```

### 4. Cache Validation
```
Check if cached content is valid
```

## Common Patterns

### Static Response
```
Known MD5 for specific response
```

### Variable MD5
```
MD5HexAssertion_MD5Hex: ${expected_checksum}
```

## Best Practices

1. **Know limitations**: MD5 has collisions (use SHA-256 for security)
2. **Static content**: Best for invariant responses
3. **Test data**: Use consistent test data
4. **Whitespace matters**: MD5 is sensitive to all differences
5. **Encoding aware**: Response encoding affects MD5

## Tips

1. **Generate first**: Run test once to get MD5
2. **Use constants**: Store MD5 in variables
3. **Compare output**: Use View Results Tree to see response
4. **Whitespace**: Be aware of trailing whitespace
5. **Headers included**: MD5 is of full response

## Limitations

| Issue | Description |
|-------|-------------|
| Dynamic content | Any change causes failure |
| Timestamps | Time-based data changes MD5 |
| Whitespace | Even whitespace affects MD5 |
| Encoding | Different encoding = different MD5 |
| Security | MD5 is cryptographically broken |

## When to Use

### Use MD5 Assertion When:
- Response should be exactly the same
- Testing for unintended changes
- Validating file downloads
- Content must match byte-for-byte

### Avoid When:
- Response contains timestamps
- Dynamic data in response
- Minor variations acceptable
- Need flexible validation

## Alternative: Size Assertion

For simpler validation:
```
Use Size Assertion to check response size
Less strict than MD5 match
```

## Notes

- Compares entire response MD5
- Very sensitive to any changes
- Use for static content validation
- Consider SHA-256 for security-sensitive cases
- Response headers excluded (only body)
