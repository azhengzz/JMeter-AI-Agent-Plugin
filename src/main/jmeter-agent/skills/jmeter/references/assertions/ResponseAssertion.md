# Response Assertion

## Description

Response Assertion validates server responses against expected criteria. It can check response code, message, headers, or body text.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `Assertion.test_field` | Yes | What to test | `Response Code`, `Response Data`, `Response Headers` |
| `Assertion.test_type` | Yes | Type of comparison | `Contains`, `Matches`, `Equals` |
| `Assertion.test_string` | Yes | Pattern to match | `200`, `success`, `.*Status.*OK.*` |

## Usage Examples

### Example 1: Check Response Code

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_状态码200"
- properties:
  - Assertion.test_field: "Response Code"
  - Assertion.test_type: "Equals"
  - Assertion.test_string: "200"
```

### Example 2: Check Response Text Contains

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_包含success"
- properties:
  - Assertion.test_field: "Response Data"
  - Assertion.test_type: "Contains"
  - Assertion.test_string: "success"
```

### Example 3: Check Response Matches Regex

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_响应格式正确"
- properties:
  - Assertion.test_field: "Response Data"
  - Assertion.test_type: "Matches"
  - Assertion.test_string: "\\{\"status\":\"[^\"]+\",\"data\":\\{.*\\}\\}"
```

### Example 4: Check Response Header

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_Content-Type正确"
- properties:
  - Assertion.test_field: "Response Headers"
  - Assertion.test_type: "Contains"
  - Assertion.test_string: "Content-Type: application/json"
```

### Example 5: Multiple Patterns (OR Logic)

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_状态码为200或201"
- properties:
  - Assertion.test_field: "Response Code"
  - Assertion.test_type: "Equals"
  - Assertion.test_string: "200"
  - Add more patterns: "201"
```

## Test Field Options

| Field | Description | Example |
|-------|-------------|---------|
| `Response Code` | HTTP status code | `200`, `404` |
| `Response Message` | HTTP status message | `OK`, `Not Found` |
| `Response Data` | Response body text | `{"status":"ok"}` |
| `Response Headers` | Response headers | `Content-Type: application/json` |
| `Request Data` | Request body | Sent request content |
| `Request Headers` | Request headers | Sent request headers |

## Test Type Options

| Type | Description | Example |
|------|-------------|---------|
| `Contains` | Response contains pattern | Response contains "success" |
| `Matches` | Response matches regex | Response matches `\d+` |
| `Equals` | Response equals pattern | Response equals "200" |
| `Substring` | Contains substring (case-insensitive) | Contains "SUCCESS" |
| `Not` | Inverts the test | Response does NOT contain "error" |

## Common Patterns

### Status Code Assertion
```
Field: Response Code
Type: Equals
Pattern: 200
```

### Success Message
```
Field: Response Data
Type: Contains
Pattern: "status":"success"
```

### JSON Structure
```
Field: Response Data
Type: Matches
Pattern: \{"id":\d+,"name":"[^"]+"\}
```

### Header Check
```
Field: Response Headers
Type: Contains
Pattern: Content-Type: application/json
```

### Error Message Not Present
```
Field: Response Data
Type: Contains
Pattern: error
Not: true
```

## Best Practices

1. **Specific patterns**: Use specific text for reliable assertions
2. **Multiple assertions**: Separate different checks into multiple assertions
3. **Descriptive names**: Name assertions clearly
4. **Test field selection**: Choose appropriate field (code/data/headers)
5. **Regex testing**: Test regex patterns before use

## Tips

1. **View Results Tree**: Use to test assertion patterns
2. **Start simple**: Begin with basic contains assertions
3. **Debug mode**: View assertion results in Listener
4. **Case sensitivity**: Contains is case-sensitive
5. **Escape special chars**: Escape regex special characters

## Assertion Result

In Listeners, assertion results show:
- ✓ Green: Assertion passed
- ✗ Red: Assertion failed
- Failure message shows expected vs actual

## Notes

- Assertions run after sampler completes
- Failed assertions mark sample as failed
- Can use regex in Matches pattern
- Multiple patterns = OR logic
- Not option inverts test result
