# CSS Selector Extractor

## Description
Allows the user to extract values from a server HTML response using a CSS Selector syntax. As a post-processor, this element will execute after each Sample request in its scope, applying the CSS/JQuery expression, extracting the requested nodes, extracting the node as text or attribute value and store the result into the given variable name.

## Parameters
| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Sample.scope` | No | `"parent"` | Scope for extraction. See Scope options below. | `"parent"` |
| `Scope.variable` | No | -- | JMeter variable name to extract from. Only used when scope is `variable`. | `"response_body"` |
| `HtmlExtractor.refname` | Yes | -- | The name of the JMeter variable in which to store the result. | `"product_name"` |
| `HtmlExtractor.expr` | Yes | -- | The CSS/JQuery selector used to select nodes from the response data. | `"div.product > h2.title"` |
| `HtmlExtractor.attribute` | No | `""` | Name of attribute to extract from matched elements. If empty, the combined text of the element and all its children will be returned. | `"href"` |
| `HtmlExtractor.match_number` | No | `"1"` | Which match to use. 0=random, 1=first, N=Nth match, -1=all matches. | `"1"` |
| `HtmlExtractor.default` | No | `""` | Default value if the expression does not match. Particularly useful for debugging. | `"NOT_FOUND"` |
| `HtmlExtractor.default_empty_value` | No | `false` | If checked and Default Value is empty, sets variable to empty string instead of not setting it. | `"false"` |
| `HtmlExtractor.extractor_impl` | No | `""` | Extractor implementation. See implementation options below. | `"JSOUP"` |

### Scope Options
| Value | Description |
|-------|-------------|
| `parent` | Main sample only (default) |
| `all` | Main sample and sub-samples |
| `children` | Sub-samples only |
| `variable` | JMeter variable (use with `Scope.variable`) |

### Extractor Implementation Options
| Value | Description |
|-------|-------------|
| `""` | Default (JSOUP, recommended) |
| `JSOUP` | JSoup parser - better HTML tolerance |
| `JODD` | Jodd-Lagarto (CSSelly) - legacy compatibility |

### Match Number Options
| Value | Description |
|-------|-------------|
| `0` | Random match |
| `1` | First match (default) |
| `N` | Nth match |
| `-1` | All matches (creates `var_1`, `var_2`, etc.) |

## Usage Examples
### Example 1: Extract Text Content
```
create_jmeter_element with:
- elementType: "htmlextractor"
- elementName: "提取商品名称"
- properties:
  - HtmlExtractor.refname: "product_name"
  - HtmlExtractor.expr: "div.product > h2.title"
  - HtmlExtractor.attribute: ""
  - HtmlExtractor.match_number: "1"
```

### Example 2: Extract Attribute Value
```
create_jmeter_element with:
- elementType: "htmlextractor"
- elementName: "提取链接地址"
- properties:
  - HtmlExtractor.refname: "product_url"
  - HtmlExtractor.expr: "a.product-link"
  - HtmlExtractor.attribute: "href"
  - HtmlExtractor.match_number: "1"
```

### Example 3: Extract All Matching Elements
```
create_jmeter_element with:
- elementType: "htmlextractor"
- elementName: "提取所有价格"
- properties:
  - HtmlExtractor.refname: "price"
  - HtmlExtractor.expr: "span.price"
  - HtmlExtractor.attribute: ""
  - HtmlExtractor.match_number: "-1"
  - HtmlExtractor.default: "NO_PRICE"

// Access as ${price_1}, ${price_2}, ${price_3}, etc.
// ${price_matchNr} contains the count
```

### Example 4: Extract from JMeter Variable
```
create_jmeter_element with:
- elementType: "htmlextractor"
- elementName: "从变量中提取数据"
- properties:
  - HtmlExtractor.refname: "extracted_value"
  - HtmlExtractor.expr: "div.content > p"
  - Sample.scope: "variable"
  - Scope.variable: "stored_html"
```

### Example 5: Extract with JODD Implementation
```
create_jmeter_element with:
- elementType: "htmlextractor"
- elementName: "使用JODD提取数据"
- properties:
  - HtmlExtractor.refname: "title"
  - HtmlExtractor.expr: "h1.page-title"
  - HtmlExtractor.extractor_impl: "JODD"
  - HtmlExtractor.match_number: "1"
```

## Best Practices
1. **Use specific selectors**: More specific selectors yield more reliable extraction
2. **Prefer JSOUP**: Better HTML tolerance and performance (default implementation)
3. **Test with View Results Tree**: Verify selectors match correctly before production
4. **Use attributes**: Extract attributes directly when the attribute value is what you need
5. **Set default values**: Always set a default value for debugging and to handle missing data

## Notes
- JSOUP is the default and recommended implementation
- CSS selectors are generally more readable than XPath for HTML extraction
- If selector implementation is set to empty, JSOUP will be used
- When match_number is -1, variables are created as `refname_1`, `refname_2`, etc., and `refname_matchNr` contains the count
- Attribute extraction uses JSoup `Element#attr(name)` when an attribute is set, or `Element#text()` when empty
