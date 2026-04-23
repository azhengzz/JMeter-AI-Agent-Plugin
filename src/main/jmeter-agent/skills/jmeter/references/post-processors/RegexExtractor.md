# Regular Expression Extractor

## Description
Allows the user to extract values from a server response using a Perl-type regular expression. As a post-processor, this element will execute after each Sample request in its scope, applying the regular expression, extracting the requested values, generate the template string, and store the result into the given variable name.

## Parameters
| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Sample.scope` | No | `"parent"` | Scope for extraction. See Scope options below. | `"parent"` |
| `Scope.variable` | No | -- | JMeter variable name to extract from. Only used when scope is `variable`. | `"response_body"` |
| `RegexExtractor.useHeaders` | No | `"false"` | Field to check. See Field options below. | `"false"` |
| `RegexExtractor.refname` | Yes | -- | The name of the JMeter variable in which to store the result. Each group is also stored as `[refname]_g#`, where `#` is the group number (0 = entire match). | `"token"` |
| `RegexExtractor.regex` | Yes | -- | The regular expression used to parse the response data. Must contain at least one set of parentheses `()` to capture a portion of the string. Do not enclose in `/ /`. | `"value=\"([^\"]*)\""` |
| `RegexExtractor.template` | Yes | `"$1$"` | The template used to create a string from the matches found. Use `$1$` for group 1, `$2$` for group 2, `$0$` for entire match. | `"$1$"` |
| `RegexExtractor.match_number` | No | `1` | Indicates which match to use. See Match Number options below. | `"1"` |
| `RegexExtractor.default` | No | `""` | Default value if the regular expression does not match. Particularly useful for debugging. Remove after debugging to keep variable unchanged when no match found. | `"NOT_FOUND"` |
| `RegexExtractor.default_empty_value` | No | `false` | If checked and Default Value is empty, sets variable to empty string instead of not setting it. Useful when extracted value is optional. | `"false"` |

### Scope Options
| Value | Description |
|-------|-------------|
| `parent` | Main sample only (default) |
| `all` | Main sample and sub-samples |
| `children` | Sub-samples only |
| `variable` | JMeter variable (use with `Scope.variable`) |

### Field to Check Options
| Value | Description |
|-------|-------------|
| `false` | Body - the body of the response (excluding headers) |
| `unescaped` | Body (unescaped) - HTML escape codes replaced. Note: impacts performance |
| `as_document` | Body as a Document - extract text via Apache Tika. Note: impacts performance |
| `true` or `request_headers` | Request Headers - may not be present for non-HTTP samples |
| `URL` | Request URL |
| `code` | Response Code (e.g. 200) |
| `message` | Response Message (e.g. OK) |

### Match Number Options
| Value | Description |
|-------|-------------|
| `0` | Random match |
| `1` | First match (default) |
| `N` | Nth match |
| `-1` | All matches (creates variables `refname_matchNr`, `refname_1`, `refname_2`, etc.) |

## Usage Examples
### Example 1: Extract Session ID
```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取SessionID"
- properties:
  - RegexExtractor.refname: "session_id"
  - RegexExtractor.regex: "JSESSIONID=([^;]+)"
  - RegexExtractor.template: "$1$"
  - RegexExtractor.match_number: "1"
  - RegexExtractor.default: "NO_SESSION"
```

### Example 2: Extract Value from JSON
```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取用户ID"
- properties:
  - RegexExtractor.refname: "user_id"
  - RegexExtractor.regex: "\"userId\"\\s*:\\s*\"([^\"]+)\""
  - RegexExtractor.template: "$1$"
  - RegexExtractor.match_number: "1"
```

### Example 3: Extract All Matching Links
```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取所有链接"
- properties:
  - RegexExtractor.refname: "link"
  - RegexExtractor.regex: "<a href=\"([^\"]+)\""
  - RegexExtractor.template: "$1$"
  - RegexExtractor.match_number: "-1"
  - RegexExtractor.default: "NO_LINK"

// Access as ${link_1}, ${link_2}, ${link_3}, etc.
// ${link_matchNr} contains the count of matches
```

### Example 4: Extract from Response Headers
```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取响应头中的Token"
- properties:
  - RegexExtractor.refname: "auth_token"
  - RegexExtractor.regex: "Authorization: Bearer ([^\r\n]+)"
  - RegexExtractor.template: "$1$"
  - RegexExtractor.useHeaders: "true"
  - RegexExtractor.match_number: "1"
```

### Example 5: Extract from JMeter Variable
```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "从变量中提取数据"
- properties:
  - Sample.scope: "variable"
  - Scope.variable: "response_data"
  - RegexExtractor.refname: "extracted_id"
  - RegexExtractor.regex: "\"id\"\\s*:\\s*(\\d+)"
  - RegexExtractor.template: "$1$"
  - RegexExtractor.match_number: "1"
```

### Example 6: Extract with Multiple Groups
```
create_jmeter_element with:
- elementType: "regexextractor"
- elementName: "提取姓名和邮箱"
- properties:
  - RegexExtractor.refname: "user"
  - RegexExtractor.regex: "name:\\s*([^,]+),\\s*email:\\s*([^,]+)"
  - RegexExtractor.template: "$1$"
  - RegexExtractor.match_number: "1"

// ${user} = first group (name)
// ${user_g0} = entire match
// ${user_g1} = first group (name)
// ${user_g2} = second group (email)
// ${user_g} = number of groups
```

## Extracted Variables

For reference name `token` with a successful match:
- `${token}`: The value constructed from the template
- `${token_g0}`: The entire regex match
- `${token_g1}`: First parenthesized group
- `${token_g2}`: Second parenthesized group
- `${token_g}`: The number of groups in the regex (excluding 0)

If no match occurs, `refName` is set to the default value (if provided). Group variables are removed.

If match_number is `-1`, additional variables are created:
- `${token_matchNr}`: Number of matches found (could be 0)
- `${token_1}`, `${token_2}`, ...: Value for each match
- `${token_1_g0}`, `${token_1_g1}`, ...: Groups for each match

## Best Practices
1. **Use specific patterns**: More specific regex patterns yield more reliable extraction
2. **Set default values**: Particularly useful for debugging to distinguish between no match vs wrong variable
3. **Use non-greedy quantifiers**: Prefer `.+?` over `.*` for precision
4. **Test with View Results Tree**: Verify regex matches correctly before production
5. **Consider alternatives**: For JSON responses, JSON Post Processor is more reliable; for simple text, Boundary Extractor is simpler

## Notes
- The regex must contain at least one set of parentheses `()` to capture a group, unless using `$0$` template
- Do not enclose the regular expression in `/ /` slashes
- For `unescaped` and `as_document` field options: note these options impact performance, use only when necessary
- When match_number is set to a positive number, matching stops as soon as enough matches are found
- The Body (unescaped) option processes HTML escapes without regard to context, so some incorrect substitutions may occur
- Headers may not be present for non-HTTP sample types
