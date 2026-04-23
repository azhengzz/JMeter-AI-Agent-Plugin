# XPath Assertion

## Description

The XPath Assertion tests a document for well formedness, has the option of validating against a DTD, or putting the document through JTidy and testing for an XPath. If that XPath exists, the Assertion is true. Using `/` will match any well-formed document, and is the default XPath Expression. The assertion also supports boolean expressions, such as `count(//*error)=2`.

See http://www.w3.org/TR/xpath for more information on XPath.

Sample expressions:

- `//title[text()='Text to match']` - matches `<title>Text to match</title>` anywhere in the response
- `/title[text()='Text to match']` - matches `<title>Text to match</title>` at root level in the response

> **Note:** The non-tolerant parser can be quite slow, as it may need to download the DTD etc.

### NAMESPACES

As a work-round for namespace limitations of the Xalan XPath parser (implementation on which JMeter is based), you need to provide a Properties file which contains mappings for the namespace prefixes. For example, in a file named `namespaces.properties`:

```
prefix1=http\://foo.apache.org
prefix2=http\://toto.apache.org
```

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Assertion.scope` | No | `"parent"` | Scope: parent=main sample only, all=main+sub-samples, children=sub-samples only, variable=JMeter variable | `"parent"` |
| `Scope.variable` | No | — | Name of JMeter variable to validate (only used when scope=variable) | `"xmlResponse"` |
| `XPath.tolerant` | Yes | `false` | Use Tidy tolerant parser for HTML/XML (handles malformed HTML) | `"true"` |
| `XPath.xpath` | Yes | `"/"` | XPath expression to match in the document | `"//user"` |
| `XPath.negate` | No | `false` | Invert assertion (fail if XPath IS found, pass if NOT found) | `"false"` |
| `XPath.quiet` | No | `false` | Tidy quiet flag (suppress Tidy warnings). Only applies when tolerant=true | `"false"` |
| `XPath.report_errors` | No | `false` | Report Tidy parsing errors as assertion failures. Only applies when tolerant=true | `"false"` |
| `XPath.show_warnings` | No | `false` | Show Tidy warnings in assertion result. Only applies when tolerant=true | `"false"` |
| `XPath.namespace` | No | `false` | Enable namespace-aware parsing. Only applies when tolerant=false | `"false"` |
| `XPath.validate` | No | `false` | Validate XML against DTD/schema. Only applies when tolerant=false | `"false"` |
| `XPath.whitespace` | No | `false` | Ignore element whitespace in XML. Only applies when tolerant=false | `"false"` |
| `XPath.download_dtds` | No | `false` | Download external DTDs during validation. Only applies when tolerant=false | `"false"` |

### Assertion.scope Enum Values

| Value | Description |
|-------|-------------|
| `parent` | Main sample only |
| `all` | Main sample and sub-samples |
| `children` | Sub-samples only |
| `variable` | JMeter variable (requires Scope.variable) |

## Usage Examples

### Example 1: Check XML Element Exists

```
// Response: <user id="123"><name>Alice</name></user>

create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户元素存在"
- properties:
  - XPath.xpath: "//user"
  - XPath.tolerant: "false"
```

### Example 2: Check Attribute with Tolerant Parser (HTML)

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_HTML页面包含div"
- properties:
  - XPath.xpath: "//div[@class='container']"
  - XPath.tolerant: "true"
  - XPath.quiet: "true"
```

### Example 3: Check Element Text Value

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_用户名为Alice"
- properties:
  - XPath.xpath: "//user/name[text()='Alice']"
  - XPath.tolerant: "false"
```

### Example 4: Invert Assertion (Fail if Found)

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_不包含error元素"
- properties:
  - XPath.xpath: "//error"
  - XPath.tolerant: "false"
  - XPath.negate: "true"
```

### Example 5: Validate with Namespace and DTD

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_SOAP命名空间正确"
- properties:
  - XPath.xpath: "//soap:Body"
  - XPath.tolerant: "false"
  - XPath.namespace: "true"
  - XPath.validate: "true"
  - XPath.download_dtds: "false"
```

### Example 6: Boolean XPath Expression

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_错误数量为0"
- properties:
  - XPath.xpath: "count(//error)=0"
  - XPath.tolerant: "false"
```

### Example 7: Apply to Variable with Tolerant Parser

```
create_jmeter_element with:
- elementType: "xpathassertion"
- elementName: "断言_变量中的XML格式"
- properties:
  - Assertion.scope: "variable"
  - Scope.variable: "responseXml"
  - XPath.xpath: "//status"
  - XPath.tolerant: "true"
  - XPath.report_errors: "true"
  - XPath.show_warnings: "true"
```

## Best Practices

1. **Use tolerant parser for HTML**: HTML responses are rarely well-formed XML; set `XPath.tolerant: "true"` for HTML content.
2. **Test XPath first**: Verify expressions in View Results Tree before using them in assertions.
3. **Use specific paths**: Prefer `//user/name` over `//name` for reliability.
4. **Disable namespaces if having issues**: Set `XPath.namespace: "false"` to avoid namespace-related parsing problems.
5. **Avoid DTD downloads in production**: Setting `XPath.download_dtds: "true"` can slow down tests significantly.

## Notes

- The non-tolerant parser can be quite slow as it may need to download DTDs.
- Tidy parser options (`quiet`, `report_errors`, `show_warnings`) only apply when `tolerant: "true"`.
- Standard XML parser options (`namespace`, `validate`, `whitespace`, `download_dtds`) only apply when `tolerant: "false"`.
- Using `/` as the XPath expression matches any well-formed document (the default).
- The assertion supports boolean XPath expressions like `count(//error)=0`.
- For namespace support, provide a properties file with prefix-to-URI mappings.
