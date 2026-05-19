# __urlencode

## Function Name
`__urlencode`

## Category
String

## Description
Function to encode a string to a `application/x-www-form-urlencoded` string.

For example, the string:

```
${__urlencode(Word "school" is "ecole" in french)}
```

returns `Word+%22school%22+is+%22%C3%A9cole%22+in+french` (with the e-acute encoded as `%C3%A9`).

Uses Java class [URLEncoder](http://docs.oracle.com/javase/7/docs/api/java/net/URLEncoder.html).

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to encode | String to encode in URL encoded chars. | Yes | - |

## Usage Examples

### Basic Usage
```
${__urlencode(Word "school" is "ecole" in french)}
```
Returns `Word+%22school%22+is+%22%C3%A9cole%22+in+french`.

### Encode Query Parameter
```
${__urlencode(hello world & test=value)}
```
Returns `hello+world+%26+test%3Dvalue`.

## Notes
- Uses Java class [URLEncoder](http://docs.oracle.com/javase/7/docs/api/java/net/URLEncoder.html).

## Since
2.10

## Reference
- [Apache JMeter - __urlencode](https://jmeter.apache.org/usermanual/functions.html#__urlencode)
