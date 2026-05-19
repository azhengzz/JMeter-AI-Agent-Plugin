# __escapeXml

## Function Name
`__escapeXml`

## Category
String

## Description
Function which escapes the characters in a String using XML 1.0 entities.

For example:

```
${__escapeXml("bread" & 'butter')}
```

returns `&quot;bread&quot; &amp; &apos;butter&apos;`.

Uses `StringEscapeUtils#escapeXml10(String)` from Commons Lang.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to escape | The string to be escaped. | Yes | - |

## Usage Examples

### Basic Usage
```
${__escapeXml("bread" & 'butter')}
```
Returns `&quot;bread&quot; &amp; &apos;butter&apos;`.

### Escape XML Special Characters
```
${__escapeXml(<tag attr="value">content</tag>)}
```
Escapes angle brackets, quotes, and ampersands to XML entities.

## Notes
- Escapes characters using XML 1.0 entities.
- Uses `StringEscapeUtils#escapeXml10(String)` from Commons Lang.
- The five XML 1.0 predefined entities that are escaped are: `&` (amp), `<` (lt), `>` (gt), `"` (quot), and `'` (apos).

## Since
3.2

## Reference
- [Apache JMeter - __escapeXml](https://jmeter.apache.org/usermanual/functions.html#__escapeXml)
