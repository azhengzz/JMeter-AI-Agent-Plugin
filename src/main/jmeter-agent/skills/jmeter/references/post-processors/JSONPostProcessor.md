# JSON Post Processor

## Description
The JSON PostProcessor enables you to extract data from JSON responses using JSON-PATH syntax. This post processor is very similar to the Regular Expression Extractor. It must be placed as a child of HTTP Sampler or any other sampler that has responses. It will allow you to extract in a very easy way text content.

## Parameters
| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Sample.scope` | No | `"parent"` | Scope for extraction. See Scope options below. | `"parent"` |
| `Scope.variable` | No | -- | JMeter variable name to extract from. Only used when scope is `variable`. | `"response_body"` |
| `JSONPostProcessor.referenceNames` | Yes | -- | Semicolon separated names of variables that will contain the results of JSON-PATH expressions. Must match number of JSON-PATH expressions. | `"token;userId"` |
| `JSONPostProcessor.jsonPathExprs` | Yes | -- | Semicolon separated JSON-PATH expressions. Must match number of variables. | `"$.token;$.userId"` |
| `JSONPostProcessor.match_numbers` | No | `"1"` | For each JSON Path Expression, which result to extract. Semicolon separated list matching number of expressions. See Match Number options below. | `"1"` |
| `JSONPostProcessor.defaultValues` | No | `""` | Semicolon separated default values if JSON-PATH expressions do not return any result. Must match number of variables. | `"NO_TOKEN;NO_ID"` |
| `JSONPostProcessor.compute_concat` | No | `false` | If checked and many results are found, concatenate them using `,` separator and store in a variable named `<variable name>_ALL`. | `"false"` |

### Scope Options
| Value | Description |
|-------|-------------|
| `parent` | Main sample only (default) |
| `all` | Main sample and sub-samples |
| `children` | Sub-samples only |
| `variable` | JMeter variable (use with `Scope.variable`) |

### Match Number Options
| Value | Description |
|-------|-------------|
| `0` | Random match (default when field is empty) |
| `1` | First match |
| `N` | Nth match; if greater than number of matches, default value is used |
| `-1` | All results, named as `<variable name>_N` (where N goes from 1 to number of results) |

## Usage Examples
### Example 1: Extract Token from Response
```
create_jmeter_element with:
- elementType: "jsonpostprocessor"
- elementName: "提取Token"
- properties:
  - JSONPostProcessor.referenceNames: "access_token"
  - JSONPostProcessor.jsonPathExprs: "$.access_token"
  - JSONPostProcessor.match_numbers: "1"
  - JSONPostProcessor.defaultValues: "NO_TOKEN"
```

### Example 2: Extract Multiple Values
```
create_jmeter_element with:
- elementType: "jsonpostprocessor"
- elementName: "提取用户信息"
- properties:
  - JSONPostProcessor.referenceNames: "user_id;user_name"
  - JSONPostProcessor.jsonPathExprs: "$.data.userId;$.data.userName"
  - JSONPostProcessor.match_numbers: "1;1"
  - JSONPostProcessor.defaultValues: "NO_ID;NO_NAME"
```

### Example 3: Extract All Array Items
```
create_jmeter_element with:
- elementType: "jsonpostprocessor"
- elementName: "提取所有项目"
- properties:
  - JSONPostProcessor.referenceNames: "item"
  - JSONPostProcessor.jsonPathExprs: "$.items[*]"
  - JSONPostProcessor.match_numbers: "-1"
  - JSONPostProcessor.compute_concat: "true"

// Access as ${item_1}, ${item_2}, ${item_3}, etc.
// ${item_matchNr} contains the count
// ${item_ALL} contains all items concatenated with ","
```

### Example 4: Extract with Filter Expression
```
create_jmeter_element with:
- elementType: "jsonpostprocessor"
- elementName: "提取活跃用户ID"
- properties:
  - JSONPostProcessor.referenceNames: "active_user_id"
  - JSONPostProcessor.jsonPathExprs: "$.users[?(@.status=='active')].id"
  - JSONPostProcessor.match_numbers: "1"
```

### Example 5: Extract from JMeter Variable
```
create_jmeter_element with:
- elementType: "jsonpostprocessor"
- elementName: "从变量中提取JSON数据"
- properties:
  - Sample.scope: "variable"
  - Scope.variable: "stored_json"
  - JSONPostProcessor.referenceNames: "field_value"
  - JSONPostProcessor.jsonPathExprs: "$.data.field"
  - JSONPostProcessor.match_numbers: "1"
```

## Variables Created

For reference name `token`:
- `${token}`: The extracted value
- `${token_1}`, `${token_2}`, ...: Individual matches (when match_numbers is `-1`)
- `${token_matchNr}`: Count of matches (when match_numbers is `-1`)
- `${token_ALL}`: All matches concatenated with `,` (when `compute_concat` is `true`)

## Best Practices
1. **Use JSONPath for JSON responses**: Prefer over Regex Extractor for structured JSON data
2. **Match counts carefully**: Number of variable names, expressions, and default values must match
3. **Set default values**: Always provide defaults for debugging and robustness
4. **Use compute_concat wisely**: Enable when you need all values as a single string
5. **Test with View Results Tree**: Verify JSONPath expressions match correctly

## Notes
- Parses JSON response body only; not applicable for non-JSON responses
- Multiple extractors are separated by semicolons in variable names, expressions, match numbers, and defaults
- The match_numbers field defaults to `0` (random) when left empty; set to `1` for first match
- JSONPath uses the JsonPath syntax (see https://github.com/json-path/JsonPath)
- More reliable than Regex Extractor for JSON data
