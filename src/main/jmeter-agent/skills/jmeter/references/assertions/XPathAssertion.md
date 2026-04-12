# XPath Assertion

## Description

XPath Assertion validates XML or HTML responses using XPath expressions. It checks if XML structure exists and optionally validates its value.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `XPath.xpath` | Yes | XPath expression | `//user/@id` |
| `XPath.tolerant` | Yes | Use tolerant parsing for HTML | `true` |
| `XPath.negate` | No | Invert assertion (fail if found) | `false` |
| `XPath.whitespace` | No | Ignore element whitespace | `false` |
| `XPath.validate` | No | Validate XML against DTD | `false` |
| `XPath.namespace` | No | Enable namespace awareness | `false` |
| `XPath.quiet` | No | Suppress parser warnings | `false` |
| `XPath.report_errors` | No | Report parser errors | `false` |
| `XPath.show_warnings` | No | Show parser warnings | `false` |
| `Assertion.scope` | No | Scope: parent/all/children/variable | `parent` |
| `Scope.variable` | No | Variable name (if scope=variable) | - |

## Usage Examples

### Example 1: Check Element Exists

```
// Response: <user id="123"><name>Alice</name></user>

create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户元素存在"
- properties:
  - XPath.xpath: "//user"
  - XPath.tolerant: true
```

### Example 2: Check Attribute Value

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户ID为123"
- properties:
  - XPath.xpath: "//user/@id"
  - XPath.tolerant: true
```

### Example 3: Check Element Text

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户名为Alice"
- properties:
  - XPath.xpath: "//user/name/text()"
  - XPath.tolerant: true
```

### Example 4: Invert Assertion

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_不包含错误元素"
- properties:
  - XPath.xpath: "//error"
  - XPath.tolerant: true
  - XPath.negate: true
```

### Example 5: Check with Tolerant Parser for HTML

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_HTML包含div元素"
- properties:
  - XPath.xpath: "//div"
  - XPath.tolerant: true
  - XPath.namespace: false
```

## Property Prefix

**IMPORTANT**: XPath Assertion uses `XPath.` prefix, NOT `XPathExtractor.` or `XPathAssertion.`

| Component | Property Prefix |
|-----------|-----------------|
| XPath Assertion | `XPath.` |
| XPath Extractor | `XPathExtractor.` |

## Correct Usage

```yaml
# CORRECT - XPath Assertion
properties:
  - XPath.xpath: "//user"
  - XPath.tolerant: true
  - XPath.negate: false

# WRONG - Do NOT use XPathExtractor2 or XPathAssertion
properties:
  - XPathExtractor2.xpathQuery: "//user"  # WRONG!
  - XPathAssertion.xpath: "//user"          # WRONG!
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

## Best Practices

1. **Always set tolerant**: HTML responses usually need tolerant parsing
2. **Test XPath first**: Verify expressions in View Results Tree
3. **Specific paths**: Use specific paths for reliability
4. **Namespace issues**: Set `namespace: false` if having issues

## Common Issues

### Issue: XPath Not Found
**Cause**: Incorrect XPath or namespace issue  
**Solution**: Test in View Results Tree, set `namespace: false`

### Issue: Whitespace in Text
**Cause**: text() includes surrounding whitespace  
**Solution**: Use `normalize-space()` in XPath

### Issue: HTML Parsing Errors
**Cause**: HTML is not well-formed XML  
**Solution**: Set `tolerant: true` and `quiet: true`

## Comparison with Related Components

| Component | Property Prefix | Purpose |
|-----------|-----------------|---------|
| XPath Assertion | `XPath.` | Validate XML/HTML |
| XPath Extractor | `XPathExtractor.` | Extract values |
| JSON Path Assertion | `JSONPath.json` | Validate JSON |
| Response Assertion | `Assertion.test_` | Validate text |
