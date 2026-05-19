# __intSum

## Function Name
`__intSum`

## Category
Calculation

## Description
The intSum function can be used to compute the sum of two or more integer values.

The reference name is optional, but it must not be a valid integer.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| First argument | The first int value. | Yes | - |
| Second argument | The second int value. | Yes | - |
| nth argument | The nth int value. | No | - |
| last argument | A reference name for reusing the value computed by this function. If specified, the reference name must contain at least one non-numeric character otherwise it will be treated as another int value to be added. | No | - |

## Usage Examples

### Basic Usage
```
${__intSum(2,5,MYVAR)}
```
Returns 7 (2+5) and stores the result in `MYVAR` variable. So `${MYVAR}` will be equal to 7.

### Sum of Three Values
```
${__intSum(2,5,7)}
```
Returns 14 (2+5+7). Note: `7` is treated as another value to add, not as a variable name, because it is a valid integer.

### Using Variables
```
${__intSum(1,2,5,${MYVAR})}
```
Returns 16 if `MYVAR` value is equal to 8 (1+2+5+8).

### Without Variable Name
```
${__intSum(3,4)}
```
Returns 7 (3+4) without storing the result.

## Notes
- The reference name is optional, but it must not be a valid integer. If the reference name is a valid integer, it will be treated as another value to be added.
- This function uses int values, which have a range of -2,147,483,648 to 2,147,483,647. For larger values, use `__longSum` instead.

## Since
1.8.1

## Reference
- [Apache JMeter - __intSum](https://jmeter.apache.org/usermanual/functions.html#__intSum)
