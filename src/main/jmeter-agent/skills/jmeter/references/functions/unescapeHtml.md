# __unescapeHtml

## Function Name
`__unescapeHtml`

## Category
String

## Description
Function to unescape a string containing HTML entity escapes to a string containing the actual Unicode characters corresponding to the escapes. Supports HTML 4.0 entities.

For example, the string:

```
${__unescapeHtml(&lt;Fran&ccedil;ais&gt;)}
```

will return `<Francais>` (with actual cedilla on the 'c').

If an entity is unrecognized, it is left alone, and inserted verbatim into the result string. e.g. `${__unescapeHtml(&gt;&zzzz;x)}` will return `>&zzzz;x`.

Uses `StringEscapeUtils#unescapeHtml(String)` from Commons Lang.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to unescape | The string to be unescaped. | Yes | - |

## Usage Examples

### Basic Usage
```
${__unescapeHtml(&lt;Fran&ccedil;ais&gt;)}
```
Returns `<Francais>` (with the actual Unicode characters for the HTML entities).

### Unrecognized Entity
```
${__unescapeHtml(&gt;&zzzz;x)}
```
Returns `>&zzzz;x` - unrecognized entities are left as-is.

## Notes
- Supports HTML 4.0 entities.
- If an entity is unrecognized, it is left alone and inserted verbatim into the result string.
- Uses `StringEscapeUtils#unescapeHtml(String)` from Commons Lang.

## Since
2.3.3

## Reference
- [Apache JMeter - __unescapeHtml](https://jmeter.apache.org/usermanual/functions.html#__unescapeHtml)
