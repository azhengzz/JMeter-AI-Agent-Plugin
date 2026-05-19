# __char

## Function Name
`__char`

## Category
String

## Description
The char function returns the result of evaluating a list of numbers as Unicode characters. See also `__unescape()`, below.

This allows one to add arbitrary character values into fields.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Unicode character number (decimal or 0xhex) | The decimal number (or hex number, if prefixed by `0x`, or octal, if prefixed by `0`) to be converted to a Unicode character. | Yes | - |

## Usage Examples

### Basic Usage
```
${__char(13,10)}
```
Returns `CRLF` (carriage return + line feed).

### Hexadecimal Format
```
${__char(0xD,0xA)}
```
Returns `CRLF` (same as decimal 13,10 using hex notation).

### Octal Format
```
${__char(015,012)}
```
Returns `CRLF` (same using octal notation).

### Single Character
```
${__char(165)}
```
Returns the yen sign character.

## Notes
- Supports decimal, hexadecimal (prefixed with `0x`), and octal (prefixed with `0`) number formats.
- Multiple character numbers can be provided as a comma-separated list.
- See also `__unescape()` for processing Java-escaped strings.

## Since
2.3.3

## Reference
- [Apache JMeter - __char](https://jmeter.apache.org/usermanual/functions.html#__char)
