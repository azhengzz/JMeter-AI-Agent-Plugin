# Regular Expression Extractor

## Description

Regular Expression Extractor uses regex patterns to extract data from server responses and store them in variables for use in subsequent requests.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `RegexExtractor.refName` | Yes | Variable name for extracted value | `token` |
| `RegexExtractor.regex` | Yes | Regular expression pattern | `JSESSIONID=(.+?);` |
| `RegexExtractor.template` | Yes | Template for extracted value | `$1$` |
| `RegexExtractor.matchNumber` | No | Which match to use (0=random, 1=first, -1=all) | `1` |
| `RegexExtractor.default` | No | Default value if no match found | `NOT_FOUND` |

## Usage Examples

### Example 1: Extract Session ID

```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取SessionID"
- properties:
  - RegexExtractor.refName: "session_id"
  - RegexExtractor.regex: "JSESSIONID=(.+?);"
  - RegexExtractor.template: "$1$"
  - RegexExtractor.matchNumber: "1"
  - RegexExtractor.default: "NO_SESSION"

// Use in subsequent request
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "带Session的请求"
- properties:
  - HTTPSampler.path: "/api/data"
  - HeaderManager.headers:
    - Cookie: "JSESSIONID=${session_id}"
```

### Example 2: Extract Value from JSON

```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取用户ID"
- properties:
  - RegexExtractor.refName: "user_id"
  - RegexExtractor.regex: ""userId"\s*:\s*"([^"]+)""
  - RegexExtractor.template: "$1$"
  - RegexExtractor.matchNumber: "1"
```

### Example 3: Extract Multiple Values

```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取所有链接"
- properties:
  - RegexExtractor.refName: "link"
  - RegexExtractor.regex: "<a href=\"([^\"]+)\""
  - RegexExtractor.template: "$1$"
  - RegexExtractor.matchNumber: "-1"  // Extract all matches

// Access as ${link_1}, ${link_2}, ${link_3}, etc.
// ${link_matchNr} contains the count of matches
```

### Example 4: Extract with Groups

```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取姓名和邮箱"
- properties:
  - RegexExtractor.refName: "user"
  - RegexExtractor.regex: "name:\s*([^,]+),\s*email:\s*([^,]+)"
  - RegexExtractor.template: "$1$"  // First group (name)
  - RegexExtractor.matchNumber: "1"

// For second group (email), create another extractor with template "$2$"
```

## Regex Patterns

### Common Patterns

| Pattern | Description | Example |
|---------|-------------|---------|
| `(.+?)` | Non-greedy match of any characters | `value=(.+?)` |
| `(.*)` | Greedy match of any characters | `<div>(.*)</div>` |
| `(\d+)` | Match digits | `id=(\d+)` |
| `([^"]+)` | Match non-quote characters | `"name":"([^"]+)"` |
| `\s+` | Match whitespace | `name\s*:\s*` |

### Examples

```
// Extract from HTML
<input type="hidden" name="token" value="abc123" />
Regex: name="token"\s+value="([^"]+)"
Template: $1$

// Extract from URL
https://example.com/user/12345/profile
Regex: /user/(\d+)/
Template: $1$

// Extract from JSON
{"status":"success","data":{"id":123}}
Regex: "status"\s*:\s*"([^"]+)"
Template: $1$
```

## Template Patterns

| Template | Description |
|----------|-------------|
| `$1$` | First captured group |
| `$2$` | Second captured group |
| `$0$` | Entire match |
| `$1_$2$` | Concatenate groups |

## Match Number Options

| Value | Description |
|-------|-------------|
| `0` | Random match |
| `1` | First match (default) |
| `n` | Nth match |
| `-1` | All matches (stored as `var_1`, `var_2`, etc.) |

## Extracted Variables

For reference name `token`:
- `${token}`: The extracted value
- `${token_g0}`: Entire match
- `${token_g1}`: First group
- `${token_gn}`: Nth group
- `${token_matchNr}`: Number of matches (when matchNumber=-1)

## Best Practices

1. **Use specific patterns**: More specific = more reliable extraction
2. **Test with View Results Tree**: Verify regex matches correctly
3. **Handle no match**: Set default value for missing data
4. **Use non-greedy**: Prefer `(.+?)` over `(.*)` for precision
5. **Escape properly**: Escape special regex characters

## Tips

1. **Debug mode**: Add Debug Sampler to view extracted values
2. **Boundary strings**: Use Boundary Extractor for simpler text extraction
3. **JSON data**: Prefer JSON Path Extractor for JSON responses
4. **Performance**: Regex is slower than JSON Path for structured data

## Notes

- Regex applies to response body (not headers)
- Case-sensitive by default
- Use Boundary Extractor for simpler text extraction
- For JSON, JSON Path Extractor is more reliable
