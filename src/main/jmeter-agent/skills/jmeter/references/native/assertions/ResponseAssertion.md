# Response Assertion
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The response assertion control panel lets you add pattern strings to be compared against various fields of the request or response. The pattern strings are:

- `Contains`, `Matches`: Perl5-style regular expressions
- `Equals`, `Substring`: plain text, case-sensitive

You can also choose whether the strings will be expected to **match** the entire response, or if the response is only expected to **contain** the pattern. You can attach multiple assertions to any controller for additional flexibility.

Note that the pattern string should not include the enclosing delimiters, i.e. use `Price: \d+` not `/Price: \d+/`.

By default, the pattern is in multi-line mode, which means that the `.` meta-character does not match newline. In multi-line mode, `^` and `$` match the start or end of any line anywhere within the string - not just the start and end of the entire string. Note that `\s` does match new-line. Case is also significant. To override these settings, use the extended regular expression syntax:

- `(?i)` - ignore case
- `(?s)` - treat target as single line, i.e. `.` matches new-line
- `(?is)` - both the above

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Assertion.scope` | No | `"parent"` | Scope of the assertion: parent (main sample only), all (main and sub-samples), children (sub-samples only), or variable | `"parent"` |
| `Scope.variable` | No | — | Name of JMeter variable to apply assertion to (only used when Assertion.scope is "variable") | `"myVar"` |
| `Assertion.test_field` | Yes | — | What to test: response data, response code, response message, response headers, request headers, sample label, response data as document, or request data | `"Assertion.response_code"` |
| `Assertion.test_type` | Yes | — | Type of comparison as integer (2=Contains, 1=Match, 8=Equals, 16=Substring, 4=Not, 32=Or; values can be combined with bitwise OR) | `"2"` |
| `Asserion.test_strings` | No | — | Multiple patterns to match (collection of strings) | `["200", "201"]` |
| `Assertion.assume_success` | No | `false` | Assume success status. When true, the Response status is forced to successful before evaluating the Assertion. | `"false"` |
| `Assertion.custom_message` | No | — | Custom failure message that replaces the generated one | `"Status code was not as expected"` |

### Assertion.scope Enum Values

| Value | Description |
|-------|-------------|
| `parent` | Main sample only |
| `all` | Main sample and sub-samples |
| `children` | Sub-samples only |
| `variable` | JMeter variable (requires Scope.variable) |

### Assertion.test_field Enum Values

| Value | Description |
|-------|-------------|
| `Assertion.response_data` | Text Response - the response body |
| `Assertion.response_code` | Response Code - e.g. 200 |
| `Assertion.response_message` | Response Message - e.g. OK |
| `Assertion.response_headers` | Response Headers |
| `Assertion.request_headers` | Request Headers |
| `Assertion.sample_label` | URL sampled / sample label |
| `Assertion.response_data_as_document` | Document (text) - extracted text via Apache Tika |
| `Assertion.request_data` | Request data - the request body |

### Assertion.test_type Values

| Value | Mode | Description |
|-------|------|-------------|
| `1` | Match | True if the whole text matches the regular expression pattern |
| `2` | Contains | True if the text contains the regular expression pattern |
| `4` | Not | Inverts the check result |
| `8` | Equals | True if the whole text equals the pattern string (case-sensitive) |
| `16` | Substring | True if the text contains the pattern string (case-sensitive) |
| `32` | Or | Apply patterns in OR combination instead of AND |

Values can be combined with bitwise OR. For example, `6` (2+4) = Contains + Not, `12` (8+4) = Equals + Not.

## Usage Examples

### Example 1: Check Response Code Equals 200

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_状态码200"
- properties:
  - Assertion.test_field: "Assertion.response_code"
  - Assertion.test_type: "8"
  - Asserion.test_strings:
    - "200"
```

### Example 2: Check Response Body Contains Text

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_包含success"
- properties:
  - Assertion.test_field: "Assertion.response_data"
  - Assertion.test_type: "2"
  - Asserion.test_strings:
    - "success"
```

### Example 3: Check Response Matches Regex Pattern

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_响应格式正确"
- properties:
  - Assertion.test_field: "Assertion.response_data"
  - Assertion.test_type: "1"
  - Asserion.test_strings:
    - "\\{\"status\":\"[^\"]+\",\"data\":\\{.*\\}\\}"
```

### Example 4: Check Response Headers

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_Content-Type正确"
- properties:
  - Assertion.test_field: "Assertion.response_headers"
  - Assertion.test_type: "2"
  - Asserion.test_strings:
    - "Content-Type: application/json"
```

### Example 5: Multiple Patterns with OR Logic

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_状态码为200或201"
- properties:
  - Assertion.test_field: "Assertion.response_code"
  - Assertion.test_type: "40"
  - Asserion.test_strings:
    - "200"
    - "201"
```

### Example 6: Inverted Check (Not Contains)

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_不包含error"
- properties:
  - Assertion.test_field: "Assertion.response_data"
  - Assertion.test_type: "6"
  - Asserion.test_strings:
    - "error"
```

### Example 7: Ignore Status with Custom Message

```
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_忽略状态后检查内容"
- properties:
  - Assertion.test_field: "Assertion.response_data"
  - Assertion.test_type: "2"
  - Assertion.assume_success: "true"
  - Assertion.custom_message: "Response body did not contain expected content"
  - Asserion.test_strings:
    - "expected_text"
```

## Best Practices

1. **Specific patterns**: Use specific text for reliable assertions rather than overly broad regex.
2. **Separate concerns**: Use separate assertions for different checks (status code vs body content).
3. **Descriptive names**: Name assertions clearly, e.g. "断言_状态码200".
4. **Choose correct test_field**: Match the right field type for your check.
5. **Test regex patterns**: Verify regex patterns in View Results Tree before using them.
6. **Use assume_success carefully**: Only set on the first assertion, as it clears previous assertion failures.

## Notes

- Pattern strings for `Contains` and `Matches` use Perl5-style regular expressions without enclosing delimiters.
- `Equals` and `Substring` use plain text comparison (case-sensitive).
- `NOT` (value 4) can be combined with other types using bitwise OR to invert the result.
- `OR` (value 32) applies each assertion in OR combination (if any pattern matches, assertion passes).
- Multiple patterns without OR use AND logic (all patterns must match for assertion to pass).
- The `Ignore Status` checkbox (assume_success) forces the response status to successful before evaluating; use only on the first assertion.
