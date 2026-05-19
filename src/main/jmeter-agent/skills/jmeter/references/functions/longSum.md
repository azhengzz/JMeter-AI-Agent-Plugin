# __longSum

## Function Name
`__longSum`

## Category
Calculation

## Description
The longSum function can be used to compute the sum of two or more long values. Use this instead of __intSum whenever you know your values will not be in the interval -2147483648 to 2147483647.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| First argument | The first long value. | Yes | - |
| Second argument | The second long value. | Yes | - |
| nth argument | The nth long value. | No | - |
| last argument | A reference name for reusing the value computed by this function. If specified, the reference name must contain at least one non-numeric character otherwise it will be treated as another long value to be added. | No | - |

## Usage Examples

### Basic Usage
```
${__longSum(2,5,MYVAR)}
```
Returns 7 (2+5) and stores the result in `MYVAR` variable. So `${MYVAR}` will be equal to 7.

### Sum of Three Values
```
${__longSum(2,5,7)}
```
Returns 14 (2+5+7). Note: `7` is treated as another value to add, not as a variable name, because it is a valid integer.

### Using Variables
```
${__longSum(1,2,5,${MYVAR})}
```
Returns 16 if `MYVAR` value is equal to 8 (1+2+5+8).

### Without Variable Name
```
${__longSum(3000000000,1000000000)}
```
Returns 4000000000. Use this instead of `__intSum` for values outside the int range (-2,147,483,648 to 2,147,483,647).

## Notes
- Use this instead of `__intSum` whenever your values will not be in the interval -2,147,483,648 to 2,147,483,647.
- The reference name is optional, but it must not be a valid integer. If the reference name is a valid integer, it will be treated as another value to be added.

## Since
2.3.2

## Reference
- [Apache JMeter - __longSum](https://jmeter.apache.org/usermanual/functions.html#__longSum)
