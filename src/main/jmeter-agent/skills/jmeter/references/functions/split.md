# __split

## Function Name
`__split`

## Category
Variables

## Description
The split function splits the string passed to it according to the delimiter, and returns the original string. If any delimiters are adjacent, `?` is returned as the value.
The split strings are returned in the variables `${VAR_1}`, `${VAR_2}` etc.
The count of variables is returned in `${VAR_n}`.
A trailing delimiter is treated as a missing variable, and `?` is returned.
Also, to allow it to work better with the ForEach controller, `__split` now deletes the first unused variable in case it was set by a previous split.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to split | A delimited string, e.g. `a\|b\|c` | Yes | - |
| Name of variable | A reference name for reusing the value computed by this function. | Yes | - |
| Delimiter | The delimiter character, e.g. `\|`. If omitted, `,` is used. Note that `,` would need to be specified as `\,`. | No | `,` |

## Usage Examples

### Basic Usage
```
${__split(a|b|c,VAR,|)}
```
Returns `a|b|c` and sets the following variables:
- `VAR_n`=`3`
- `VAR_1`=`a`
- `VAR_2`=`b`
- `VAR_3`=`c`
- `VAR_4`=`null`

### Split with Adjacent Delimiters
Define `VAR`=`a||c|` in the test plan.
```
${__split(${VAR},VAR,|)}
```
Returns the contents of `VAR`, i.e. `a||c|` and sets the following variables:
- `VAR_n`=`4`
- `VAR_1`=`a`
- `VAR_2`=`?`
- `VAR_3`=`c`
- `VAR_4`=`?`
- `VAR_5`=`null`

### Split with Comma Delimiter
```
${__split(apple,banana,cherry,FRUIT)}
```
Returns `apple,banana,cherry` and sets:
- `FRUIT_n`=`3`
- `FRUIT_1`=`apple`
- `FRUIT_2`=`banana`
- `FRUIT_3`=`cherry`

## Notes
- If any delimiters are adjacent, `?` is returned as the value for the missing element.
- A trailing delimiter is treated as a missing variable, and `?` is returned.
- The function deletes the first unused variable in case it was set by a previous split, to allow it to work better with the ForEach controller.
- The count of variables is returned in `${VAR_n}`.
- The default delimiter is `,`. To use a comma as delimiter, specify it as `\,`.

## Since
2.0.2

## Reference
- [Apache JMeter - __split](https://jmeter.apache.org/usermanual/functions.html#__split)
