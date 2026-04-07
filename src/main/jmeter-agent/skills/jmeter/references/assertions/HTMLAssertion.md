# HTML Assertion

## Description

HTML Assertion validates HTML responses using JTidy to check if HTML is well-formed or conforms to specific standards.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `HTMLAssertion.doctype` | No | Expected document type | `omit`, `auto`, `strict`, `loose` |
| `HTMLAssertion.htmlManager` | No | HTML settings | See JTidy settings |
| `HTMLAssertion.errorsThreshold` | No | Maximum errors allowed | `0` |
| `HTMLAssertion.warningThreshold` | No | Maximum warnings allowed | `0` |
| `HTMLAssertion.format` | No | Output format | `HTML`, `XHTML`, `XML` |

## Usage Examples

### Example 1: Validate HTML is Well-Formed

```
create_jmeter_element with:
- elementType: "htmlassertion"
- elementName: "断言_HTML格式正确"
- properties:
  - HTMLAssertion.doctype: "omit"
  - HTMLAssertion.errorsThreshold: "0"
  - HTMLAssertion.warningThreshold: "0"
```

### Example 2: Strict HTML Validation

```
create_jmeter_element with:
- elementType: "htmlassertion"
- elementName: "断言_严格HTML"
- properties:
  - HTMLAssertion.doctype: "strict"
  - HTMLAssertion.errorsThreshold: "0"
  - HTMLAssertion.warningThreshold: "0"
```

### Example 3: Allow Minor Warnings

```
create_jmeter_element with:
- elementType: "htmlassertion"
- elementName: "断言_HTML无错误"
- properties:
  - HTMLAssertion.doctype: "auto"
  - HTMLAssertion.errorsThreshold: "0"
  - HTMLAssertion.warningThreshold: "10"
```

### Example 4: XHTML Validation

```
create_jmeter_element with:
- elementType: "htmlassertion"
- elementName: "断言_XHTML格式"
- properties:
  - HTMLAssertion.doctype: "strict"
  - HTMLAssertion.format: "XHTML"
  - HTMLAssertion.errorsThreshold: "0"
```

## Document Type Options

| Doctype | Description |
|---------|-------------|
| `omit` | No DOCTYPE validation |
| `auto` | Auto-detect DOCTYPE |
| `strict` | HTML 4.01 Strict |
| `loose` | HTML 4.01 Transitional |
| `frameset` | HTML 4.01 Frameset |

## Validation Levels

| Level | Errors | Warnings | Use Case |
|-------|--------|----------|----------|
| Strict | 0 | 0 | Perfect HTML |
| Standard | 0 | >0 | Minor issues OK |
| Lenient | >0 | >0 | Debugging |

## Common HTML Issues Detected

| Issue | Description |
|-------|-------------|
| Unclosed tags | Missing closing tags |
| Mismatched tags | Opening/closing tag mismatch |
| Invalid nesting | Tags improperly nested |
| Missing attributes | Required attributes missing |
| Invalid attributes | Attribute values invalid |

## Use Cases

### 1. Validate HTML API Responses
```
Ensure HTML responses are well-formed
```

### 2. QA HTML Content
```
Check generated HTML quality
```

### 3. Regression Testing
```
Detect HTML corruption
```

### 4. Standards Compliance
```
Ensure HTML meets standards
```

## Best Practices

1. **Start lenient**: Allow warnings initially
2. **Tighten gradually**: Reduce thresholds over time
3. **Use appropriate doctype**: Match actual HTML version
4. **Check real HTML**: Test with actual responses
5. **Combine with other assertions**: Use with response assertions

## Tips

1. **View warnings**: Check assertion results for specific issues
2. **JTidy settings**: Configure JTidy for specific needs
3. **Real-world HTML**: Web HTML often has minor issues
4. **Debug mode**: Use View Results Tree to see validation output
5. **Threshold tuning**: Adjust based on requirements

## Common Issues

### Issue: Too Many Warnings
**Cause**: HTML not perfectly formed
**Solution**: Increase warning threshold or fix HTML

### Issue: Strict Validation Fails
**Cause**: HTML doesn't meet strict standards
**Solution**: Use loose doctype or omit validation

### Issue: Performance Impact
**Cause**: HTML parsing is expensive
**Solution**: Use selectively or disable in load tests

## Example: Complete Page Validation

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_首页"

// Check response code
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_状态码200"
- properties:
  - Assertion.test_string: "200"

// Validate HTML
create_jmeter_element with:
- elementType: "htmlassertion"
- elementName: "断言_HTML格式正确"
- properties:
  - HTMLAssertion.doctype: "auto"
  - HTMLAssertion.errorsThreshold: "0"
  - HTMLAssertion.warningThreshold: "5"

// Check for specific content
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_包含标题"
- properties:
  - Assertion.test_field: "Response Data"
  - Assertion.test_type: "Contains"
  - Assertion.test_string: "<title>"
```

## JTidy Configuration

Common JTidy settings:
- `indent-spaces`: Spaces per indent level
- `wrap`: Column to wrap at
- `uppercase-tags`: Uppercase tag names
- `numeric-entities`: Use numeric entities

## Notes

- Uses JTidy for HTML validation
- Can detect malformed HTML
- Performance overhead during validation
- Adjust thresholds based on requirements
- May need to be lenient for real-world HTML
