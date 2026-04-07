# Boundary Extractor

## Description

Boundary Extractor extracts data between left and right boundary strings. It's simpler and more efficient than Regular Expression Extractor for basic text extraction.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `BoundaryExtractor.leftBoundary` | Yes | Left boundary string | `name="` |
| `BoundaryExtractor.rightBoundary` | Yes | Right boundary string | `"` |
| `BoundaryExtractor.refName` | Yes | Variable name for extracted value | `token` |
| `BoundaryExtractor.matchNumber` | No | Which match (1=first, 0=random, -1=all) | `1` |
| `BoundaryExtractor.default` | No | Default value if no match | `NOT_FOUND` |

## Usage Examples

### Example 1: Extract Attribute Value

```
// Response: <input name="csrf_token" value="abc123xyz"/>

create_jmeter_element with:
- elementType: "boundaryextractor"
- elementName: "提取CSRF Token"
- properties:
  - BoundaryExtractor.leftBoundary: "value=\""
  - BoundaryExtractor.rightBoundary: "\""
  - BoundaryExtractor.refName: "csrf_token"
  - BoundaryExtractor.matchNumber: "1"
```

### Example 2: Extract from HTML

```
// Response: <span class="user-id">12345</span>

create_jmeter_element with:
- elementType: "boundaryextractor"
- elementName: "提取用户ID"
- properties:
  - BoundaryExtractor.leftBoundary: "<span class=\"user-id\">"
  - BoundaryExtractor.rightBoundary: "</span>"
  - BoundaryExtractor.refName: "user_id"
```

### Example 3: Extract Between Text

```
// Response: Session ID: abc123xyz (expires in 1 hour)

create_jmeter_element with:
- elementType: "boundaryextractor"
- elementName: "提取Session ID"
- properties:
  - BoundaryExtractor.leftBoundary: "Session ID: "
  - BoundaryExtractor.rightBoundary: " ("
  - BoundaryExtractor.refName: "session_id"
```

### Example 4: Extract from JSON (Simple Cases)

```
// Response: {"access_token":"xyz789","token_type":"Bearer"}

create_jmeter_element with:
- elementType: "boundaryextractor"
- elementName: "提取Token"
- properties:
  - BoundaryExtractor.leftBoundary: "\"access_token\":\""
  - BoundaryExtractor.rightBoundary: "\""
  - BoundaryExtractor.refName: "access_token"
```

### Example 5: Extract Multiple Values

```
// Response: Found items: [A] [B] [C] in list

create_jmeter_element with:
- elementType: "boundaryextractor"
- elementName: "提取所有项目"
- properties:
  - BoundaryExtractor.leftBoundary: "["
  - BoundaryExtractor.rightBoundary: "]"
  - BoundaryExtractor.refName: "item"
  - BoundaryExtractor.matchNumber: "-1"

// Access as ${item_1}=A, ${item_2}=B, ${item_3}=C
```

## Match Number Options

| Value | Description |
|-------|-------------|
| `1` | First match (default) |
| `n` | Nth match |
| `0` | Random match |
| `-1` | All matches |

## Advantages over Regex

| Feature | Boundary Extractor | Regex Extractor |
|---------|-------------------|-----------------|
| Performance | Faster | Slower |
| Complexity | Simple | Complex |
| Reliability | More predictable | Regex complexity |
| Use Case | Known delimiters | Pattern matching |

## Best Practices

1. **Use for simple extraction**: When you know the boundaries
2. **Unique boundaries**: Use unique strings for reliability
3. **Test boundaries**: Verify with View Results Tree
4. **Escape quotes**: Use `\"` for quotes in boundaries
5. **Order matters**: Left boundary must come before right

## When to Use

### Use Boundary Extractor When:
- Response has predictable delimiters
- Extracting attribute values
- Simple text between known markers
- Better performance needed

### Use Regex Extractor When:
- Pattern matching required
- Complex extraction logic
- Boundaries not predictable
- Multiple formats in response

## Common Patterns

### HTML Attribute
```
<a href="https://example.com">Link</a>
Left: href="
Right: "
```

### HTML Element Content
```
<div class="price">$19.99</div>
Left: <div class="price">
Right: </div>
```

### JSON Field Value
```
{"name":"Alice"}
Left: "name":""
Right: "
```

### URL Parameter
```
https://example.com?token=abc123&id=456
Left: token=
Right: &
```

## Tips

1. **Make boundaries unique**: Avoid common substrings
2. **Include context**: Add surrounding text for uniqueness
3. **Handle edge cases**: Set default value for no match
4. **Debug mode**: Use Debug Sampler to verify
5. **Case sensitive**: Match is case-sensitive

## Escaping Special Characters

| Character | Escape |
|-----------|--------|
| Quote (`"`) | `\"` |
| Backslash (`\`) | `\\` |
| Newline | `\n` |
| Tab | `\t` |

## Notes

- Searches for left boundary, then right boundary after it
- Returns text between boundaries (excludes boundaries)
- Case-sensitive matching
- More efficient than Regex for simple cases
- For complex patterns, use Regex Extractor
