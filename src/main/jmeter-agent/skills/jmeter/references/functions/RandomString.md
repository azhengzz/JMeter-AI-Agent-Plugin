# __RandomString

## Function Name
`__RandomString`

## Category
Calculation

## Description
The RandomString function returns a random String of length using characters in chars to use.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Length | A number length of generated String | Yes | - |
| Characters to use | Chars used to generate String | No | - |
| Variable Name | A reference name for reusing the value computed by this function. | No | - |

## Usage Examples

### Basic Usage
```
${__RandomString(5)}
```
Returns a random string of 5 characters which can be readable or not.

### With Custom Character Set
```
${__RandomString(10,abcdefg)}
```
Returns a random string of 10 characters picked from `abcdefg` set, like `cdbgdbeebd` or `adbfeggfad`.

### With Variable Name
```
${__RandomString(6,a12zeczclk, MYVAR)}
```
Returns a random string of 6 characters picked from `a12zeczclk` set and stores the result in `MYVAR`. `MYVAR` will contain string like `2z22ak` or `z11kce`.

### Alphanumeric String
```
${__RandomString(8,ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789)}
```
Returns a random alphanumeric string of 8 characters.

## Notes
- If no character set is specified, the generated string may contain any characters and might not be readable.

## Since
2.6

## Reference
- [Apache JMeter - __RandomString](https://jmeter.apache.org/usermanual/functions.html#__RandomString)
