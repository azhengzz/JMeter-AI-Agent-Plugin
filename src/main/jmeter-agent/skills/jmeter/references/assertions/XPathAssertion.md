# XPath Assertion

## Description

XPath Assertion validates XML or HTML responses using XPath expressions. It checks if XML structure exists and optionally validates its value.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `XPathAssertion.xpath` | Yes | XPath expression | `//user/@id` |
| `XPathAssertion.valid` | No | Expected value (optional) | `123` |

## Usage Examples

### Example 1: Check Element Exists

```
// Response: <user id="123"><name>Alice</name></user>

create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户元素存在"
- properties:
  - XPathAssertion.xpath: "//user"
```

### Example 2: Check Attribute Value

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户ID为123"
- properties:
  - XPathAssertion.xpath: "//user/@id"
  - XPathAssertion.valid: "123"
```

### Example 2: Check Element Text

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户名为Alice"
- properties:
  - XPathAssertion.xpath: "//user/name/text()"
  - XPathAssertion.valid: "Alice"
```

### Example 4: Check Nested Element

```
// Response: <response><data><status>success</status></data></response>

create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_状态为success"
- properties:
  - XPathAssertion.xpath: "//data/status/text()"
  - XPathAssertion.valid: "success"
```

### Example 5: Count Elements

```
// Response: <items><item>A</item><item>B</item><item>C</item></items>

create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_有3个项目"
- properties:
  - XPathAssertion.xpath: "count(//items/item)"
  - XPathAssertion.valid: "3"
```

### Example 6: Check Presence of Element

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_包含错误元素"
- properties:
  - XPathAssertion.xpath: "//error"
```

## XPath Syntax

| Expression | Description | Result |
|------------|-------------|--------|
| `//element` | Anywhere in document | All matching elements |
| `/root/child` | Absolute path | Child of root |
| `@attribute` | Attribute | Attribute value |
| `text()` | Element text | Text content |
| `[n]` | Nth element | Nth matching element |
| `[@attr='val']` | Attribute filter | Elements with matching attribute |

## Common Patterns

### Element Exists
```
XPath: //user
Valid: (empty - just check existence)
```

### Attribute Value
```
XPath: //user/@id
Valid: 123
```

### Element Text
```
XPath: //status/text()
Valid: success
```

### Nested Element
```
XPath: //data/user/name/text()
Valid: Alice
```

### Count Elements
```
XPath: count(//item)
Valid: 5
```

### With Condition
```
XPath: //user[@status='active']/@id
Valid: 123
```

## Best Practices

1. **Test XPath first**: Verify expressions in View Results Tree
2. **Specific paths**: Use specific paths for reliability
3. **Tolerant parsing**: May need for HTML responses
4. **Whitespace handling**: Text() includes whitespace
5. **Namespace issues**: May need to ignore namespaces

## Tips

1. **Simple checks**: Start with existence checks
2. **Attribute checks**: Use @attribute syntax
3. **Text content**: Use text() for element content
4. **Counting**: Use count() for array validation
5. **Conditions**: Use predicates for filtering

## Common Issues

### Issue: XPath Not Found
**Cause**: Incorrect XPath or namespace issue
**Solution**: Test in View Results Tree XPath Tester

### Issue: Whitespace in Text
**Cause**: text() includes surrounding whitespace
**Solution**: Use normalize-space() or trim

### Issue: Namespace Problems
**Cause**: XML has namespaces that affect XPath
**Solution**: Disable namespace awareness if possible

## Comparison with Other Assertions

| Assertion | Use Case |
|-----------|----------|
| XPath Assertion | XML/HTML validation |
| JSON Path Assertion | JSON validation |
| Response Assertion | General text validation |

## Example: Complete XML API Validation

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_用户信息XML"

// Check status code
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_状态码200"
- properties:
  - Assertion.test_string: "200"

// Check response structure
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_包含用户元素"
- properties:
  - XPathAssertion.xpath: "//user"

// Check specific value
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户名"
- properties:
  - XPathAssertion.xpath: "//user/name/text()"
  - XPathAssertion.valid: "Alice"

// Check response size
create_jmeter_element with:
- elementType: "sizeassertion"
- elementName: "断言_响应非空"
- properties:
  - SizeAssertion.size: "0"
  - SizeAssertion.operator: ">"
```

## Notes

- Validates XML/HTML structure
- Uses XPath 1.0 expressions
- Can check existence or validate values
- Namespace-aware by default
- Use with XML APIs or HTML content
