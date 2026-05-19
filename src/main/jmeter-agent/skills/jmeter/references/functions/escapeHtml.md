# __escapeHtml

## Function Name
`__escapeHtml`

## Category
String

## Description
Function which escapes the characters in a String using HTML entities. Supports HTML 4.0 entities.

For example, the string:

```
${__escapeHtml("bread" & "butter")}
```

returns `&quot;bread&quot; &amp; &quot;butter&quot;`.

Uses `StringEscapeUtils#escapeHtml(String)` from Commons Lang.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to escape | The string to be escaped. | Yes | - |

## Usage Examples

### Basic Usage
```
${__escapeHtml("bread" & "butter")}
```
Returns `&quot;bread&quot; &amp; &quot;butter&quot;`.

### Escape Special Characters
```
${__escapeHtml(<div>Test</div>)}
```
Escapes the angle brackets to HTML entities.

## Notes
- Supports HTML 4.0 entities.
- Uses `StringEscapeUtils#escapeHtml(String)` from Commons Lang.

## Since
2.3.3

## Reference
- [Apache JMeter - __escapeHtml](https://jmeter.apache.org/usermanual/functions.html#__escapeHtml)
