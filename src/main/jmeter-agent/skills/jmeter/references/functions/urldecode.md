# __urldecode

## Function Name
`__urldecode`

## Category
String

## Description
Function to decode a `application/x-www-form-urlencoded` string. Note: use UTF-8 as the encoding scheme.

For example, the string:

```
${__urldecode(Word+%22school%22+is+%22%C3%A9cole%22+in+french)}
```

returns `Word "school" is "ecole" in french` (with the actual e-acute character).

Uses Java class [URLDecoder](http://docs.oracle.com/javase/7/docs/api/java/net/URLDecoder.html).

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to decode | The string with URL encoded chars to decode. | Yes | - |

## Usage Examples

### Basic Usage
```
${__urldecode(Word+%22school%22+is+%22%C3%A9cole%22+in+french)}
```
Returns `Word "school" is "ecole" in french` (with actual accented characters).

### Decode Simple Value
```
${__urldecode(Hello%20World)}
```
Returns `Hello World`.

## Notes
- Uses UTF-8 as the encoding scheme.
- Uses Java class [URLDecoder](http://docs.oracle.com/javase/7/docs/api/java/net/URLDecoder.html).

## Since
2.10

## Reference
- [Apache JMeter - __urldecode](https://jmeter.apache.org/usermanual/functions.html#__urldecode)
