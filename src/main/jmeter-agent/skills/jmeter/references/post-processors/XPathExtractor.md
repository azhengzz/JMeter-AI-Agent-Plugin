# XPath Extractor

## Description

XPath Extractor uses XPath expressions to extract data from XML or HTML responses. It's ideal for parsing XML API responses or HTML content.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `XPathExtractor.refName` | Yes | Variable name for extracted value | `order_id` |
| `XPathExtractor.xpathQuery` | Yes | XPath expression | `//order/@id` |
| `XPathExtractor.default` | No | Default value if no match | `NOT_FOUND` |
| `XPathExtractor.tolerant` | No | Use tolerant parsing for HTML | `true` |
| `XPathExtractor.namespace` | No | Enable namespace awareness | `false` |

## Usage Examples

### Example 1: Extract XML Attribute

```
// Response: <order id="12345" status="confirmed"/>

create_jmeter_element with:
- elementType: "xpathextractor"
- elementName: "提取订单ID"
- properties:
  - XPathExtractor.refName: "order_id"
  - XPathExtractor.xpathQuery: "//order/@id"
  - XPathExtractor.default: "NO_ID"
```

### Example 2: Extract Element Text

```
// Response: <response><status>success</status><message>Done</message></response>

create_jmeter_element with:
- elementType: "xpathextractor"
- elementName: "提取状态"
- properties:
  - XPathExtractor.refName: "status"
  - XPathExtractor.xpathQuery: "//response/status/text()"
```

### Example 3: Extract Nested Value

```
// Response: <user><profile><name>Alice</name><email>alice@example.com</email></profile></user>

create_jmeter_element with:
- elementType: "xpathextractor"
- elementName: "提取邮箱"
- properties:
  - XPathExtractor.refName: "email"
  - XPathExtractor.xpathQuery: "//user/profile/email/text()"
```

### Example 4: Extract from HTML

```
// Response: <html><body><a href="/user/123">Profile</a></body></html>

create_jmeter_element with:
- elementType: "xpathextractor"
- elementName: "提取链接"
- properties:
  - XPathExtractor.refName: "profile_link"
  - XPathExtractor.xpathQuery: "//a[@href]/@href"
  - XPathExtractor.tolerant: "true"  // Enable for HTML
```

### Example 5: Extract Multiple Values

```
// Response: <items><item id="1"/><item id="2"/><item id="3"/></items>

create_jmeter_element with:
- elementType: "xpathextractor"
- elementName: "提取所有ID"
- properties:
  - XPathExtractor.refName: "item_id"
  - XPathExtractor.xpathQuery: "//item/@id"

// Access as ${item_id_1}, ${item_id_2}, ${item_id_3}
// ${item_id_matchNr} contains count
```

## XPath Syntax

| Expression | Description | Example |
|------------|-------------|---------|
| `/` | Root element | `/root/child` |
| `//` | Anywhere in document | `//user` |
| `@attr` | Attribute | `//user/@id` |
| `text()` | Element text | `//name/text()` |
| `[n]` | Nth element | `//item[1]` |
| `[@attr='val']` | Attribute filter | `//user[@id='123']` |
| `[contains()]` | Contains text | `//a[contains(text(),'Login')]` |

## Common Patterns

### Extract Attribute
```
<user id="123"/>
//user/@id
```

### Extract Text
```
<name>Alice</name>
//name/text()
```

### Extract with Attribute Filter
```
<user type="admin"><name>Bob</name></user>
//user[@type='admin']/name/text()
```

### Extract Nth Element
```
<items><item>A</item><item>B</item></items>
//items/item[2]/text()
```

### Extract with Text Filter
```
<a class="btn">Submit</a>
//a[contains(@class,'btn')]/text()
```

## HTML vs XML

### XML Parsing
- Strict parsing required
- Namespaces respected
- Set `tolerant: false`

### HTML Parsing
- Set `tolerant: true` for lenient parsing
- Handles malformed HTML
- Ignores namespaces by default

## Best Practices

1. **Use tolerant mode**: Enable for HTML responses
2. **Test expressions**: Verify with View Results Tree
3. **Be specific**: More specific XPath = better reliability
4. **Default values**: Set default for missing data
5. **Namespace handling**: Disable for simple XML

## Debugging

Use Debug Sampler to view extracted values:
```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "查看提取的变量"
- properties:
  - displayJMeterVariables: "true"
```

## Comparison

| Extractor | Use Case |
|-----------|----------|
| XPath Extractor | XML/HTML responses |
| JSONPath Extractor | JSON responses |
| Regex Extractor | Any text (less reliable) |

## Tips

1. **Use XPath for XML**: More reliable than Regex
2. **Tolerant mode**: Essential for HTML parsing
3. **Simple paths**: Start simple, add complexity as needed
4. **View Results Tree**: Use XPath Tester to validate
5. **Namespace issues**: Set `namespace: false` if problems

## Notes

- Parses response body only
- Tolerant mode handles malformed HTML
- Multiple matches stored as `var_1`, `var_2`, etc.
- Use with XML APIs or HTML scraping
- XPath 1.0 supported (not XPath 2.0)
