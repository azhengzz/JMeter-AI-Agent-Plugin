# __unescape

## Function Name
`__unescape`

## Category
String

## Description
The unescape function returns the result of evaluating a Java-escaped string. See also `__char()` above.

This allows one to add characters to fields which are otherwise tricky (or impossible) to define via the GUI.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to unescape | The string to be unescaped. | Yes | - |

## Usage Examples

### Basic Usage (CRLF)
```
${__unescape(\r\n)}
```
Returns `CRLF` (carriage return + line feed).

### Tab Character
```
${__unescape(1\t2)}
```
Returns `1[tab]2` (with an actual tab character between 1 and 2).

## Notes
- This function processes Java escape sequences (e.g., `\n`, `\t`, `\r`, `\\`, etc.).
- See also `__char()` for generating Unicode characters from numeric codes.

## Since
2.3.3

## Reference
- [Apache JMeter - __unescape](https://jmeter.apache.org/usermanual/functions.html#__unescape)
