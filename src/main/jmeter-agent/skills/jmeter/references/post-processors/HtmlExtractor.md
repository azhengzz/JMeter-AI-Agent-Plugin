# CSS Selector Extractor

## Description

CSS Selector Extractor uses CSS Selector or jQuery expressions to extract data from HTML responses and store them in variables. Supports both JSOUP and JODD parsing engines.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `HtmlExtractor.refname` | Yes | Variable name for extracted value | `product_name` |
| `HtmlExtractor.expr` | Yes | CSS Selector or jQuery expression | `div.product > h2.title` |
| `HtmlExtractor.attribute` | No | Attribute to extract (empty = text content) | `href`, `src`, `data-id` |
| `HtmlExtractor.match_number` | No | Which match to use (0=random, 1=first, -1=all) | `1` |
| `HtmlExtractor.default` | No | Default value if no match found | `NOT_FOUND` |
| `HtmlExtractor.default_empty_value` | No | Set empty string as default | `false` |
| `HtmlExtractor.extractor_impl` | No | Extractor implementation (empty=JSOUP) | `JSOUP` or `JODD` |

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

// Access as ${price_1}, ${price_2}, ${price_3}, etc.
// ${price_matchNr} contains the count
```

### Example 4: Extract Data Attribute

```
create_jmeter_element with:
- elementType: "htmlextractor"
- elementName: "提取产品ID"
- properties:
  - HtmlExtractor.refname: "product_id"
  - HtmlExtractor.expr: "div.product-card"
  - HtmlExtractor.attribute: "data-product-id"
  - HtmlExtractor.match_number: "1"
```

### Example 5: Complex CSS Selector

```
create_jmeter_element with:
- elementType: "htmlextractor"
- elementName: "提取特定行数据"
- properties:
  - HtmlExtractor.refname: "cell_value"
  - HtmlExtractor.expr: "table#dataTable tr.row-active td:nth-child(3)"
  - HtmlExtractor.attribute: ""
  - HtmlExtractor.match_number: "1"
```

## CSS Selector Patterns

| Selector | Description | Example |
|----------|-------------|---------|
| `element` | Tag name | `div`, `a`, `span` |
| `#id` | Element by ID | `#username` |
| `.class` | Element by class | `.btn-primary` |
| `element.class` | Tag with class | `div.active` |
| `parent > child` | Direct child | `ul > li` |
| `ancestor descendant` | Any descendant | `form input` |
| `[attr]` | Has attribute | `[data-id]` |
| `[attr=value]` | Attribute equals | `[type="text"]` |
| `:first-child` | First child | `li:first-child` |
| `:last-child` | Last child | `li:last-child` |
| `:nth-child(n)` | Nth child | `td:nth-child(2)` |

## Attribute Values

| Attribute | Description |
|-----------|-------------|
| (empty) | Extract text content |
| `href` | Link URL |
| `src` | Image/source URL |
| `id` | Element ID |
| `class` | CSS class names |
| `data-*` | Custom data attribute |
| `value` | Input value |
| `name` | Element name |

## Match Number Options

| Value | Description |
|-------|-------------|
| `0` | Random match |
| `1` | First match (default) |
| `n` | Nth match |
| `-1` | All matches |

## Extractor Implementation

| Value | Description |
|-------|-------------|
| (empty) | JSOUP (recommended, better compatibility) |
| `JSOUP` | JSOUP parser |
| `JODD` | JODD parser (legacy) |

## Best Practices

1. **Use specific selectors**: More specific = more reliable extraction
2. **Prefer JSOUP**: Better HTML tolerance and performance
3. **Test with View Results Tree**: Verify selectors match correctly
4. **Use attributes**: Extract attributes directly when possible
5. **Handle no match**: Set default value for missing data

## Tips

1. **Debug mode**: Add Debug Sampler to view extracted values
2. **Text vs attributes**: Leave attribute empty for text content
3. **Complex selectors**: Combine classes, IDs, and structural selectors
4. **Multiple matches**: Use -1 to extract all matching elements
5. **Nth-child**: Use 1-based indexing for nth-child selectors

## Common HTML Patterns

```
// ID selector
#submit-button

// Class selector
.btn-primary

// Attribute selector
input[name="username"]

// Structural selector
div.container > ul.menu > li.item

// Multiple classes
div.card.product.active

// Data attribute
[data-product-id]

// Comma-separated (OR)
div.title, h1.title
```

## Notes

- JSOUP is the default and recommended implementation
- CSS selectors are more readable than XPath for HTML
- Use jQuery syntax for complex selectors
- Attribute names are case-sensitive
